package com.jar.flowcapture.replay;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** The result of replaying a whole recorded flow. */
@Data
@AllArgsConstructor
public class ReplayReport {

    private String flowName;
    private List<StepResult> results;
    private int passed;
    private int failed;
    private int skipped;
    private int notReached;
    private long totalMs;

    /** True only when nothing failed — the signal for "this flow is safe to generate". */
    public boolean isSuccess() {
        return failed == 0;
    }

    /** One-line summary, so the dashboard and the logs say the same thing. */
    public String getSummary() {
        if (failed > 0) {
            return "Failed at step " + firstFailure() + " — " + passed + " passed, " + failed + " failed, "
                    + notReached + " not reached";
        }
        if (passed == 0) {
            return "Nothing ran — all " + skipped + " steps were skipped";
        }
        return (passed == 1 ? "The 1 executable step passed" : "All " + passed + " executable steps passed")
                + (skipped > 0 ? " (" + skipped + " skipped)" : "")
                + " in " + (totalMs / 1000) + "s";
    }

    private int firstFailure() {
        return results.stream()
                .filter(r -> r.getStatus() == StepResult.Status.FAILED)
                .mapToInt(StepResult::getStepNumber)
                .findFirst().orElse(-1);
    }
}
