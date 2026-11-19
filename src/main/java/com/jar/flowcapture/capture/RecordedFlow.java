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

    public void addStep(RecordedStep step) {
        steps.add(step);
    }

    /** @return the removed step, or null if there was nothing to remove (QA double-clicked undo, etc. — not an error). */
    public RecordedStep removeLastStep() {
        if (steps.isEmpty()) return null;
        return steps.remove(steps.size() - 1);
    }
}
