package com.jar.flowcapture.replay;

import com.jar.flowcapture.capture.RecordedFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Remembers the last replay that passed, so Finish can require a passing replay without
 * re-running one that already ran.
 *
 * The point of the gate is to change what generating a test means. Before it, Finish promised
 * "this compiles and matches repo style" — the generated test could still fail the first time
 * anyone ran it, which is exactly the flaky-test problem this tool exists to reduce. With it,
 * nothing reaches the repo until the whole sequence has run on a real device.
 *
 * A gate QA routinely bypasses is worse than no gate, so the cost has to stay low: replaying a
 * long flow takes minutes, and paying that on every Finish — including the one straight after
 * a manual replay — would make skipping it the obvious habit. Hence the record of what passed.
 */
@Slf4j
@Service
public class ReplayGate {

    private volatile String passingSignature;

    /** Called after any replay; only a clean one is worth remembering. */
    public void record(RecordedFlow flow, ReplayReport report) {
        if (report.isSuccess()) {
            passingSignature = FlowSignature.of(flow);
            log.info("Replay gate satisfied for '{}'", flow.getFlowName());
        } else {
            // Clear rather than leave a stale pass: the flow has demonstrably changed behaviour
            // since whatever last passed, even if its steps happen to be identical.
            passingSignature = null;
        }
    }

    /** True when this exact sequence of steps has already replayed cleanly. */
    public boolean hasPassed(RecordedFlow flow) {
        return passingSignature != null && passingSignature.equals(FlowSignature.of(flow));
    }

    /** Called when a flow ends, so a later flow can never inherit this one's verdict. */
    public void clear() {
        passingSignature = null;
    }
}
