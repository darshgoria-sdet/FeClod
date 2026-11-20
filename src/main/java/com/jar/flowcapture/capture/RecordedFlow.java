package com.jar.flowcapture.capture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jar.flowcapture.codegen.TargetArea;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A full exploration session: one new flow, a sequence of steps, ready to hand to codegen. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class RecordedFlow {

    private String id = UUID.randomUUID().toString();
    private String flowName;
    private TargetArea targetArea = TargetArea.GOLD;
    private Instant startedAt = Instant.now();
    private List<RecordedStep> steps = new ArrayList<>();

    /**
     * Whether the generated test carries assertions. On by default — a test with no assertion
     * only proves the app did not crash — but QA can turn it off, because an assertion the
     * tool inferred is a claim about intent, and a wrong one is worse than none.
     */
    private boolean generateAssertions = true;

    /** The try/catch blocks this flow's steps belong to, keyed from RecordedStep.tryGroup. */
    private List<TryBlock> tryBlocks = new ArrayList<>();

    public Optional<TryBlock> tryBlock(String id) {
        if (id == null) return Optional.empty();
        return tryBlocks.stream().filter(b -> b.getId().equals(id)).findFirst();
    }

    /**
     * Wraps a run of steps in a try/catch.
     *
     * A step can only be in one block, so re-wrapping a step moves it: the alternative,
     * nesting, buys very little here and makes both the generated code and the step list
     * much harder to read.
     *
     * @return the block that now owns those steps
     */
    public TryBlock wrapSteps(int fromIndex, int toIndex, TryBlock block) {
        if (fromIndex < 0 || toIndex >= steps.size() || fromIndex > toIndex) {
            throw new IllegalArgumentException("Steps " + (fromIndex + 1) + " to " + (toIndex + 1)
                    + " are not a valid range — this flow has " + steps.size() + " step(s)");
        }
        tryBlocks.add(block);
        for (int i = fromIndex; i <= toIndex; i++) {
            steps.get(i).setTryGroup(block.getId());
        }
        return block;
    }

    /** The steps that run only if this block's try failed, in flow order. */
    public List<RecordedStep> recoveryStepsFor(String blockId) {
        if (blockId == null) return List.of();
        return steps.stream().filter(s -> blockId.equals(s.getCatchGroup())).toList();
    }

    /**
     * Assigns a run of steps to a block's catch, so they become its backup route.
     *
     * They are taken out of any try they were in: a step cannot be both the thing that might
     * fail and the thing that runs when it does.
     */
    public void assignToCatch(int fromIndex, int toIndex, String blockId) {
        if (tryBlock(blockId).isEmpty()) {
            throw new IllegalArgumentException("No try/catch block with id " + blockId);
        }
        if (fromIndex < 0 || toIndex >= steps.size() || fromIndex > toIndex) {
            throw new IllegalArgumentException("Steps " + (fromIndex + 1) + " to " + (toIndex + 1)
                    + " are not a valid range — this flow has " + steps.size() + " step(s)");
        }
        for (int i = fromIndex; i <= toIndex; i++) {
            RecordedStep step = steps.get(i);
            step.setTryGroup(null);
            step.setCatchGroup(blockId);
        }
    }

    /**
     * Moves a step to another position, the way dragging it in the list does.
     *
     * Reordering is an edit, not a re-recording: nothing runs on the device, and whether the
     * flow still works in the new order is a question for Replay.
     */
    public void moveStep(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= steps.size() || toIndex < 0 || toIndex >= steps.size()) {
            throw new IllegalArgumentException("Cannot move step " + (fromIndex + 1) + " to position "
                    + (toIndex + 1) + " — this flow has " + steps.size() + " step(s)");
        }
        if (fromIndex == toIndex) return;
        steps.add(toIndex, steps.remove(fromIndex));
    }

    /** Removes the try/catch, leaving its steps exactly where they are and no longer wrapped. */
    public void unwrap(String blockId) {
        if (blockId == null) return;
        steps.stream()
                .filter(s -> blockId.equals(s.getTryGroup()))
                .forEach(s -> s.setTryGroup(null));
        steps.stream()
                .filter(s -> blockId.equals(s.getCatchGroup()))
                .forEach(s -> s.setCatchGroup(null));
        tryBlocks.removeIf(b -> b.getId().equals(blockId));
    }

    /** Drops blocks nothing points at any more — a step removal can empty one. */
    public void pruneEmptyTryBlocks() {
        tryBlocks.removeIf(block -> steps.stream().noneMatch(
                s -> block.getId().equals(s.getTryGroup()) || block.getId().equals(s.getCatchGroup())));
    }

    public void addStep(RecordedStep step) {
        steps.add(step);
    }

    /**
     * Puts a step at a chosen position instead of the end.
     *
     * Recording can only ever append, because it follows what someone is doing on a phone in
     * real time. Editing a recorded flow is a different job: the commonest repair is "this
     * screen needs a moment before the next tap", and that belongs between two steps that
     * already exist. An index equal to the current size appends, so the caller does not need
     * a special case for the end of the list.
     *
     * @throws IllegalArgumentException when the position is outside the flow
     */
    public void insertStep(int index, RecordedStep step) {
        if (index < 0 || index > steps.size()) {
            throw new IllegalArgumentException(
                    "Cannot insert at position " + (index + 1) + " — this flow has " + steps.size() + " step(s)");
        }
        steps.add(index, step);
    }

    /** @return the removed step, or null if there was nothing to remove (QA double-clicked undo, etc. — not an error). */
    public RecordedStep removeLastStep() {
        if (steps.isEmpty()) return null;
        return steps.remove(steps.size() - 1);
    }

    /**
     * Removes one step from anywhere in the flow. Unlike undo this is not a mis-tap recovery:
     * it is for a step that turned out to be unnecessary several screens later — a stray tap, a
     * wait that is no longer needed, a scroll that the app no longer requires.
     *
     * Nothing is re-run on the device. The remaining steps are exactly the steps that were
     * recorded, so whether they still work as a sequence is a question for Replay, which is
     * the tool that can actually answer it.
     *
     * @return the removed step
     * @throws IllegalArgumentException when the index is out of range
     */
    public RecordedStep removeStep(int index) {
        if (index < 0 || index >= steps.size()) {
            throw new IllegalArgumentException(
                    "No step at position " + (index + 1) + " — this flow has " + steps.size() + " step(s)");
        }
        return steps.remove(index);
    }
}
