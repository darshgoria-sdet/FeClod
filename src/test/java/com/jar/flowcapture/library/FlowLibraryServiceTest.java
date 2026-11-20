package com.jar.flowcapture.library;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.codegen.TargetArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowLibraryServiceTest {

    private RecordedFlow flow(String name) {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName(name);
        flow.setTargetArea(TargetArea.GOLD);
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel("offers card");
        step.setCandidateLocators(List.of(new CandidateLocator(
                CandidateLocator.Strategy.RESOURCE_ID, "com.jar:id/offers", "android.view.View")));
        flow.addStep(step);
        return flow;
    }

    private FlowLibraryService.SavedFlow saved(RecordedFlow flow, Path testFile, String content) {
        var saved = new FlowLibraryService.SavedFlow();
        saved.setFlow(flow);
        saved.setFinishedAt(Instant.now());
        saved.setTestFile(new FlowLibraryService.WrittenFile(testFile.toString(), content, false));
        return saved;
    }

    @Test
    void aFinishedFlowComesBackWithItsStepsIntact(@TempDir Path dir) {
        var library = new FlowLibraryService(dir);
        RecordedFlow original = flow("Test4");

        library.save(saved(original, dir.resolve("Test4Test.java"), "class Test4Test {}"));
        var reopened = library.load(original.getId()).orElseThrow();

        assertEquals("Test4", reopened.getFlow().getFlowName());
        assertEquals(1, reopened.getFlow().getSteps().size());
        assertEquals("offers card", reopened.getFlow().getSteps().get(0).getActionLabel());
        assertEquals("com.jar:id/offers",
                reopened.getFlow().getSteps().get(0).getCandidateLocators().get(0).getValue());
    }

    /** The id survives the round trip, which is what lets a second finish update the same files. */
    @Test
    void theFlowKeepsItsIdenitySoARefinishCanRecogniseItsOwnFiles(@TempDir Path dir) {
        var library = new FlowLibraryService(dir);
        RecordedFlow original = flow("Test4");

        library.save(saved(original, dir.resolve("Test4Test.java"), "class Test4Test {}"));

        assertEquals(original.getId(), library.load(original.getId()).orElseThrow().getFlow().getId());
    }

    @Test
    void savedFlowsAreListedNewestFirst(@TempDir Path dir) throws Exception {
        var library = new FlowLibraryService(dir);
        var older = saved(flow("Older"), dir.resolve("a.java"), "a");
        older.setFinishedAt(Instant.now().minusSeconds(600));
        var newer = saved(flow("Newer"), dir.resolve("b.java"), "b");

        library.save(older);
        library.save(newer);

        assertEquals(List.of("Newer", "Older"),
                library.list().stream().map(FlowLibraryService.Summary::flowName).toList());
    }

    /**
     * The question a re-finish has to answer before overwriting: is this still the file we
     * wrote, or has somebody worked on it since?
     */
    @Test
    void anUntouchedGeneratedFileIsRecognisedAsOurOwn(@TempDir Path dir) throws Exception {
        var library = new FlowLibraryService(dir);
        Path written = dir.resolve("Test4Test.java");
        Files.writeString(written, "class Test4Test {}");
        var record = new FlowLibraryService.WrittenFile(written.toString(), "class Test4Test {}", false);

        assertTrue(library.isUnchangedSinceWrite(record));
    }

    @Test
    void aHandEditedFileIsNotOurOwnAnyMore(@TempDir Path dir) throws Exception {
        var library = new FlowLibraryService(dir);
        Path written = dir.resolve("Test4Test.java");
        var record = new FlowLibraryService.WrittenFile(written.toString(), "class Test4Test {}", false);
        Files.writeString(written, "class Test4Test { /* someone added this */ }");

        assertFalse(library.isUnchangedSinceWrite(record));
    }

    @Test
    void aFileThatHasSinceBeenDeletedIsNotOurOwnEither(@TempDir Path dir) {
        var library = new FlowLibraryService(dir);
        var record = new FlowLibraryService.WrittenFile(dir.resolve("gone.java").toString(), "x", false);

        assertFalse(library.isUnchangedSinceWrite(record));
    }

    @Test
    void forgettingAFlowRemovesItFromTheList(@TempDir Path dir) {
        var library = new FlowLibraryService(dir);
        RecordedFlow one = flow("One");
        library.save(saved(one, dir.resolve("a.java"), "a"));

        library.delete(one.getId());

        assertTrue(library.list().isEmpty());
        assertTrue(library.load(one.getId()).isEmpty());
    }

    /** Ids come off disk, so a crafted one must not be able to climb out of the library folder. */
    @Test
    void anIdThatLooksLikeAPathIsRefused(@TempDir Path dir) {
        var library = new FlowLibraryService(dir);

        assertThrows(IllegalArgumentException.class, () -> library.load("../../etc/passwd"));
    }

    /**
     * Output that went to a *GeneratedDraft.java means the flow does not own the file it is
     * named after any more. Regenerating will not change what anyone runs, so re-opening must
     * not promise that it will.
     */
    @Test
    void aFlowWhoseOutputWentToADraftIsNotTreatedAsOwningItsFile(@TempDir Path dir) throws Exception {
        var library = new FlowLibraryService(dir);
        Path draft = dir.resolve("Test4PageGeneratedDraft.java");
        Files.writeString(draft, "class Test4Page {}");
        var record = new FlowLibraryService.WrittenFile(draft.toString(), "class Test4Page {}", true);

        assertFalse(library.isUnchangedSinceWrite(record),
                "the bytes match, but the real file is somebody else's now");
    }
}
