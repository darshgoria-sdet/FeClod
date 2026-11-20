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

        @Override
        public synchronized By clickFirstVisible(java.util.List<By> locators, int timeoutSeconds) {
            lastLocatorSet = locators;
            calls.add("click:" + locators.get(0));
            return locators.stream()
                    .filter(by -> failClicksContaining == null || !by.toString().contains(failClicksContaining))
                    .findFirst().orElse(null);
        }

        @Override
        public synchronized By sendKeysToFirstVisible(java.util.List<By> locators, String text, int timeoutSeconds) {
            calls.add("type:" + text);
            return locators.get(0);
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

    /** A failure should say what was tried, not name one locator as if it were the only one. */
    @Test
    void aFailureNamesEveryLocatorItTried() {
        device.failClicksContaining = "com.jar";

        ReplayReport report = service.replay(flowWith(tap("tap one", "com.jar:id/one")), false);

        String message = report.getResults().get(0).getMessage();
        assertTrue(message.contains("tried"), message);
        assertTrue(message.contains("com.jar:id/one"), message);
    }
}
