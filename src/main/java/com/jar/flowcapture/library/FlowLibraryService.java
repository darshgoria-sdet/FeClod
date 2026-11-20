package com.jar.flowcapture.library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jar.flowcapture.capture.RecordedFlow;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Keeps every finished flow, so a recording outlives the moment it was generated.
 *
 * Until now finishing a flow ended it: the files landed in the repo and the recording that
 * produced them was dropped. That makes "add a wait and regenerate" impossible — the only
 * way to change one step of a generated test was to record the whole journey again, or to
 * hand-edit Java that nothing can replay. Keeping the flow is what turns generation from a
 * one-shot export into something you can come back to.
 *
 * Each entry also remembers the files it wrote and what they contained, which is how a
 * re-finish can tell its own untouched output (safe to overwrite) from a file someone has
 * since edited by hand (never overwrite silently).
 */
@Slf4j
@Service
public class FlowLibraryService {

    private final Path libraryDir;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public FlowLibraryService() {
        this(Paths.get(System.getProperty("user.home"), ".flow-capture-studio", "flows"));
    }

    /** Directory injected so a test can exercise this without writing into a real home directory. */
    FlowLibraryService(Path libraryDir) {
        this.libraryDir = libraryDir;
    }

    /** One generated file as it was when this tool last wrote it. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WrittenFile {
        private String path;
        /** SHA-256 of the exact bytes written, so a later hand edit is detectable. */
        private String sha256;
        /**
         * True when the real file was already taken, so this output went to a sibling
         * *GeneratedDraft.java instead. The flow no longer owns the file it is named after,
         * and regenerating will not change what anyone actually runs — worth saying out loud
         * when the flow is re-opened rather than discovering it after the next finish.
         */
        private boolean wroteDraftInstead;

        public WrittenFile() {}

        public WrittenFile(String path, String content, boolean wroteDraftInstead) {
            this.path = path;
            this.sha256 = hash(content);
            this.wroteDraftInstead = wroteDraftInstead;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SavedFlow {
        private RecordedFlow flow;
        private Instant finishedAt;
        private WrittenFile locatorsFile;
        private WrittenFile pageFile;
        private WrittenFile testFile;
    }

    /** What the dashboard lists — the flow itself can be large (thumbnails), so it is not included. */
    public record Summary(String id, String flowName, String targetArea, Instant finishedAt,
                          int stepCount, String testFilePath) {}

    public void save(SavedFlow saved) {
        try {
            Files.createDirectories(libraryDir);
            Files.writeString(fileFor(saved.getFlow().getId()),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(saved),
                    StandardCharsets.UTF_8);
            log.info("Saved flow '{}' to the library", saved.getFlow().getFlowName());
        } catch (IOException e) {
            // The files are already written to the repo at this point. Losing the archive copy
            // is a lost convenience, not lost work, and must not fail the finish.
            log.warn("Could not save flow to the library: {}", e.getMessage());
        }
    }

    public List<Summary> list() {
        List<Summary> summaries = new ArrayList<>();
        if (!Files.isDirectory(libraryDir)) return summaries;
        try (var entries = Files.list(libraryDir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(path -> {
                readFile(path).ifPresent(saved -> summaries.add(new Summary(
                        saved.getFlow().getId(),
                        saved.getFlow().getFlowName(),
                        String.valueOf(saved.getFlow().getTargetArea()),
                        saved.getFinishedAt(),
                        saved.getFlow().getSteps().size(),
                        saved.getTestFile() == null ? null : saved.getTestFile().getPath())));
            });
        } catch (IOException e) {
            log.warn("Could not list saved flows: {}", e.getMessage());
        }
        summaries.sort(Comparator.comparing(Summary::finishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return summaries;
    }

    public Optional<SavedFlow> load(String id) {
        return readFile(fileFor(id));
    }

    public void delete(String id) {
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete saved flow " + id, e);
        }
    }

    /**
     * Whether the file on disk is still exactly what this tool last wrote there.
     *
     * The question a re-finish has to answer before overwriting. Same bytes means nobody has
     * touched it and regenerating is safe; anything else — edited by hand, merged, or a file
     * this tool never wrote — means the caller must not overwrite without being asked.
     */
    public boolean isUnchangedSinceWrite(WrittenFile record) {
        if (record == null || record.getPath() == null) return false;
        if (record.isWroteDraftInstead()) return false;
        try {
            Path path = Path.of(record.getPath());
            if (!Files.isRegularFile(path)) return false;
            return hash(Files.readString(path, StandardCharsets.UTF_8)).equals(record.getSha256());
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<SavedFlow> readFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            SavedFlow saved = objectMapper.readValue(path.toFile(), SavedFlow.class);
            return saved.getFlow() == null ? Optional.empty() : Optional.of(saved);
        } catch (IOException e) {
            log.warn("Skipping unreadable saved flow {}: {}", path.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Path fileFor(String id) {
        // Ids are generated UUIDs, but this path is built from data read off disk, so it is
        // still worth refusing anything that could climb out of the library directory.
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Not a valid flow id: " + id);
        }
        return libraryDir.resolve(id + ".json");
    }

    /** Public so the file writer can ask the same question of a file on disk. */
    public static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
