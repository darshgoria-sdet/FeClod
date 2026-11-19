package com.jar.flowcapture.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Persists the in-progress RecordedFlow to a local JSON file after every step, so a
 * crashed/restarted Flow Capture Studio process (or a QA engineer pausing to grab
 * lunch) doesn't lose an entire recording session. This is the tool's own internal
 * state file, not code generated into Jar_Frontend_Automation — the "org.json only"
 * convention that applies to *generated* files doesn't apply here, so this uses
 * Jackson (already on the classpath via spring-boot-starter-web) rather than hand-
 * rolling JSON serialization for nested lists/enums.
 */
@Slf4j
@Service
public class FlowPersistenceService {

    private static final Path SESSION_FILE = Paths.get(System.getProperty("user.home"), ".flow-capture-studio", "current-session.json");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // registers the JSR-310 module for Instant

    public void save(RecordedFlow flow) {
        try {
            Files.createDirectories(SESSION_FILE.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(SESSION_FILE.toFile(), flow);
        } catch (IOException e) {
            // Persistence is a safety net, not the primary data path — a failed save
            // should never interrupt an in-progress recording.
            log.warn("Failed to persist recording session: {}", e.getMessage());
        }
    }

    public RecordedFlow loadIfPresent() {
        if (!Files.exists(SESSION_FILE)) return null;
        try {
            return objectMapper.readValue(SESSION_FILE.toFile(), RecordedFlow.class);
        } catch (IOException e) {
            log.warn("Failed to load persisted recording session, starting fresh: {}", e.getMessage());
            return null;
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(SESSION_FILE);
        } catch (IOException e) {
            log.warn("Failed to delete persisted session file: {}", e.getMessage());
        }
    }
}
