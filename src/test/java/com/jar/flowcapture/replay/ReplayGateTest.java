package com.jar.flowcapture.replay;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayGateTest {

    private final ReplayGate gate = new ReplayGate();

    private RecordedStep tap(String label, String locator) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(label);
        step.setCandidateLocators(new ArrayList<>(List.of(
                new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, locator, null))));
        return step;
    }

    private RecordedFlow flow(RecordedStep... steps) {
        RecordedFlow f = new RecordedFlow();
        f.setFlowName("SomeFlow");
        for (RecordedStep s : steps) f.addStep(s);
        return f;
    }

    private ReplayReport passed() {
        return new ReplayReport("SomeFlow", List.of(
                new StepResult(1, "TAP", "one", StepResult.Status.PASSED, null, 10, null)),
                1, 0, 0, 0, 0, 10);
    }

    private ReplayReport failed() {
        return new ReplayReport("SomeFlow", List.of(
                new StepResult(1, "TAP", "one", StepResult.Status.FAILED, "nope", 10, null)),
                0, 1, 0, 0, 0, 10);
    }

    /** Having just watched a clean replay, QA should not be made to sit through another one. */
    @Test
    void aPassingReplaySatisfiesTheGateForThatExactFlow() {
        RecordedFlow f = flow(tap("one", "com.jar:id/one"));
        gate.record(f, passed());

        assertTrue(gate.hasPassed(f));
    }

    @Test
    void aFailingReplayNeverSatisfiesTheGate() {
        RecordedFlow f = flow(tap("one", "com.jar:id/one"));
        gate.record(f, failed());

        assertFalse(gate.hasPassed(f));
    }

    /** The whole risk of caching a pass: it must not survive the flow changing underneath it. */
    @Test
    void addingAStepInvalidatesTheGate() {
        RecordedFlow f = flow(tap("one", "com.jar:id/one"));
        gate.record(f, passed());

        f.addStep(tap("two", "com.jar:id/two"));

        assertFalse(gate.hasPassed(f), "a step added after the replay has never been run");
    }

    @Test
    void removingAStepInvalidatesTheGate() {
        RecordedFlow f = flow(tap("one", "com.jar:id/one"), tap("two", "com.jar:id/two"));
        gate.record(f, passed());

        f.removeStep(0);

        assertFalse(gate.hasPassed(f));
    }

    /**
     * A step can change without the count changing — the executor promotes whichever locator
     * matched, and QA can re-target a utility argument. Either means something different will
     * run, so the previous pass no longer applies.
     */
    @Test
    void changingAStepsLocatorInvalidatesTheGate() {
        RecordedStep step = tap("one", "com.jar:id/one");
        RecordedFlow f = flow(step);
        gate.record(f, passed());

        step.setCandidateLocators(List.of(
                new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, "com.jar:id/different", null)));

        assertFalse(gate.hasPassed(f));
    }

    @Test
    void changingAUtilityArgumentInvalidatesTheGate() {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        step.setUtilityMethodKind("WAIT_FOR_SECONDS");
        UtilityArgs args = new UtilityArgs();
        args.getScalars().add(new UtilityArgs.ScalarArg("seconds", "2"));
        step.setUtilityArgs(args);

        RecordedFlow f = flow(step);
        gate.record(f, passed());

        args.getScalars().set(0, new UtilityArgs.ScalarArg("seconds", "30"));

        assertFalse(gate.hasPassed(f));
    }

    /** A verdict must never carry over to a different recording. */
    @Test
    void anotherFlowNeverInheritsThisOnesVerdict() {
        RecordedFlow first = flow(tap("one", "com.jar:id/one"));
        gate.record(first, passed());

        RecordedFlow second = flow(tap("one", "com.jar:id/one"));

        assertFalse(gate.hasPassed(second), "identical steps, different recording");
    }

    @Test
    void clearingDropsTheVerdict() {
        RecordedFlow f = flow(tap("one", "com.jar:id/one"));
        gate.record(f, passed());

        gate.clear();

        assertFalse(gate.hasPassed(f));
    }

    /** Screenshots record what happened, not what will happen — they must not bust the cache. */
    @Test
    void aScreenshotArrivingLaterDoesNotInvalidateTheGate() {
        RecordedStep step = tap("one", "com.jar:id/one");
        RecordedFlow f = flow(step);
        gate.record(f, passed());

        step.setScreenshotThumbnailBase64("PNGDATA");
        step.setVerifiedBeforeWrite(true);

        assertTrue(gate.hasPassed(f));
    }
}
