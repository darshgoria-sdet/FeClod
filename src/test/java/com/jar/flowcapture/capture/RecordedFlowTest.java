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

    /**
     * The repair that motivated insertion: a screen needs a moment before the next tap, and
     * that moment belongs between two steps that already exist, not at the end of the flow.
     */
    @Test
    void aStepCanBeInsertedBetweenTwoExistingOnes() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("first"));
        flow.addStep(named("second"));

        flow.insertStep(1, named("wait"));

        assertEquals(List.of("first", "wait", "second"),
                flow.getSteps().stream().map(RecordedStep::getActionLabel).toList());
    }

    @Test
    void insertingAtTheStepCountAppends() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("only"));

        flow.insertStep(1, named("after"));

        assertEquals(List.of("only", "after"),
                flow.getSteps().stream().map(RecordedStep::getActionLabel).toList());
    }

    @Test
    void insertingBeforeTheFirstStepIsAllowed() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("only"));

        flow.insertStep(0, named("before"));

        assertEquals("before", flow.getSteps().get(0).getActionLabel());
    }

    @Test
    void insertingOutsideTheFlowSaysSoRatherThanThrowingIndexOutOfBounds() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("only"));

        var error = assertThrows(IllegalArgumentException.class, () -> flow.insertStep(5, named("nope")));

        assertTrue(error.getMessage().contains("1 step"), error.getMessage());
    }

    private RecordedStep named(String label) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(label);
        return step;
    }

    @Test
    void aStepCanBeDraggedToAnEarlierPosition() {
        RecordedFlow flow = new RecordedFlow();
        for (String name : List.of("a", "b", "c", "d", "e")) flow.addStep(named(name));

        flow.moveStep(4, 1);

        assertEquals(List.of("a", "e", "b", "c", "d"), labels(flow));
    }

    @Test
    void aStepCanBeDraggedLater() {
        RecordedFlow flow = new RecordedFlow();
        for (String name : List.of("a", "b", "c")) flow.addStep(named(name));

        flow.moveStep(0, 2);

        assertEquals(List.of("b", "c", "a"), labels(flow));
    }

    @Test
    void movingAStepOutsideTheFlowSaysSo() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("only"));

        assertThrows(IllegalArgumentException.class, () -> flow.moveStep(0, 4));
    }

    /** A backup step belongs to the catch, so it must not also be one of the steps being tried. */
    @Test
    void assigningAStepToACatchTakesItOutOfTheTry() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("try this"));
        flow.addStep(named("otherwise this"));
        TryBlock block = new TryBlock();
        block.setOnFailure(TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        flow.wrapSteps(0, 1, block);

        flow.assignToCatch(1, 1, block.getId());

        assertEquals(block.getId(), flow.getSteps().get(0).getTryGroup());
        assertNull(flow.getSteps().get(1).getTryGroup());
        assertEquals(block.getId(), flow.getSteps().get(1).getCatchGroup());
        assertEquals(List.of("otherwise this"),
                flow.recoveryStepsFor(block.getId()).stream().map(RecordedStep::getActionLabel).toList());
    }

    @Test
    void removingATryBlockReleasesItsBackupStepsToo() {
        RecordedFlow flow = new RecordedFlow();
        flow.addStep(named("try this"));
        flow.addStep(named("otherwise this"));
        TryBlock block = new TryBlock();
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        flow.unwrap(block.getId());

        assertNull(flow.getSteps().get(1).getCatchGroup());
        assertTrue(flow.getTryBlocks().isEmpty());
    }

    private List<String> labels(RecordedFlow flow) {
        return flow.getSteps().stream().map(RecordedStep::getActionLabel).toList();
    }
}
