package com.jar.flowcapture.verify;

import com.jar.flowcapture.config.FlowCaptureProperties;
import com.jar.flowcapture.settings.OutputPathService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the generated TestNG class for real: Maven, the repo's own listeners, the repo's own
 * BaseSetup, against the phone.
 *
 * Step replay can only ever check the step list. Once a flow carries hand edits, a try/catch
 * or anything else the model does not describe, replay is checking a different program from
 * the one that was written — and it can never catch a missing import, a class that forgets to
 * extend BaseSetup, or a locator PageFactory resolves differently from findElements. This is
 * the only check that is the actual test, so it is the last word.
 *
 * The run is a child process, not an in-process TestNG invocation: the repo needs its own
 * classpath, its AspectJ weaver agent and its own JVM arguments, and sharing this one would
 * be both fragile and a good way to take the studio down with a failing test.
 */
@Slf4j
@Service
public class RealTestRunService {

    /** Kept small enough to send to the browser on every poll, large enough to hold a stack trace. */
    private static final int MAX_OUTPUT_LINES = 600;

    public enum State { IDLE, RUNNING, PASSED, FAILED, ERROR, STOPPED }

    @Data
    public static class Status {
        private final State state;
        private final String testClass;
        private final Instant startedAt;
        private final long elapsedMs;
        private final Integer exitCode;
        /** "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", when surefire has said it yet. */
        private final String summary;
        private final List<String> output;
        private final String message;
    }

    private final FlowCaptureProperties properties;
    private final OutputPathService outputPathService;

    private final Deque<String> output = new ArrayDeque<>();
    private volatile State state = State.IDLE;
    private volatile String testClass;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile Integer exitCode;
    private volatile String summary;
    private volatile String message;
    private volatile Process process;

    public RealTestRunService(FlowCaptureProperties properties, OutputPathService outputPathService) {
        this.properties = properties;
        this.outputPathService = outputPathService;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /**
     * @param fullyQualifiedTestClass e.g. com.jar.testCases.goldTestCase.Test4Test
     * @param env                     the suite's "env" parameter — BaseSetup reads it to decide
     *                                how to build the driver, so getting it wrong fails before
     *                                the first step
     */
    public synchronized void start(String fullyQualifiedTestClass, String env) {
        if (state == State.RUNNING) {
            throw new IllegalStateException("A test run is already going — stop it or wait for it to finish");
        }
        Path repoRoot = Path.of(outputPathService.currentPath());
        Path testFile = repoRoot.resolve("src/test/java/" + fullyQualifiedTestClass.replace('.', '/') + ".java");
        if (!Files.isRegularFile(testFile)) {
            throw new IllegalArgumentException(
                    "No generated test at " + testFile + " — generate the flow before verifying it");
        }

        Path suite = writeSuite(repoRoot, fullyQualifiedTestClass, env);
        List<String> command = List.of(
                properties.getMavenPath(), "test",
                "-Dsurefire.suiteXmlFiles=" + repoRoot.relativize(suite),
                // Allure's own post-processing is noise here and adds time to every run.
                "-Dallure.results.directory=target/flow-capture/allure");

        output.clear();
        this.testClass = fullyQualifiedTestClass;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.exitCode = null;
        this.summary = null;
        this.message = null;
        this.state = State.RUNNING;
        append("$ " + String.join(" ", command));
        append("  (in " + repoRoot + ")");

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            applyJavaHome(builder);
            process = builder.start();
        } catch (IOException e) {
            state = State.ERROR;
            message = "Could not start Maven (" + properties.getMavenPath() + "): " + e.getMessage();
            append(message);
            return;
        }

        Process started = process;
        Thread reader = new Thread(() -> consume(started), "real-test-run");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * The test runs on the repo's JDK, not the studio's.
     *
     * The studio is pinned to JDK 21 (Lombok does not support 26), and a child Maven inherits
     * that pin through JAVA_HOME — which is how the first real run of a generated test died
     * at "Fatal error compiling ... target 22" without reaching a single step. An explicit
     * test-java-home wins; otherwise JAVA_HOME is dropped so Maven uses the system default.
     */
    private void applyJavaHome(ProcessBuilder builder) {
        String configured = properties.getTestJavaHome();
        if (configured != null && !configured.isBlank()) {
            builder.environment().put("JAVA_HOME", configured.trim());
            append("  (JAVA_HOME " + configured.trim() + ")");
        } else {
            builder.environment().remove("JAVA_HOME");
            append("  (JAVA_HOME unset — using the system default JDK, not the studio's)");
        }
    }

    private void consume(Process started) {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                append(line);
                rememberSummary(line);
            }
            int code = started.waitFor();
            finish(code);
        } catch (IOException e) {
            finishWithError("Lost the Maven output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishWithError("Interrupted while waiting for Maven");
        }
    }

