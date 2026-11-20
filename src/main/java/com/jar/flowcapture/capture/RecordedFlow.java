package com.jar.flowcapture.capture;

import com.jar.flowcapture.codegen.TargetArea;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A full exploration session: one new flow, a sequence of steps, ready to hand to codegen. */
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

    public void addStep(RecordedStep step) {
        steps.add(step);
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
