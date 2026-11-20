package com.jar.flowcapture.replay;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import com.jar.flowcapture.config.FlowCaptureProperties;
import com.jar.flowcapture.execute.UtilityExecutor;
import com.jar.flowcapture.reuse.LocatorIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jar.flowcapture.capture.ExistingLocatorRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.openqa.selenium.By;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowReplayServiceTest {

    /**
     * Stands in for the device. Records what was asked of it and lets a test decide which
     * element "cannot be found", which is the only interesting variable in replay.
     */
    private static class FakeDevice extends AppiumSessionService {
        final List<String> calls = new ArrayList<>();
        String failClicksContaining;

        FakeDevice() {
            super(new FlowCaptureProperties());
        }

        java.util.List<By> lastLocatorSet = java.util.List.of();

        /** How many elements a given locator "matches" on this fake screen; 1 unless a test says otherwise. */
        final java.util.Map<String, Integer> matchCounts = new java.util.HashMap<>();

        @Override
        public synchronized By findFirstVisible(java.util.List<By> locators, int timeoutSeconds) {
            lastLocatorSet = locators;
            return locators.stream()
                    .filter(by -> failClicksContaining == null || !by.toString().contains(failClicksContaining))
                    .findFirst().orElse(null);
        }

        @Override
        public synchronized int countMatches(By by) {
            return matchCounts.getOrDefault(by.toString(), 1);
        }

        @Override
        public synchronized void clickResolved(By by) {
            calls.add("click:" + by);
        }

        @Override
        public synchronized void typeIntoResolved(By by, String text) {
            calls.add("type:" + text);
        }

        @Override
        public synchronized void scrollDown() {
            calls.add("scrollDown");
        }

        @Override
        public synchronized String captureScreenshotBase64() {
            return "FAKEPNG";
        }

        @Override
        public synchronized void closeApp() {
            calls.add("closeApp");
        }

        @Override
        public synchronized void openApp() {
            calls.add("openApp");
        }
    }

    private final FakeDevice device = new FakeDevice();
    private final FlowReplayService service =
            new FlowReplayService(device, new UtilityExecutor(device), new MethodCatalogService(),
                    new LocatorIndexService(new FlowCaptureProperties()));

    private RecordedStep tap(String label, String resourceId) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(label);
        step.setCandidateLocators(List.of(
                new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, resourceId, "android.view.View")));
        return step;
    }

    private RecordedFlow flowWith(RecordedStep... steps) {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("SomeFlow");
        for (RecordedStep step : steps) flow.addStep(step);
        return flow;
    }

    @Test
    void everyStepRunsInOrderWhenNothingFails() {
        RecordedStep scroll = new RecordedStep();
        scroll.setActionType(RecordedStep.ActionType.SCROLL_DOWN);

        ReplayReport report = service.replay(
                flowWith(tap("tap one", "com.jar:id/one"), scroll, tap("tap two", "com.jar:id/two")), false);

        assertTrue(report.isSuccess());
        assertEquals(3, report.getPassed());
        assertEquals(0, report.getFailed());
        assertEquals(List.of("click:AppiumBy.id: com.jar:id/one", "scrollDown", "click:AppiumBy.id: com.jar:id/two"),
                device.calls);
    }

    /**
     * Everything after a failure assumes state the failed step never produced, so continuing
     * would report the same failure several times over and leave the phone somewhere random.
     */
    @Test
    void replayStopsAtTheFirstFailureAndMarksTheRestNotReached() {
        device.failClicksContaining = "two";

        ReplayReport report = service.replay(
                flowWith(tap("tap one", "com.jar:id/one"),
                        tap("tap two", "com.jar:id/two"),
                        tap("tap three", "com.jar:id/three")), false);

        assertFalse(report.isSuccess());
        assertEquals(1, report.getPassed());
        assertEquals(1, report.getFailed());
        assertEquals(1, report.getNotReached());
        assertEquals(StepResult.Status.NOT_REACHED, report.getResults().get(2).getStatus());
        assertTrue(report.getSummary().contains("Failed at step 2"), report.getSummary());
        assertFalse(device.calls.contains("click:AppiumBy.id: com.jar:id/three"),
                "the step after the failure must never touch the device");
    }

    /** A screen marker generates a comment; running it would mean nothing. */
    @Test
    void screenBoundariesAreSkippedNotFailed() {
        RecordedStep boundary = new RecordedStep();
        boundary.setActionType(RecordedStep.ActionType.SCREEN_BOUNDARY);
        boundary.setScreenName("PaymentScreen");

        ReplayReport report = service.replay(flowWith(boundary, tap("tap one", "com.jar:id/one")), false);

        assertEquals(StepResult.Status.SKIPPED, report.getResults().get(0).getStatus());
        assertTrue(report.isSuccess());
        assertEquals(1, report.getSkipped());
    }

    /**
     * PhonePe and the Dev Tools payment screen are driven by hand. Replay cannot perform
     * them, and calling that a failure would make every payment flow look broken.
     */
    @Test
    void manualStepsAreSkippedRatherThanFailed() {
        RecordedStep manual = new RecordedStep();
        manual.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        manual.setUtilityMethodKind(ReusableMethod.Kind.COMPLETE_PHONEPE_PAYMENT.name());
        manual.setActionLabel("complete the PhonePe payment");

        ReplayReport report = service.replay(flowWith(manual), false);

        assertEquals(StepResult.Status.SKIPPED, report.getResults().get(0).getStatus());
        assertTrue(report.getResults().get(0).getMessage().contains("Manual step"));
        assertTrue(report.isSuccess());
    }

    @Test
    void restartingTheAppHappensBeforeTheFirstStep() {
        service.replay(flowWith(tap("tap one", "com.jar:id/one")), true);

        assertEquals(List.of("closeApp", "openApp", "click:AppiumBy.id: com.jar:id/one"), device.calls);
    }

    /** A step with only an index-based locator cannot be executed, and silently passing it would be a lie. */
    @Test
    void anUnexecutableLocatorIsReportedAsAFailure() {
        RecordedStep brittle = new RecordedStep();
        brittle.setActionType(RecordedStep.ActionType.TAP);
        brittle.setActionLabel("tap the third row");
        brittle.setCandidateLocators(List.of(new CandidateLocator(
                CandidateLocator.Strategy.INDEX_BASED, "new UiSelector().instance(2)", "android.view.View")));

        ReplayReport report = service.replay(flowWith(brittle), false);

        assertFalse(report.isSuccess());
        assertTrue(report.getResults().get(0).getMessage().contains("index-based"));
    }

    /** Screenshots are evidence, not a dependency — losing one must not change any verdict. */
    @Test
    void everyExecutedStepCarriesTheScreenItLandedOn() {
        ReplayReport report = service.replay(flowWith(tap("tap one", "com.jar:id/one")), false);

        assertEquals("FAKEPNG", report.getResults().get(0).getScreenshotBase64());
    }

    /** The report should read like the code it is checking, not like a tooltip. */
    @Test
    void utilityStepsAreReportedByCallNameNotTheirLongDescription() {
        RecordedStep home = new RecordedStep();
        home.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        home.setUtilityMethodKind(ReusableMethod.Kind.RETURN_TO_HOME.name());
        home.setActionLabel("Presses back until the home screen profile icon is visible \u2014 the standard "
                + "start/end state for every test in this repo");

        ReplayReport report = service.replay(flowWith(home), false);

        assertEquals("navigateBackUntilElementFound(profileIcon)", report.getResults().get(0).getLabel());
    }

    /**
     * The failure QA hit: a step reusing HomeScreenLocators.profileIcon timed out after 25s
     * reporting one XPath, while the element was on screen under a DIFFERENT alternative of
     * the same @AndroidFindAll field. (On the live build the profile icon's content-desc is
     * "new profile Icon" when a badge is showing, so the two exact-match alternatives miss and
     * only the contains() one hits.) PageFactory ORs all four at runtime, so replaying only
     * the first was stricter than the test it was checking.
     */
    @Test
    void aReusedRepoLocatorReplaysWithEveryAlternativeOnTheField(@TempDir Path repo) throws Exception {
        Path dir = repo.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("HomeScreenLocators.java"), """
                package locaters.goldLocators;
                public class HomeScreenLocators {
                    @AndroidFindAll({
                            @AndroidBy(xpath = "//android.view.View[@content-desc='ProfileIcon']"),
                            @AndroidBy(xpath = "//android.view.View[@content-desc='profile Icon']"),
                            @AndroidBy(xpath = "(//android.view.View[contains(@content-desc,'profile')])[1]"),
                            @AndroidBy(xpath = "//android.widget.ImageView[@content-desc='ProfileIcon']")
                    })
                    private WebElement profileIcon;
                }
                """);
        FlowCaptureProperties properties = new FlowCaptureProperties();
        properties.setTargetRepoPath(repo.toString());
        LocatorIndexService index = new LocatorIndexService(properties);
        index.refresh();

        FlowReplayService replayService = new FlowReplayService(
                device, new UtilityExecutor(device), new MethodCatalogService(), index);

        RecordedStep step = tap("ProfileElement", "com.jar:id/unused");
        step.setReusedLocator(new ExistingLocatorRef(
                "HomeScreenLocators", "locaters.goldLocators", "profileIcon",
                "//android.view.View[@content-desc='ProfileIcon']",
                CandidateLocator.Strategy.EXACT_TEXT_XPATH, false, List.of()));

        replayService.replay(flowWith(step), false);

        List<String> tried = device.lastLocatorSet.stream().map(Object::toString).toList();
        assertEquals(5, tried.size(),
                "all four declared alternatives plus the captured candidate, got: " + tried);
        assertTrue(tried.stream().anyMatch(t -> t.contains("contains(@content-desc,'profile')")),
                "the alternative that actually matches on the live build must be tried");
    }

    /**
     * The failure QA hit, and the reason this check exists.
     *
     * Step 3 of a real flow was captured on a Compose card whose only locator was the
     * container-scoped //*[@resource-id="…nav_host_fragment"]//android.view.View — 104
     * matches. Replay found "an" element, clicked the first of the 104 (an outer layout),
     * threw nothing, and reported PASSED in half a second while the card never opened.
     * Finding something is not the same as finding the right thing.
     */
    @Test
    void aTapThroughAnAmbiguousLocatorFailsInsteadOfClickingAnArbitraryElement() {
        device.matchCounts.put("AppiumBy.id: com.jar:id/one", 104);

        ReplayReport report = service.replay(flowWith(tap("tap the offers card", "com.jar:id/one")), false);

        assertFalse(report.isSuccess());
        StepResult result = report.getResults().get(0);
        assertEquals(StepResult.Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("104 elements"), result.getMessage());
        assertTrue(device.calls.isEmpty(), "nothing should be clicked once the locator is known to be ambiguous");
    }

    /** Typing into an ambiguous locator is the same defect: the text can land in another field. */
    @Test
    void anInputThroughAnAmbiguousLocatorFailsToo() {
        RecordedStep input = new RecordedStep();
        input.setActionType(RecordedStep.ActionType.INPUT);
        input.setActionLabel("amount field");
        input.setInputValue("500");
        input.setCandidateLocators(List.of(new CandidateLocator(
                CandidateLocator.Strategy.RESOURCE_ID, "com.jar:id/field", "android.widget.EditText")));
        device.matchCounts.put("AppiumBy.id: com.jar:id/field", 3);

        ReplayReport report = service.replay(flowWith(input), false);

        assertFalse(report.isSuccess());
        assertTrue(report.getResults().get(0).getMessage().contains("3 elements"));
        assertTrue(device.calls.isEmpty());
    }

    /** A locator that resolves to exactly one element is the normal case and must stay fast and quiet. */
    @Test
    void aUniqueLocatorStillPassesAndIsActuallyClicked() {
        ReplayReport report = service.replay(flowWith(tap("tap one", "com.jar:id/one")), false);

        assertTrue(report.isSuccess());
        assertEquals(List.of("click:AppiumBy.id: com.jar:id/one"), device.calls);
    }

    /** A failure should say what was tried, not name one locator as if it were the only one. */
    @Test
    void aFailureNamesEveryLocatorItTried() {
        device.failClicksContaining = "com.jar";

        ReplayReport report = service.replay(flowWith(tap("tap one", "com.jar:id/one")), false);

        String message = report.getResults().get(0).getMessage();
        assertTrue(message.contains("tried"), message);
        assertTrue(message.contains("com.jar:id/one"), message);
    }

    /**
     * An inserted wait has to actually wait during replay.
     *
     * It was logged and skipped before, on the reasoning that a sleep has no device action —
     * true, and beside the point. A fixed wait is the one step whose entire purpose is
     * elapsed time, and it gets inserted precisely because the app needs those seconds. A
     * replay that skips it runs a faster flow than the test it is supposed to be checking,
     * and reports a pass the real test will not reproduce.
     */
    @Test
    void aFixedWaitActuallyWaitsDuringReplay() {
        RecordedStep wait = new RecordedStep();
        wait.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        wait.setUtilityMethodKind(ReusableMethod.Kind.WAIT_FOR_SECONDS.name());
        com.jar.flowcapture.capture.UtilityArgs args = new com.jar.flowcapture.capture.UtilityArgs();
        args.getScalars().add(new com.jar.flowcapture.capture.UtilityArgs.ScalarArg("seconds", "1"));
        wait.setUtilityArgs(args);

        long before = System.currentTimeMillis();
        ReplayReport report = service.replay(flowWith(wait), false);
        long elapsed = System.currentTimeMillis() - before;

        assertTrue(report.isSuccess(), report.getSummary());
        assertTrue(elapsed >= 900, "replay should have paused for about a second, took " + elapsed + "ms");
    }

    /**
     * An optional screen must not stop the replay.
     *
     * The generated test wraps these steps in try/catch and carries on, so a replay that
     * aborts here is stricter than the code it is checking — and it would stop on exactly
     * the screen someone has already said is allowed to be missing.
     */
    @Test
    void aFailureInsideACarryOnBlockIsReportedAndSteppedOver() {
        device.failClicksContaining = "coupon";
        RecordedFlow flow = flowWith(
                tap("tap one", "com.jar:id/one"),
                tap("dismiss coupon sheet", "com.jar:id/coupon"),
                tap("tap three", "com.jar:id/three"));
        flow.wrapSteps(1, 1, new com.jar.flowcapture.capture.TryBlock());

        ReplayReport report = service.replay(flow, false);

        assertTrue(report.isSuccess(), report.getSummary());
        assertEquals(StepResult.Status.FAILED_TOLERATED, report.getResults().get(1).getStatus());
        assertEquals(1, report.getTolerated());
        assertEquals(2, report.getPassed());
        assertEquals(0, report.getNotReached(), "the steps after an optional one must still run");
        assertTrue(report.getSummary().contains("optional step"), report.getSummary());
    }

    /** A block that says to fail still fails, and still stops the replay. */
    @Test
    void aFailureInsideAFailWithMessageBlockStillAborts() {
        device.failClicksContaining = "pay";
        RecordedFlow flow = flowWith(tap("pay", "com.jar:id/pay"), tap("after", "com.jar:id/after"));
        var block = new com.jar.flowcapture.capture.TryBlock();
        block.setOnFailure(com.jar.flowcapture.capture.TryBlock.OnFailure.FAIL_WITH_MESSAGE);
        flow.wrapSteps(0, 0, block);

        ReplayReport report = service.replay(flow, false);

        assertFalse(report.isSuccess());
        assertEquals(StepResult.Status.FAILED, report.getResults().get(0).getStatus());
        assertEquals(1, report.getNotReached());
    }

    /** A step wrapped in a carry-on block that works is an ordinary pass, not a tolerated one. */
    @Test
    void aWrappedStepThatSucceedsIsJustAPass() {
        RecordedFlow flow = flowWith(tap("dismiss coupon sheet", "com.jar:id/coupon"));
        flow.wrapSteps(0, 0, new com.jar.flowcapture.capture.TryBlock());

        ReplayReport report = service.replay(flow, false);

        assertEquals(StepResult.Status.PASSED, report.getResults().get(0).getStatus());
        assertEquals(0, report.getTolerated());
    }

    /**
     * The backup route, which is how this team already uses try/catch by hand: if the first
     * way through a screen does not work, take the other way. The backup has to run at the
     * moment the try failed — in the state the failure left behind — or the replay is not
     * checking the same program the generated catch block describes.
     */
    @Test
    void whenATryFailsItsBackupStepsRunInItsPlace() {
        device.failClicksContaining = "primary";
        RecordedFlow flow = flowWith(
                tap("primary way", "com.jar:id/primary"),
                tap("other way", "com.jar:id/fallback"),
                tap("carry on", "com.jar:id/next"));
        var block = new com.jar.flowcapture.capture.TryBlock();
        block.setOnFailure(com.jar.flowcapture.capture.TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        ReplayReport report = service.replay(flow, false);

        assertTrue(report.isSuccess(), report.getSummary());
        assertTrue(device.calls.contains("click:AppiumBy.id: com.jar:id/fallback"),
                "the backup step should have run: " + device.calls);
        assertTrue(device.calls.contains("click:AppiumBy.id: com.jar:id/next"),
                "and the flow should carry on afterwards: " + device.calls);
    }

    /** When the try works, the backup must not run — it is a fallback, not an extra step. */
    @Test
    void backupStepsStayPutWhenTheTrySucceeds() {
        RecordedFlow flow = flowWith(
                tap("primary way", "com.jar:id/primary"),
                tap("other way", "com.jar:id/fallback"));
        var block = new com.jar.flowcapture.capture.TryBlock();
        block.setOnFailure(com.jar.flowcapture.capture.TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        ReplayReport report = service.replay(flow, false);

        assertTrue(report.isSuccess(), report.getSummary());
        assertFalse(device.calls.contains("click:AppiumBy.id: com.jar:id/fallback"),
                "the backup must not run when the try worked: " + device.calls);
        assertEquals(StepResult.Status.SKIPPED, report.getResults().get(1).getStatus());
    }

    /** The catch was the second attempt. If the backup fails too, there is no third. */
    @Test
    void aBackupStepThatAlsoFailsStopsTheReplay() {
        device.failClicksContaining = "com.jar";
        RecordedFlow flow = flowWith(
                tap("primary way", "com.jar:id/primary"),
                tap("other way", "com.jar:id/fallback"),
                tap("carry on", "com.jar:id/next"));
        var block = new com.jar.flowcapture.capture.TryBlock();
        block.setOnFailure(com.jar.flowcapture.capture.TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        ReplayReport report = service.replay(flow, false);

        assertFalse(report.isSuccess());
        assertFalse(device.calls.contains("click:AppiumBy.id: com.jar:id/next"),
                "nothing after the failed backup should run: " + device.calls);
    }
}
