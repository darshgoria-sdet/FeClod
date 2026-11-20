package com.jar.flowcapture.verify;

import com.jar.flowcapture.config.FlowCaptureProperties;
import com.jar.flowcapture.settings.OutputPathService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RealTestRunServiceTest {

    private RealTestRunService serviceFor(Path repo, String mavenPath) {
        FlowCaptureProperties properties = new FlowCaptureProperties();
        properties.setTargetRepoPath(repo.toString());
        properties.setMavenPath(mavenPath);
        return new RealTestRunService(properties, new OutputPathService(properties));
    }

    private Path generatedTest(Path repo) throws Exception {
        Path dir = repo.resolve("src/test/java/com/jar/testCases/goldTestCase");
        Files.createDirectories(dir);
        Path file = dir.resolve("Test4Test.java");
        Files.writeString(file, "class Test4Test {}");
        return file;
    }

    @Test
    void refusesToRunATestThatHasNotBeenGeneratedYet(@TempDir Path repo) {
        var service = serviceFor(repo, "/bin/echo");

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.start("com.jar.testCases.goldTestCase.Nope", "PHYSICAL_DEVICE"));

        assertTrue(error.getMessage().contains("generate the flow"), error.getMessage());
    }

    /**
     * Running with -Dtest= would drop the repo's suite, and with it the "env" parameter
     * BaseSetup reads and the listeners the repo relies on — the test would then fail for
     * reasons that have nothing to do with the recorded flow. So a one-class suite is
     * written that keeps everything else.
     */
    @Test
    void writesAOneClassSuiteCarryingTheRepoSListenersAndEnv(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/bin/echo");

        service.start("com.jar.testCases.goldTestCase.Test4Test", "PHYSICAL_DEVICE");

        String suite = Files.readString(repo.resolve("target/flow-capture/verify-suite.xml"));
        assertTrue(suite.contains("<parameter name=\"env\" value=\"PHYSICAL_DEVICE\"/>"), suite);
        assertTrue(suite.contains("<class name=\"com.jar.testCases.goldTestCase.Test4Test\"/>"), suite);
        assertTrue(suite.contains("listeners.AllureListener"), suite);
        assertEquals(1, suite.split("<class ", -1).length - 1, "exactly one class runs");
    }

    /** target/ is gitignored in the target repo, so verifying leaves the working tree alone. */
    @Test
    void theSuiteGoesUnderTargetSoNothingLandsInTheWorkingTree(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/bin/echo");

        service.start("com.jar.testCases.goldTestCase.Test4Test", null);

        assertTrue(Files.isRegularFile(repo.resolve("target/flow-capture/verify-suite.xml")));
        try (var top = Files.list(repo)) {
            assertEquals(2, top.count(), "only src/ and target/ exist at the repo root");
        }
    }

    @Test
    void defaultsToThePhysicalDeviceEnvironment(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/bin/echo");

        service.start("com.jar.testCases.goldTestCase.Test4Test", null);

        assertTrue(Files.readString(repo.resolve("target/flow-capture/verify-suite.xml"))
                .contains("value=\"PHYSICAL_DEVICE\""));
    }

    /** The env and class name go straight into generated XML, so neither may be arbitrary text. */
    @Test
    void refusesAnEnvironmentOrClassNameThatIsNotOne(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/bin/echo");

        assertThrows(IllegalArgumentException.class,
                () -> service.start("com.jar.testCases.goldTestCase.Test4Test", "\"/><evil"));
    }

    @Test
    void aMavenThatCannotBeStartedIsReportedRatherThanThrown(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/definitely/not/maven");

        service.start("com.jar.testCases.goldTestCase.Test4Test", "PHYSICAL_DEVICE");

        var status = service.status();
        assertEquals(RealTestRunService.State.ERROR, status.getState());
        assertTrue(status.getMessage().contains("Could not start Maven"), status.getMessage());
    }

    @Test
    void aRunThatExitsZeroIsReportedAsPassed(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/bin/echo");

        service.start("com.jar.testCases.goldTestCase.Test4Test", "PHYSICAL_DEVICE");
        waitUntilFinished(service);

        assertEquals(RealTestRunService.State.PASSED, service.status().getState());
        assertEquals(0, service.status().getExitCode());
    }

    @Test
    void aRunThatExitsNonZeroIsReportedAsFailed(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        var service = serviceFor(repo, "/usr/bin/false");

        service.start("com.jar.testCases.goldTestCase.Test4Test", "PHYSICAL_DEVICE");
        waitUntilFinished(service);

        assertEquals(RealTestRunService.State.FAILED, service.status().getState());
        assertNotEquals(0, service.status().getExitCode());
    }

    /** The surefire line is what a human looks for first, so it is pulled out of the noise. */
    @Test
    void theSurefireSummaryLineIsPickedOutOfTheOutput(@TempDir Path repo) throws Exception {
        generatedTest(repo);
        Path fakeMaven = repo.resolve("fake-mvn");
        Files.writeString(fakeMaven, """
                #!/bin/sh
                echo "[INFO] Scanning for projects..."
                echo "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in Test4Test"
                echo "[INFO] BUILD SUCCESS"
                """);
        fakeMaven.toFile().setExecutable(true);
        var service = serviceFor(repo, fakeMaven.toString());

        service.start("com.jar.testCases.goldTestCase.Test4Test", null);
        waitUntilFinished(service);

        assertEquals("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", service.status().getSummary());
        assertTrue(service.status().getOutput().stream().anyMatch(l -> l.contains("BUILD SUCCESS")),
                service.status().getOutput().toString());
    }

    /** A class name goes straight into generated XML, so it may not be arbitrary text either. */
    @Test
    void refusesAClassNameThatIsNotOne(@TempDir Path repo) throws Exception {
        Path dir = repo.resolve("src/test/java");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("odd name.java"), "x");
        var service = serviceFor(repo, "/bin/echo");

        assertThrows(IllegalArgumentException.class, () -> service.start("odd name", "PHYSICAL_DEVICE"));
    }

    private void waitUntilFinished(RealTestRunService service) throws InterruptedException {
        for (int i = 0; i < 100 && service.isRunning(); i++) Thread.sleep(50);
        assertFalse(service.isRunning(), "the run should have finished by now");
    }
}
