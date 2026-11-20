package com.jar.flowcapture.replay;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Outcome of replaying one recorded step against the live device. */
@Data
@AllArgsConstructor
public class StepResult {

    public enum Status {
        /** The step ran and did what it did when recorded. */
        PASSED,
        /** The step ran but did not succeed — this is the one that matters. */
        FAILED,
        /**
         * Failed inside a try/catch block that says to carry on.
         *
         * Not a pass: the step genuinely did not work, and hiding that would make an optional
         * screen indistinguishable from a broken one. Not a failure either, because the
         * generated test will not fail here — so the replay must not stop, or it would be
         * stricter than the code it is checking.
         */
        FAILED_TOLERATED,
        /** Nothing to do on the device: a screen marker, a pure timing step, or a manual action. */
        SKIPPED,
        /** Never attempted, because an earlier step failed. */
        NOT_REACHED
    }

    /** 1-based, matching the numbering QA sees in the step log. */
    private int stepNumber;
    private String actionType;
    private String label;
    private Status status;

    /** Why it was skipped, or how it failed. Null when it simply passed. */
    private String message;

    private long elapsedMs;

    /** Base64 PNG of the screen right after this step — null when capture failed or the step was skipped. */
    private String screenshotBase64;
}
