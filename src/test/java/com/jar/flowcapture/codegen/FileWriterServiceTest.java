package com.jar.flowcapture.codegen;

import com.jar.flowcapture.config.FlowCaptureProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileWriterServiceTest {

    @TempDir
    Path repoRoot;

    private FileWriterService fileWriterService;

    @BeforeEach
    void setUp() {
        FlowCaptureProperties properties = new FlowCaptureProperties();
        properties.setTargetRepoPath(repoRoot.toString());
        fileWriterService = new FileWriterService(properties);
    }

    @Test
    void doesNotOverwriteAnExistingHandWrittenFileByDefault() throws IOException {
        Path existingDir = repoRoot.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(existingDir);
        Files.writeString(existingDir.resolve("FooLocators.java"), "// hand-written, do not touch");

        var result = fileWriterService.writeLocatorsClass(
                "locaters/goldLocators", "FooLocators", "// generated", false);

        assertTrue(result.isCollisionAvoided());
        assertEquals("// hand-written, do not touch", Files.readString(existingDir.resolve("FooLocators.java")),
                "the original hand-written file must be untouched");
        assertEquals("FooLocatorsGeneratedDraft.java", result.getPath().getFileName().toString());
        assertEquals("// generated", Files.readString(result.getPath()));
    }

    @Test
    void allowOverwriteTrueReplacesTheExistingFile() throws IOException {
        Path existingDir = repoRoot.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(existingDir);
        Files.writeString(existingDir.resolve("FooLocators.java"), "// old content");

        var result = fileWriterService.writeLocatorsClass(
                "locaters/goldLocators", "FooLocators", "// new content", true);

        assertFalse(result.isCollisionAvoided());
        assertEquals("FooLocators.java", result.getPath().getFileName().toString());
        assertEquals("// new content", Files.readString(result.getPath()));
    }

    @Test
    void validateTargetRepoPathThrowsWhenPathMissing() {
        FlowCaptureProperties properties = new FlowCaptureProperties();
        properties.setTargetRepoPath(repoRoot.resolve("does-not-exist").toString());
        FileWriterService badWriter = new FileWriterService(properties);

        assertThrows(IllegalStateException.class, badWriter::validateTargetRepoPathOrThrow);
    }

    /**
     * Regenerating a re-opened flow has to be able to update the file it wrote last time.
     *
     * Without this every edit-and-regenerate cycle produces another *GeneratedDraft.java and
     * the file that actually runs never changes — which would make re-opening a finished flow
     * pointless.
     */
    @Test
    void regeneratingUpdatesTheFileThisToolWroteLastTime() throws IOException {
        Path dir = repoRoot.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(dir);
        String firstGeneration = "// generated, first pass";
        Files.writeString(dir.resolve("FooLocators.java"), firstGeneration);

        var result = fileWriterService.writeLocatorsClass(
                "locaters/goldLocators", "FooLocators", "// generated, with a wait added", false,
                com.jar.flowcapture.library.FlowLibraryService.hash(firstGeneration));

        assertFalse(result.isCollisionAvoided(), "its own untouched output is not a collision");
        assertEquals("// generated, with a wait added", Files.readString(dir.resolve("FooLocators.java")));
        assertFalse(Files.exists(dir.resolve("FooLocatorsGeneratedDraft.java")));
    }

    /** Edited since it was generated: the edit wins, and the new output goes beside it. */
    @Test
    void aFileEditedSinceGenerationIsStillNeverOverwritten() throws IOException {
        Path dir = repoRoot.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FooLocators.java"), "// generated, then edited by hand");

        var result = fileWriterService.writeLocatorsClass(
                "locaters/goldLocators", "FooLocators", "// regenerated", false,
                com.jar.flowcapture.library.FlowLibraryService.hash("// generated, first pass"));

        assertTrue(result.isCollisionAvoided());
        assertEquals("// generated, then edited by hand", Files.readString(dir.resolve("FooLocators.java")));
        assertEquals("// regenerated", Files.readString(dir.resolve("FooLocatorsGeneratedDraft.java")));
    }

    /**
     * A draft has to compile. Java requires a public class to sit in a file of its own name,
     * so a draft holding "public class FooPage" in FooPageGeneratedDraft.java does not just
     * fail on its own — it duplicates the real class and breaks the whole module's build.
     * Three such files in the target repo made `mvn test` fail before any test ran, behind a
     * cascade of "cannot find symbol" from Lombok never getting the chance to run.
     */
    @Test
    void aDraftIsRenamedToMatchItsFilenameSoTheModuleStillCompiles() throws IOException {
        Path dir = repoRoot.resolve("src/main/Java/pageObjectModel/gold");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FooPage.java"), "// hand-written");

        fileWriterService.writePageClass("pageObjectModel/gold", "FooPage", """
                package pageObjectModel.gold;

                public class FooPage {
                    public FooPage() {
                    }
                }
                """, false);

        String draft = Files.readString(dir.resolve("FooPageGeneratedDraft.java"));
        assertTrue(draft.contains("public class FooPageGeneratedDraft {"), draft);
        assertTrue(draft.contains("public FooPageGeneratedDraft() {"), draft);
        assertFalse(draft.contains("public class FooPage {"), draft);
    }

    /** The rename is only for drafts — an ordinary write must not touch the class name. */
    @Test
    void anOrdinaryWriteKeepsTheGeneratedClassNameExactly() throws IOException {
        var result = fileWriterService.writePageClass("pageObjectModel/gold", "FooPage",
                "package pageObjectModel.gold;\n\npublic class FooPage {\n}\n", false);

        assertFalse(result.isCollisionAvoided());
        assertTrue(Files.readString(result.getPath()).contains("public class FooPage {"));
    }

    /** Only the declaration and constructors move; references elsewhere are left alone. */
    @Test
    void theRenameDoesNotTouchOtherMentionsOfTheName() {
        String renamed = FileWriterService.renameClassForDraft("""
                package pageObjectModel.gold;

                import locaters.goldLocators.FooPageLocators;

                public class FooPage {
                    // FooPage is referenced in this comment
                    FooPageLocators fooPageLocators = new FooPageLocators(null);
                }
                """, "FooPage");

        assertTrue(renamed.contains("public class FooPageGeneratedDraft {"));
        assertTrue(renamed.contains("// FooPage is referenced in this comment"));
        assertTrue(renamed.contains("new FooPageLocators(null)"));
    }
}
