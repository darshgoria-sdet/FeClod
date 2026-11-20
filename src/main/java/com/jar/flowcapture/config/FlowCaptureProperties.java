package com.jar.flowcapture.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "flow-capture")
public class FlowCaptureProperties {

    private String targetRepoPath;

    /**
     * How to invoke Maven when running a generated test for real. Absolute by default
     * because the studio is usually launched from a desktop app rather than a login shell,
     * so "mvn" alone is often not on the PATH it inherits.
     */
    private String mavenPath = "mvn";

    /**
     * JAVA_HOME for a real test run, when the target repo needs a different JDK from the one
     * the studio runs on. It does here: the studio is pinned to JDK 21 because Lombok's
     * annotation processor does not support JDK 26, while Jar_Frontend_Automation compiles
     * with --release 22. Maven inherits this process's environment, so without this the run
     * fails at "Fatal error compiling ... target 22" before a single step executes.
     *
     * Left blank, JAVA_HOME is removed from the child's environment so Maven falls back to
     * the system default — which is the right answer whenever the studio's own pin is the
     * only thing in the way.
     */
    private String testJavaHome;

    @NestedConfigurationProperty
    private Appium appium = new Appium();

    @NestedConfigurationProperty
    private ScreenStream screenStream = new ScreenStream();

    @NestedConfigurationProperty
    private Bifrost bifrost = new Bifrost();

    @Data
    public static class Appium {
        private String serverUrl;
        private String platformName;
        private String automationName;
        private String deviceName;
        /** Matches androidPackage in Jar_Frontend_Automation's config.properties — used by the openApp/closeApp quick actions. */
        private String androidPackage;
    }

    @Data
    public static class ScreenStream {
        /** Only used by the slow Appium-screenshot fallback; the MJPEG mirror is not polled. */
        private long pollIntervalMs;

        /**
         * The UiAutomator2 server's own MJPEG broadcaster port ON THE DEVICE. It runs
         * independently of the WebDriver command queue, which is what makes it both far
         * faster and free of contention with element lookups.
         */
        private int deviceMjpegPort;

        /** Host port that deviceMjpegPort is adb-forwarded to. */
        private int hostMjpegPort;

        /** Set false to force the old screenshot-polling mirror, e.g. when debugging. */
        private boolean preferMjpeg;

        private String adbPath;
    }

    @Data
    public static class Bifrost {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String model;
    }
}
