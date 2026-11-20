package com.jar.flowcapture.replay;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.LocatorBy;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import com.jar.flowcapture.execute.UtilityExecutor;
import com.jar.flowcapture.reuse.LocatorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a recorded flow step-by-step against the live device.
 *
 * This is the check that recording alone cannot give you. Every individual step worked at
 * the moment it was recorded — that is how it got recorded. What nobody knows until the
 * whole sequence runs back-to-back is whether the flow still works as a flow: whether a
 * locator captured on a loaded screen also resolves when the screen is still rendering,
 * whether a step depends on state left over from exploring, whether an element that was
 * unique then is ambiguous now. Those are exactly the failures that otherwise surface as a
 * flaky test some days after the code is generated and committed.
 *
 * It stops at the first failure on purpose. Every later step assumes the app is in the state
 * the failed step would have produced, so carrying on would report a cascade of failures
 * that are all the same failure, and would leave the phone somewhere arbitrary.
 */
@Slf4j
@Service
public class FlowReplayService {

    /**
     * Deliberately longer than the 20s used elsewhere for a single wait: during replay the
     * app is doing real work between steps (network calls, animations) with no human pause,
     * so a step can legitimately need longer here than it did while QA was clicking.
     */
    private static final int STEP_TIMEOUT_SECONDS = 25;

    private final AppiumSessionService appium;
    private final UtilityExecutor utilityExecutor;
    private final MethodCatalogService methodCatalog;
    private final LocatorIndexService locatorIndex;

    public FlowReplayService(AppiumSessionService appium, UtilityExecutor utilityExecutor,
                             MethodCatalogService methodCatalog, LocatorIndexService locatorIndex) {
        this.appium = appium;
        this.utilityExecutor = utilityExecutor;
        this.methodCatalog = methodCatalog;
        this.locatorIndex = locatorIndex;
    }

    public ReplayReport replay(RecordedFlow flow, boolean restartApp) {
        List<RecordedStep> steps = flow.getSteps();
        List<StepResult> results = new ArrayList<>();
        long started = System.currentTimeMillis();

        if (restartApp) {
            // Terminate and relaunch. This runs the app's startup path again, but it does NOT
            // reliably land on the home screen: Android restores the task, so the app usually
            // comes back on the screen it was on, scroll position included. Confirmed on a
            // real device — do not describe this to QA as a clean start.
            appium.closeApp();
            appium.openApp();
        }

        boolean aborted = false;
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep step = steps.get(i);
            int number = i + 1;

            if (aborted) {
                results.add(new StepResult(number, String.valueOf(step.getActionType()), label(step),
                        StepResult.Status.NOT_REACHED, null, 0, null));
                continue;
            }

            // A recovery step runs only if the try it backs up failed, so it is not part of
            // the main sequence. It is executed from the catch, below, and reported there.
            if (step.getCatchGroup() != null) {
                results.add(new StepResult(number, String.valueOf(step.getActionType()), label(step),
                        StepResult.Status.SKIPPED, "Backup step — runs only if its try block fails", 0, null));
                continue;
            }

            long stepStart = System.currentTimeMillis();
            StepResult result;
            try {
                result = runStep(step, number);
            } catch (Exception e) {
                // Anything unexpected is a failed step, not a failed replay — QA still gets
                // the report for the steps that already ran.
                result = new StepResult(number, String.valueOf(step.getActionType()), label(step),
                        StepResult.Status.FAILED, messageOf(e), 0, null);
            }
            result.setElapsedMs(System.currentTimeMillis() - stepStart);

            if (result.getStatus() != StepResult.Status.SKIPPED) {
                result.setScreenshotBase64(screenshotSafely());
            }
            results.add(result);

            // A failure inside a try/catch that carries on is reported and then stepped over,
            // because that is exactly what the generated test will do. Aborting here would
            // make the replay stricter than the code it is standing in for, and would stop a
            // flow on the one screen that was marked as optional in the first place.
            if (result.getStatus() == StepResult.Status.FAILED && tolerates(flow, step)) {
                result.setStatus(StepResult.Status.FAILED_TOLERATED);
                // The backup route. Running it here, at the moment the try failed, is what
                // makes the replay match the generated code: the catch body executes in the
                // state the failure left behind, not at the end of the flow.
                aborted = !runRecovery(flow, step.getTryGroup(), results);
            }
            if (result.getStatus() == StepResult.Status.FAILED) {
                aborted = true;
            }
        }

