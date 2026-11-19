package com.jar.flowcapture.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordedFlowTest {

    @Test
    void removeLastStepRemovesOnlyTheMostRecentStep() {
        RecordedFlow flow = new RecordedFlow();
        RecordedStep first = new RecordedStep();
        first.setActionLabel("first");
        RecordedStep second = new RecordedStep();
        second.setActionLabel("second");
        flow.addStep(first);
        flow.addStep(second);

        RecordedStep removed = flow.removeLastStep();

        assertEquals("second", removed.getActionLabel());
        assertEquals(1, flow.getSteps().size());
        assertEquals("first", flow.getSteps().get(0).getActionLabel());
    }

    @Test
    void removeLastStepOnEmptyFlowReturnsNullInsteadOfThrowing() {
        RecordedFlow flow = new RecordedFlow();
        assertNull(flow.removeLastStep());
    }
}
