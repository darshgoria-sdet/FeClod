package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.catalog.ReusableMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestClassGeneratorTest {

    private final TestClassGenerator generator = new TestClassGenerator(new AssertionPlanner());

    private RecordedStep screen(String name) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.SCREEN_BOUNDARY);
        step.setScreenName(name);
        return step;
    }

    private RecordedStep returnToHome() {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        step.setUtilityMethodKind(ReusableMethod.Kind.RETURN_TO_HOME.name());
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(new UtilityArgs.ElementTarget("element", "profileIcon",
                List.of(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH,
                        "//android.view.View[@content-desc='ProfileIcon']", null)),
                new ExistingLocatorRef("HomeScreenLocators", "locaters.goldLocators", "profileIcon",
                        "//android.view.View[@content-desc='ProfileIcon']",
                        CandidateLocator.Strategy.EXACT_TEXT_XPATH, false, List.of())));
        step.setUtilityArgs(args);
        return step;
    }

    private RecordedStep tap() {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel("tap pay now");
        step.setElementFieldName("payNow");
        return step;
    }

    private String generate(RecordedFlow flow) {
        return generator.generate(flow, "InstantSaveAbcPage", "pageObjectModel.gold",
                "com.jar.testCases.goldTestCase").fileContent();
    }

    private RecordedFlow flow(boolean assertions, RecordedStep... steps) {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Instant Save Abc");
        flow.setGenerateAssertions(assertions);
        for (RecordedStep step : steps) flow.addStep(step);
        return flow;
    }

    @Test
    void generatesATestNgTestInvokingTheFlowMethod() {
        String code = generate(flow(false, screen("InstantSaveSuccessScreen")));

        assertTrue(code.contains("package com.jar.testCases.goldTestCase;"));
        assertTrue(code.contains("import pageObjectModel.gold.InstantSaveAbcPage;"));
        assertTrue(code.contains("instantSaveAbcPage.instantSaveAbcFlow();"));
    }

    /**
     * Every hand-written test in the repo extends BaseSetup, which carries the TestNG
     * lifecycle that creates the driver. Without it a generated test compiles and then fails
     * at runtime with no session — which is exactly how FestiveSavingsTest ended up differing
     * from every hand-written test in the suite.
     */
    @Test
    void theGeneratedTestExtendsBaseSetup() {
        String code = generate(flow(true, returnToHome()));

        assertTrue(code.contains("public class InstantSaveAbcTest extends BaseSetup {"), code);
        assertTrue(code.contains("import basePackage.BaseSetup;"));
    }

    @Test
    void withAssertionsOffTheTodoStubComesBackAndNothingIsAsserted() {
        String code = generate(flow(false, returnToHome()));

        assertTrue(code.contains("TODO"), code);
        // "Assert." also appears inside the TODO's example comment, so check for a real
        // statement rather than the substring.
        assertTrue(code.lines().noneMatch(l -> l.trim().startsWith("Assert.")),
                "assertions were switched off: " + code);
        assertFalse(code.contains("import org.testng.Assert;"));
    }

    /**
     * A flow ending on return-to-home has already proved the home screen is reachable, during
     * recording and again during replay — so asserting it restates something verified rather
     * than guessing at intent.
     */
    @Test
    void aFlowEndingOnReturnToHomeAssertsTheHomeScreen() {
        String code = generate(flow(true, tap(), returnToHome()));

        assertTrue(code.contains("Assert.assertTrue(WaitUtils.isVisible(10, homeScreenLocators.getProfileIcon())"), code);
        assertTrue(code.contains("Expected to be back on the home screen"));
        assertTrue(code.contains("import locaters.goldLocators.HomeScreenLocators;"));
        assertTrue(code.contains("HomeScreenLocators homeScreenLocators = new HomeScreenLocators(null);"));
    }

    /** Matches TrustSafetyCentreTest, which asserts the session survived the flow. */
    @Test
    void assertionsAlwaysIncludeTheSessionSurvivingTheFlow() {
        String code = generate(flow(true, returnToHome()));

        assertTrue(code.contains("Assert.assertNotNull(DriverFactory.driver,"), code);
        assertTrue(code.contains("import basePackage.DriverFactory;"));
    }

    /**
     * The important restraint: a flow ending on a tap has established nothing about what the
     * tap should produce, so the tool says so instead of inventing an assertion that would
     * pass for the wrong reason.
     */
    @Test
    void aFlowEndingOnATapGetsNoFabricatedEndStateAssertion() {
        String code = generate(flow(true, screen("PaymentScreen"), tap()));

        assertTrue(code.contains("Assert.assertNotNull(DriverFactory.driver,"));
        assertFalse(code.contains("WaitUtils.isVisible"), "nothing proved an element is visible: " + code);
        assertTrue(code.contains("TODO: assert the expected end state"), code);
        assertTrue(code.contains("PaymentScreen"), "the last named screen should still be hinted");
    }

    /** A screen marker is a comment in the generated code; it cannot be what a flow "ends on". */
    @Test
    void aTrailingScreenMarkerDoesNotHideTheRealLastStep() {
        String code = generate(flow(true, returnToHome(), screen("HomeScreen")));

        assertTrue(code.contains("WaitUtils.isVisible(10, homeScreenLocators.getProfileIcon())"), code);
    }

    @Test
    void theTestCarriesADescriptionNamingTheFlow() {
        String code = generate(flow(true, returnToHome(), screen("HomeScreen")));

        assertTrue(code.contains("@Test(description = \"InstantSaveAbc flow — ends on HomeScreen\")"), code);
    }
}