    private synchronized void finish(int code) {
        exitCode = code;
        finishedAt = Instant.now();
        if (state == State.STOPPED) return;   // the user killed it; that verdict stands
        state = code == 0 ? State.PASSED : State.FAILED;
        message = code == 0
                ? "The generated test passed against the device."
                : "The generated test failed. The surefire report in target/surefire-reports has the detail.";
        log.info("Real test run of {} finished with exit code {}", testClass, code);
    }

    private synchronized void finishWithError(String why) {
        state = State.ERROR;
        message = why;
        finishedAt = Instant.now();
        append(why);
    }

    private static final Pattern SUMMARY =
            Pattern.compile("Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+");

    private void rememberSummary(String line) {
        Matcher m = SUMMARY.matcher(line);
        if (m.find()) summary = m.group();
    }

    private synchronized void append(String line) {
        output.addLast(line);
        while (output.size() > MAX_OUTPUT_LINES) output.removeFirst();
    }

    public synchronized void stop() {
        if (state != State.RUNNING || process == null) return;
        state = State.STOPPED;
        message = "Stopped before it finished.";
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        finishedAt = Instant.now();
    }

    public synchronized Status status() {
        long elapsed = startedAt == null ? 0
                : ((finishedAt == null ? Instant.now() : finishedAt).toEpochMilli() - startedAt.toEpochMilli());
        return new Status(state, testClass, startedAt, elapsed, exitCode, summary,
                new ArrayList<>(output), message);
    }

    /**
     * A one-class suite that keeps everything else about the repo's own suite.
     *
     * Running with -Dtest= instead would drop the suite entirely, and with it the "env"
     * parameter BaseSetup reads and the listeners the repo relies on — the test would fail
     * for reasons that have nothing to do with the flow. Written under target/, which this
     * repo already gitignores, so verifying never leaves anything in the working tree.
     */
    private Path writeSuite(Path repoRoot, String fullyQualifiedTestClass, String env) {
        String environment = (env == null || env.isBlank()) ? "PHYSICAL_DEVICE" : env.trim();
        if (!environment.matches("[A-Z_]{1,32}")) {
            throw new IllegalArgumentException("Not a valid environment name: " + env);
        }
        if (!fullyQualifiedTestClass.matches("[A-Za-z0-9_.$]{1,200}")) {
            throw new IllegalArgumentException("Not a valid test class name: " + fullyQualifiedTestClass);
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE suite SYSTEM "http://testng.org/testng-1.0.dtd">
                <!-- Written by Flow Capture Studio to verify one generated test. Safe to delete. -->
                <suite name="Flow Capture verification">
                    <listeners>
                        <listener class-name="listeners.AllureListener"/>
                        <listener class-name="listeners.AnnotationTransformer"/>
                        <listener class-name="listeners.RetryListener"/>
                    </listeners>
                    <parameter name="env" value="%s"/>
                    <test verbose="2" preserve-order="true" name="Flow Capture verification">
                        <classes>
                            <class name="%s"/>
                        </classes>
                    </test>
                </suite>
                """.formatted(environment, fullyQualifiedTestClass);
        try {
            Path dir = repoRoot.resolve("target/flow-capture");
            Files.createDirectories(dir);
            Path suite = dir.resolve("verify-suite.xml");
            Files.writeString(suite, xml, StandardCharsets.UTF_8);
            return suite;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the verification suite: " + e.getMessage(), e);
        }
    }
}
