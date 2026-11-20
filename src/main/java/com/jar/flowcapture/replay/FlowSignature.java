package com.jar.flowcapture.replay;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * A fingerprint of everything about a flow that affects what it does on the device.
 *
 * It exists so a replay that already passed can be trusted instead of re-run. Replaying a
 * long flow takes minutes, and making Finish always pay that cost would push QA to skip the
 * gate — which defeats it. The signature changes the moment any step is added, removed,
 * re-labelled, re-targeted or reordered, so a cached pass can only ever apply to the exact
 * sequence that actually ran.
 *
 * Deliberately excluded: screenshots and verification flags, which record what happened
 * rather than what will happen, and would invalidate the cache for no reason.
 */
public final class FlowSignature {

    private FlowSignature() {}

    public static String of(RecordedFlow flow) {
        if (flow == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add(flow.getId());
        for (RecordedStep step : flow.getSteps()) {
            parts.add(describe(step));
        }
        return String.join("|", parts);
    }

    private static String describe(RecordedStep step) {
        StringBuilder sb = new StringBuilder();
        sb.append(step.getActionType()).append('~')
                .append(nullSafe(step.getActionLabel())).append('~')
                .append(nullSafe(step.getInputValue())).append('~')
                .append(nullSafe(step.getScreenName())).append('~')
                .append(nullSafe(step.getUtilityMethodKind())).append('~');

        // The locator that will actually be used, reused-repo reference included: changing
        // which locator a step resolves through changes whether it works.
        if (step.getReusedLocator() != null) {
            sb.append(step.getReusedLocator().getLocatorsClassName()).append('.')
                    .append(step.getReusedLocator().getFieldName());
        }
        sb.append('~').append(locatorValues(step.getCandidateLocators()));

        UtilityArgs args = step.getUtilityArgs();
        if (args != null) {
            for (UtilityArgs.ElementTarget target : args.getElements()) {
                sb.append('~').append(nullSafe(target.getParamName())).append('=')
                        .append(locatorValues(target.getCandidateLocators()));
                if (target.getReusedFrom() != null) {
                    sb.append('@').append(target.getReusedFrom().getLocatorsClassName())
                            .append('.').append(target.getReusedFrom().getFieldName());
                }
            }
            for (UtilityArgs.ScalarArg scalar : args.getScalars()) {
                sb.append('~').append(nullSafe(scalar.getParamName()))
                        .append('=').append(nullSafe(scalar.getValue()));
            }
        }
        return sb.toString();
    }

    private static String locatorValues(List<CandidateLocator> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (CandidateLocator candidate : candidates) {
            values.add(candidate.getStrategy() + ":" + candidate.getValue());
        }
        return String.join(",", values);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
