package com.jar.flowcapture.codegen;

import com.jar.flowcapture.config.FlowCaptureProperties;
import com.jar.flowcapture.library.FlowLibraryService;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite, null);
    }

    public WriteResult writePageClass(String packageRelativePath, String className, String content, boolean allowOverwrite) {
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite, null);
    }

    public WriteResult writeTestClass(String packageRelativePath, String className, String content, boolean allowOverwrite) {
        return write("src/test/java/" + packageRelativePath, className, content, allowOverwrite, null);
    }

    /**
     * The same three writes, told what this tool last wrote to that file.
     *
     * Regenerating a flow that was already generated once must be able to update its own
     * output — otherwise every edit-and-regenerate produces another *GeneratedDraft.java and
     * the file you actually run never changes. "Its own output" is defined strictly: the file
     * on disk still contains exactly the bytes written last time. A hand edit, a merge, or a
     * file this tool never wrote all fail that test and fall back to the draft.
     */
    public WriteResult writeLocatorsClass(String packageRelativePath, String className, String content,
                                          boolean allowOverwrite, String previousContentHash) {
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite, previousContentHash);
    }

    public WriteResult writePageClass(String packageRelativePath, String className, String content,
                                      boolean allowOverwrite, String previousContentHash) {
        return write("src/main/Java/" + packageRelativePath, className, content, allowOverwrite, previousContentHash);
    }

    public WriteResult writeTestClass(String packageRelativePath, String className, String content,
                                      boolean allowOverwrite, String previousContentHash) {
        return write("src/test/java/" + packageRelativePath, className, content, allowOverwrite, previousContentHash);
    }

    /** Fails fast at boot rather than letting a QA engineer lose an entire recording session to a bad path at finish() time. */
    public void validateTargetRepoPathOrThrow() {
        Path repoRoot = Path.of(properties.getTargetRepoPath());
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalStateException(
                    "flow-capture.target-repo-path does not exist or is not a directory: " + repoRoot);
        }
    }

    private WriteResult write(String relativeDir, String className, String content,
                              boolean allowOverwrite, String previousContentHash) {
        validateTargetRepoPathOrThrow();
        Path repoRoot = Path.of(properties.getTargetRepoPath());

        Path targetDir = repoRoot.resolve(relativeDir);
        Path targetFile = targetDir.resolve(className + ".java");
        boolean collisionAvoided = false;

        if (Files.exists(targetFile) && !allowOverwrite && !isOurOwnUntouchedOutput(targetFile, previousContentHash)) {
            collisionAvoided = true;
            targetFile = targetDir.resolve(className + "GeneratedDraft.java");
            content = renameClassForDraft(content, className);
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

    /**
     * Renames the class inside a draft to match the draft's filename.
     *
     * Java requires a public class to live in a file of the same name, so writing
     * "public class FestiveSavingsPage" into FestiveSavingsPageGeneratedDraft.java produces a
     * file that cannot compile — and, because the real class is still next to it, one that
     * breaks the whole module rather than just itself. Three such files were sitting in the
     * target repo making `mvn test` fail before a single test ran, and the only clue was a
     * cascade of "cannot find symbol" from Lombok never getting to run.
     *
     * A draft is for reading and merging, so the rename is deliberately shallow: the class
     * declaration and its constructors, nothing else. References to it from the other two
     * generated files still point at the real class, which is correct — the draft is the odd
     * one out, not the thing anyone should be wiring up.
     */
    static String renameClassForDraft(String content, String className) {
        String draftName = className + "GeneratedDraft";
        return content
                .replaceAll("(?m)^(\\s*public\\s+class\\s+)" + Pattern.quote(className) + "\\b", "$1" + draftName)
                .replaceAll("(?m)^(\\s*public\\s+)" + Pattern.quote(className) + "\\s*\\(", "$1" + draftName + "(");
    }

    /** True only when the file still holds exactly the bytes this tool last wrote to it. */
    private boolean isOurOwnUntouchedOutput(Path targetFile, String previousContentHash) {
        if (previousContentHash == null) return false;
        try {
            return FlowLibraryService.hash(Files.readString(targetFile, StandardCharsets.UTF_8))
                    .equals(previousContentHash);
        } catch (IOException e) {
            return false;
        }
    }
}
