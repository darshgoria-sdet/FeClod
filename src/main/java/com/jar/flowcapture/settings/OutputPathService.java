package com.jar.flowcapture.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jar.flowcapture.codegen.TargetArea;
import com.jar.flowcapture.config.FlowCaptureProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where generated files are written, changeable from the dashboard instead of only from
 * application.yml.
 *
 * The repo path was a launch-time setting, which is wrong for how this is actually used: QA
 * works across more than one checkout, and discovering that a recording went into the wrong
 * clone happens after the recording is finished and the files are already on disk. Making it
 * visible and editable in the UI turns that into a choice rather than an accident.
 *
 * The chosen path is remembered next to the session file, so it survives restarts of the tool
 * without anyone editing config.
 */
@Slf4j
@Service
public class OutputPathService {

    private static final Path SETTINGS_FILE =
            Paths.get(System.getProperty("user.home"), ".flow-capture-studio", "settings.json");

    private final FlowCaptureProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OutputPathService(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    @Data
    public static class StoredSettings {
        private String targetRepoPath;
    }

    /**
     * A saved path wins over application.yml. The yml value is the first-run default; once QA
     * has picked a repo in the UI, silently reverting to the configured one on the next launch
     * would be the same accident this exists to prevent.
     */
    @PostConstruct
    void restoreSavedPath() {
        try {
            if (!Files.isRegularFile(SETTINGS_FILE)) return;
            StoredSettings stored = objectMapper.readValue(SETTINGS_FILE.toFile(), StoredSettings.class);
            if (stored.getTargetRepoPath() != null && !stored.getTargetRepoPath().isBlank()) {
                properties.setTargetRepoPath(stored.getTargetRepoPath());
                log.info("Output repo restored from saved settings: {}", stored.getTargetRepoPath());
            }
        } catch (Exception e) {
            // A corrupt settings file must not stop the tool starting — the configured default
            // is still perfectly usable.
            log.warn("Could not read saved settings ({}) — using the configured repo path", e.getMessage());
        }
    }

    public String currentPath() {
        return properties.getTargetRepoPath();
    }

    /**
     * @throws IllegalArgumentException when the path cannot receive generated files, checked
     *         now rather than at finish time when a whole recording is riding on it
     */
    public void setPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Enter the path to the repo that generated files should go into");
        }
        String expanded = expandHome(rawPath.trim());
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Use an absolute path, e.g. /Users/you/work/Jar_Frontend_Automation");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }
        if (!Files.isWritable(path)) {
            throw new IllegalArgumentException("No write permission for: " + path);
        }
        properties.setTargetRepoPath(path.toString());
        save(path.toString());
        log.info("Output repo set to {}", path);
    }

    /**
     * Advisory, never blocking. A brand-new checkout legitimately has none of these folders
     * yet, and refusing it would be wrong — but silently writing a flow into a repo that is
     * not the automation repo is the failure worth warning about.
     */
    public String warningFor(String path) {
        if (path == null || path.isBlank()) return "No output repo set.";
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) return "That path no longer exists — generated files cannot be written.";
        if (!Files.isDirectory(root.resolve("src/main/Java/locaters"))) {
            return "This does not look like Jar_Frontend_Automation (no src/main/Java/locaters). "
                    + "Files will still be written, creating folders as needed.";
        }
        return null;
    }

    /** The three files' exact destinations for an area, so the choice is unambiguous before finishing. */
    public Map<String, String> destinationsFor(TargetArea area) {
        Path root = Path.of(currentPath());
        Map<String, String> destinations = new LinkedHashMap<>();
        destinations.put("locators", root.resolve("src/main/Java").resolve(area.getLocatorsRelativePath()).toString());
        destinations.put("page", root.resolve("src/main/Java").resolve(area.getPageRelativePath()).toString());
        destinations.put("test", root.resolve("src/test/java").resolve(area.getTestRelativePath()).toString());
        return destinations;
    }

    /** "~/work/repo" is what a human types; Java's Path does not expand it. */
    private String expandHome(String path) {
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    private void save(String path) {
        try {
            File parent = SETTINGS_FILE.getParent().toFile();
            if (!parent.exists() && !parent.mkdirs()) {
                log.warn("Could not create {} — the path will not survive a restart", parent);
                return;
            }
            StoredSettings stored = new StoredSettings();
            stored.setTargetRepoPath(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), stored);
        } catch (Exception e) {
            // The path is already applied in memory; failing to persist it is a convenience
            // loss, not a reason to reject a choice QA has made.
            log.warn("Could not save the output path ({}) — it applies now but not after a restart", e.getMessage());
        }
    }
}
