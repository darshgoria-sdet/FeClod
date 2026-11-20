package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.catalog.ReusableMethod;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Decides what a generated test is entitled to assert.
 *
 * The rule is deliberately narrow: only assert what the recording actually established. A
 * tool cannot know the intent of a test, and an assertion that merely looks plausible is
 * worse than none — it passes for the wrong reason and gets trusted. So this asserts two
 * kinds of thing and nothing else.
 *
 * First, that the session survived, which is what the repo's own tests already do
 * (TrustSafetyCentreTest: assertNotNull(DriverFactory.driver, ...)).
 *
 * Second, the end state — but only when the final step is one whose whole meaning is "this
 * element is now on screen": return-to-home, back/scroll-until-visible, wait-for-visible.
 * Those steps already proved the element was there during recording and during replay, so
 * asserting it is restating something verified, not guessing. A flow ending on a tap gets no
 * end-state assertion, because what a tap should have produced is exactly the thing only a
 * human knows.
 */
@Service
public class AssertionPlanner {

    /** One assertion to emit: the element to check and what to say when it is not there. */
    public record EndStateAssertion(ExistingLocatorRef reusedLocator, String ownFieldName, String description) {}

    /** Kinds whose contract is "after this step, the element is visible". */
    private static final List<ReusableMethod.Kind> PROVES_VISIBLE = List.of(
            ReusableMethod.Kind.RETURN_TO_HOME,
            ReusableMethod.Kind.NAVIGATE_BACK_UNTIL_VISIBLE,
            ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE,
            ReusableMethod.Kind.SCROLL_UP_UNTIL_VISIBLE,
            ReusableMethod.Kind.WAIT_FOR_VISIBILITY,
            ReusableMethod.Kind.WAIT_FOR_PRESENCE);

    public Optional<EndStateAssertion> endStateFor(RecordedFlow flow) {
        if (flow == null || !flow.isGenerateAssertions()) return Optional.empty();

        RecordedStep last = lastMeaningfulStep(flow);
        if (last == null || last.getActionType() != RecordedStep.ActionType.UTILITY_CALL) return Optional.empty();

        ReusableMethod.Kind kind = kindOf(last);
        if (kind == null || !PROVES_VISIBLE.contains(kind)) return Optional.empty();

        UtilityArgs args = last.getUtilityArgs();
        if (args == null) return Optional.empty();
        List<UtilityArgs.ElementTarget> targets = args.elementsFor("element");
        if (targets.isEmpty()) return Optional.empty();

        UtilityArgs.ElementTarget target = targets.get(0);
        String description = kind == ReusableMethod.Kind.RETURN_TO_HOME
                ? "Expected to be back on the home screen at the end of the flow"
                : "Expected element to be visible at the end of the flow";
        return Optional.of(new EndStateAssertion(target.getReusedFrom(), target.getFieldName(), description));
    }

    /** Screen boundaries are comments in the generated code; they cannot end a flow in any real sense. */
    private RecordedStep lastMeaningfulStep(RecordedFlow flow) {
        List<RecordedStep> steps = flow.getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).getActionType() != RecordedStep.ActionType.SCREEN_BOUNDARY) return steps.get(i);
        }
        return null;
    }

    private ReusableMethod.Kind kindOf(RecordedStep step) {
        try {
            return ReusableMethod.Kind.valueOf(step.getUtilityMethodKind());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
