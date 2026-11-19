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
