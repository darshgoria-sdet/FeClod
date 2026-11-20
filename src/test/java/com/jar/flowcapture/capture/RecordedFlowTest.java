package com.jar.flowcapture.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    private RecordedStep labelled(String label) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(label);
        return step;
    }

    /** Undo only reaches the end; a step that turned out to be unnecessary can be anywhere. */
    @Test
    void removesAStepFromTheMiddleAndClosesTheGap() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(labelled("one"));
        flow.addStep(labelled("two"));
        flow.addStep(labelled("three"));

        RecordedStep removed = flow.removeStep(1);

        assertEquals("two", removed.getActionLabel());
        assertEquals(List.of("one", "three"),
                flow.getSteps().stream().map(RecordedStep::getActionLabel).toList());
    }

    /** A stale index must say so rather than silently removing the wrong step. */
    @Test
    void refusesAnIndexOutsideTheFlow() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(labelled("only"));

        var error = assertThrows(IllegalArgumentException.class, () -> flow.removeStep(1));
        assertTrue(error.getMessage().contains("1 step(s)"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> flow.removeStep(-1));
        assertEquals(1, flow.getSteps().size(), "a rejected removal must leave the flow untouched");
    }
}
