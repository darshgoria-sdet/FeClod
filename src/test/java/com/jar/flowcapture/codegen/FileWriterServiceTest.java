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
}