        int tolerated = count(results, StepResult.Status.FAILED_TOLERATED);
        int passed = count(results, StepResult.Status.PASSED);
        int failed = count(results, StepResult.Status.FAILED);
        int skipped = count(results, StepResult.Status.SKIPPED);
        int notReached = count(results, StepResult.Status.NOT_REACHED);
        ReplayReport report = new ReplayReport(flow.getFlowName(), results, passed, failed, skipped,
                notReached, tolerated, System.currentTimeMillis() - started);
        log.info("Replay of '{}': {}", flow.getFlowName(), report.getSummary());
        return report;
    }

    /**
     * Runs a block's backup steps after its try failed.
     *
     * Their results are appended to the report as they run, so the report reads in execution
     * order — which is the only order that explains what the device did. A backup step that
     * fails is a real failure: the catch was the second attempt, and there is no third.
     *
     * @return true when the flow can carry on
     */
    private boolean runRecovery(RecordedFlow flow, String blockId, List<StepResult> results) {
        List<RecordedStep> recovery = flow.recoveryStepsFor(blockId);
        if (recovery.isEmpty()) return true;

        log.info("Try block failed — running {} backup step(s)", recovery.size());
        for (RecordedStep step : recovery) {
            int number = flow.getSteps().indexOf(step) + 1;
            long stepStart = System.currentTimeMillis();
            StepResult result;
            try {
                result = runStep(step, number);
            } catch (Exception e) {
                result = new StepResult(number, String.valueOf(step.getActionType()), label(step),
                        StepResult.Status.FAILED, messageOf(e), 0, null);
            }
            result.setElapsedMs(System.currentTimeMillis() - stepStart);
            result.setLabel("(backup) " + result.getLabel());
            result.setScreenshotBase64(screenshotSafely());
            // Replace the "skipped — runs only if its try fails" placeholder with what happened.
            results.removeIf(r -> r.getStepNumber() == number
                    && r.getStatus() == StepResult.Status.SKIPPED);
            results.add(result);
            if (result.getStatus() == StepResult.Status.FAILED) return false;
        }
        return true;
    }

    /** Whether this step sits in a try/catch block whose answer to a failure is "carry on". */
    private boolean tolerates(RecordedFlow flow, RecordedStep step) {
        return flow.tryBlock(step.getTryGroup())
                .map(block -> block.getOnFailure() != com.jar.flowcapture.capture.TryBlock.OnFailure.FAIL_WITH_MESSAGE)
                .orElse(false);
    }

    private StepResult runStep(RecordedStep step, int number) {
        String type = String.valueOf(step.getActionType());
        String label = label(step);

        switch (step.getActionType()) {
            case SCREEN_BOUNDARY -> {
                return new StepResult(number, type, label, StepResult.Status.SKIPPED,
                        "Screen marker — generates a comment, nothing to run", 0, null);
            }
            case TAP -> {
                List<By> locators = locatorsFor(step);
                if (locators.isEmpty()) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED,
                            "No executable locator — the only candidate is index-based", 0, null);
                }
                By hit = appium.findFirstVisible(locators, STEP_TIMEOUT_SECONDS);
                if (hit == null) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED,
                            notFoundMessage(locators), 0, null);
                }
                String ambiguity = ambiguityOf(hit);
                if (ambiguity != null) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED, ambiguity, 0, null);
                }
                appium.clickResolved(hit);
                return new StepResult(number, type, label, StepResult.Status.PASSED, null, 0, null);
            }
            case INPUT -> {
                List<By> locators = locatorsFor(step);
                if (locators.isEmpty()) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED,
                            "No executable locator — the only candidate is index-based", 0, null);
                }
                By hit = appium.findFirstVisible(locators, STEP_TIMEOUT_SECONDS);
                if (hit == null) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED,
                            notFoundMessage(locators), 0, null);
                }
                String ambiguity = ambiguityOf(hit);
                if (ambiguity != null) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED, ambiguity, 0, null);
                }
                appium.typeIntoResolved(hit, step.getInputValue());
                return new StepResult(number, type, label, StepResult.Status.PASSED, null, 0, null);
            }
            case SCROLL_DOWN -> {
                appium.scrollDown();
                return new StepResult(number, type, label, StepResult.Status.PASSED, null, 0, null);
            }
            case SCROLL_UP -> {
                appium.scrollUp();
                return new StepResult(number, type, label, StepResult.Status.PASSED, null, 0, null);
            }
            case UTILITY_CALL -> {
                ReusableMethod method = methodCatalog.byKind(step.getUtilityMethodKind()).orElse(null);
                if (method == null) {
                    return new StepResult(number, type, label, StepResult.Status.FAILED,
                            "Unknown utility method: " + step.getUtilityMethodKind(), 0, null);
                }
                if (!utilityExecutor.isExecutable(method)) {
                    // Recorded on purpose, driven by hand — replay cannot perform it, and
                    // calling that a failure would be wrong.
                    return new StepResult(number, type, label, StepResult.Status.SKIPPED,
                            "Manual step — do it on the phone now, then the replay continues from "
                                    + "whatever state you leave it in", 0, null);
                }
                utilityExecutor.execute(method, step.getUtilityArgs());  // throws on failure
                return new StepResult(number, type, label, StepResult.Status.PASSED, null, 0, null);
            }
        }
        return new StepResult(number, type, label, StepResult.Status.SKIPPED, "Unhandled step type", 0, null);
    }

    /**
     * Every locator the generated code could resolve this element through, best-first.
     *
     * For a reused repo locator that means all the alternatives on the declaring field, not
     * the single one stored on the reference: a field declared with @AndroidFindAll is ORed
     * by PageFactory at runtime, so replaying only the first alternative is stricter than the
     * test it is meant to be checking, and fails on elements the test would find.
     */
    private List<By> locatorsFor(RecordedStep step) {
        List<By> locators = new ArrayList<>();
        ExistingLocatorRef reused = step.getReusedLocator();
        if (reused != null) {
            locatorIndex.findDeclared(reused.getLocatorsClassName(), reused.getFieldName())
                    .ifPresent(declared -> declared.candidates().stream()
                            .map(LocatorBy::toBy).filter(java.util.Objects::nonNull).forEach(locators::add));
            if (locators.isEmpty()) {
                By single = LocatorBy.toBy(
                        new CandidateLocator(reused.getStrategy(), reused.getLocatorValue(), null));
                if (single != null) locators.add(single);
            }
        }
        // The captured candidates come second: when QA reused a repo locator that is what the
        // test will use, but these are still valid ways to find the same element.
        if (step.getCandidateLocators() != null) {
            step.getCandidateLocators().stream()
                    .map(LocatorBy::toBy)
                    .filter(java.util.Objects::nonNull)
                    .filter(by -> !locators.contains(by))
                    .forEach(locators::add);
        }
        return locators;
    }

    /**
     * Why a step that "found its element" is still not a passing step.
     *
     * Finding an element proves only that something matched. When the locator matches many
     * elements the driver hands back the first one in document order, which for a container-
     * scoped XPath like //*[@resource-id="…nav_host_fragment"]//android.view.View is an outer
     * layout, not the card that was tapped during recording. Clicking it throws nothing and
     * changes nothing, so the step reports PASSED while the app has not moved — observed on a
     * real flow whose step 3 matched 104 elements and passed in half a second without opening
     * the card it was named after.
     *
     * The generated test would do exactly the same thing, so this is a defect in the step
     * rather than a quirk of replay, and it is reported as a failure.
     *
     * @return null when the locator is unambiguous
     */
    private String ambiguityOf(By locator) {
        int matches;
        try {
            matches = appium.countMatches(locator);
        } catch (RuntimeException e) {
            log.debug("Could not count matches for {}: {}", locator, e.getMessage());
            return null;   // never turn a counting problem into a step failure
        }
        if (matches <= 1) return null;
        return "Matched " + matches + " elements, so the click lands on whichever the driver "
                + "returns first — not necessarily this step's element. Re-point it in the "
                + "Inspector and pick a locator that matches exactly one. Locator: " + locator;
    }

    /** Names every locator that was tried, so a failure says what to go and look at. */
    private String notFoundMessage(List<By> locators) {
        String tried = locators.size() == 1
                ? locators.get(0).toString()
                : locators.size() + " locators, none matched: " + locators;
        return "Not found within " + STEP_TIMEOUT_SECONDS + "s — tried " + tried;
    }

    /**
     * A utility step's actionLabel is the catalog's full sentence description, which is right
     * for a button tooltip and far too long for a report row. Name the call instead, with its
     * bound argument where it has one, so the report reads like the code it is checking.
     */
    private String label(RecordedStep step) {
        if (step.getActionType() == RecordedStep.ActionType.SCREEN_BOUNDARY) {
            return "screen: " + (step.getScreenName() == null ? "(unnamed)" : step.getScreenName());
        }
        if (step.getActionType() == RecordedStep.ActionType.UTILITY_CALL) {
            ReusableMethod method = methodCatalog.byKind(step.getUtilityMethodKind()).orElse(null);
            if (method != null) return callName(method);
        }
        return step.getActionLabel() == null ? String.valueOf(step.getActionType()) : step.getActionLabel();
    }

    /** "navigateBackUntilElementFound(profileIcon)" — matches what the dashboard's method list shows. */
    private String callName(ReusableMethod method) {
        String call = method.getFullyQualifiedCall();
        int dot = call.lastIndexOf('.');
        String shortName = dot < 0 ? call : call.substring(dot + 1);
        return method.getPresetLocatorField() == null
                ? shortName
                : shortName + "(" + method.getPresetLocatorField() + ")";
    }

    private String messageOf(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        // Appium stack details run to dozens of lines; the first line carries the cause.
        return m.lines().findFirst().orElse(m);
    }

    /** A missing screenshot must never turn a passing step into a failing one. */
    private String screenshotSafely() {
        try {
            return appium.captureScreenshotBase64();
        } catch (Exception e) {
            log.debug("Replay screenshot failed: {}", e.getMessage());
            return null;
        }
    }

    private int count(List<StepResult> results, StepResult.Status status) {
        return (int) results.stream().filter(r -> r.getStatus() == status).count();
    }
}
