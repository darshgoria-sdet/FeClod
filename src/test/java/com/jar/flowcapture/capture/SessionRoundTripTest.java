package com.jar.flowcapture.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A saved session has to be readable by the same app that wrote it.
 *
 * It was not: Lombok's derived accessors (isBrittle, isUnique, isAmbiguous on
 * CandidateLocator, and the generated getter-name helper on ExistingLocatorRef) are written
 * out by Jackson but have no matching setters, so loading failed with "Unrecognized field"
 * and every restart of the studio silently dropped the in-progress recording — the save was
 * written, logged as fine, and never came back.
 */
class SessionRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void aSavedFlowCanBeReadBackByTheAppThatWroteIt() throws Exception {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");

        RecordedStep tap = new RecordedStep();
        tap.setActionType(RecordedStep.ActionType.TAP);
        tap.setActionLabel("offers card");
        tap.setCandidateLocators(List.of(
                new CandidateLocator(CandidateLocator.Strategy.CLASS_NAME_XPATH,
                        "//*[@resource-id=\"nav_host_fragment\"]//android.view.View",
                        "android.view.View", 104),
                new CandidateLocator(CandidateLocator.Strategy.INDEX_BASED,
                        "new UiSelector().instance(N)", "android.view.View")));
        tap.setReusedLocator(new ExistingLocatorRef(
                "HomeScreenLocators", "locaters.goldLocators", "profileIcon",
                "//android.view.View[@content-desc='profile Icon']",
                CandidateLocator.Strategy.EXACT_TEXT_XPATH, true, List.of("WeeklySavingsLocators")));
        flow.addStep(tap);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(flow);
        RecordedFlow restored = mapper.readValue(json, RecordedFlow.class);

        assertEquals("Test4", restored.getFlowName());
        assertEquals(1, restored.getSteps().size());
        CandidateLocator best = restored.getSteps().get(0).getCandidateLocators().get(0);
        assertEquals(104, best.getMatchCount(), "the match count is what flags the step as risky");
        assertTrue(best.isAmbiguous());
        assertEquals("profileIcon", restored.getSteps().get(0).getReusedLocator().getFieldName());
    }

    /** The serialized form carries the derived flags; reading must tolerate them, not choke. */
    @Test
    void derivedAccessorsAreWrittenOutAndIgnoredOnTheWayBackIn() throws Exception {
        String json = mapper.writeValueAsString(new CandidateLocator(
                CandidateLocator.Strategy.INDEX_BASED, "new UiSelector().instance(2)", "android.view.View"));

        assertTrue(json.contains("\"brittle\""), json);
        assertDoesNotThrow(() -> mapper.readValue(json, CandidateLocator.class));
    }
}
