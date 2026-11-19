package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestClassGeneratorTest {

    private final TestClassGenerator generator = new TestClassGenerator();

    @Test
    void generatesATestNgTestInvokingTheFlowMethodWithATodoAssertion() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Instant Save Abc");

        RecordedStep boundary = new RecordedStep();
        boundary.setActionType(RecordedStep.ActionType.SCREEN_BOUNDARY);
        boundary.setScreenName("InstantSaveSuccessScreen");
        flow.addStep(boundary);

        var result = generator.generate(flow, "InstantSaveAbcPage", "pageObjectModel.gold", "com.jar.testCases.goldTestCase");

        assertEquals("InstantSaveAbcTest", result.className());
        assertTrue(result.fileContent().contains("package com.jar.testCases.goldTestCase;"));
        assertTrue(result.fileContent().contains("import pageObjectModel.gold.InstantSaveAbcPage;"));
        assertTrue(result.fileContent().contains("@Test"));
        assertTrue(result.fileContent().contains("instantSaveAbcPage.instantSaveAbcFlow();"));
        assertTrue(result.fileContent().contains("InstantSaveSuccessScreen"),
                "the last named screen should be hinted in the assertion TODO");
        assertTrue(result.fileContent().contains("TODO"));
    }

    @Test
    void neverFabricatesAnAssertionEvenWhenNoScreenWasNamed() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("No Screen Named Flow");

        var result = generator.generate(flow, "NoScreenNamedFlowPage", "pageObjectModel.gold", "com.jar.testCases.goldTestCase");

        assertTrue(result.fileContent().contains("TODO"));
        assertFalse(result.fileContent().contains("assertEquals") && !result.fileContent().contains("// e.g."),
                "must never emit a real, non-commented assertion call — only a TODO stub");
    }
}
