package com.jar.flowcapture.codegen;

import com.jar.flowcapture.config.FlowCaptureProperties;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes generated files into the target repo's working tree only. Never runs git
 * commands — staging/committing is left entirely to the engineer reviewing the diff,
 * per explicit instruction that this tool must not touch git in any repo.
 *
 * Never silently overwrites a file that already exists (it could be a teammate's
 * hand-written class whose name happens to collide with the generated flow name).
 * Unless the caller explicitly opts in via allowOverwrite=true, a collision is
 * redirected to a sibling "*GeneratedDraft.java" file so nothing is lost, and the
 * result tells the caller a collision happened so the dashboard can surface it.
 */
@Slf4j
@Service
public class FileWriterService {

    private final FlowCaptureProperties properties;

    public FileWriterService(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    @Value
    public static class WriteResult {
        Path path;
        boolean collisionAvoided;
    }

    public WriteResult writeLocatorsClass(String packageRelativePath, String className, String content, boolean allowOverwrite) {
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite);
    }

    public WriteResult writePageClass(String packageRelativePath, String className, String content, boolean allowOverwrite) {
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite);
    }

    public WriteResult writeTestClass(String packageRelativePath, String className, String content, boolean allowOverwrite) {
        return write("src/test/java/" + packageRelativePath, className, content, allowOverwrite);
    }

    /** Fails fast at boot rather than letting a QA engineer lose an entire recording session to a bad path at finish() time. */
    public void validateTargetRepoPathOrThrow() {
        Path repoRoot = Path.of(properties.getTargetRepoPath());
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalStateException(
                    "flow-capture.target-repo-path does not exist or is not a directory: " + repoRoot);
        }
    }

    private WriteResult write(String relativeDir, String className, String content, boolean allowOverwrite) {
        validateTargetRepoPathOrThrow();
        Path repoRoot = Path.of(properties.getTargetRepoPath());

        Path targetDir = repoRoot.resolve(relativeDir);
        Path targetFile = targetDir.resolve(className + ".java");
        boolean collisionAvoided = false;

        if (Files.exists(targetFile) && !allowOverwrite) {
            collisionAvoided = true;
            targetFile = targetDir.resolve(className + "GeneratedDraft.java");
            log.warn("{}.java already exists — writing to {} instead so nothing hand-written is overwritten",
                    className, targetFile.getFileName());
        }

        try {
            Files.createDirectories(targetDir);
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
            log.info("Generated file written to {}", targetFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write generated file: " + targetFile, e);
        }

        return new WriteResult(targetFile, collisionAvoided);
    }
}
