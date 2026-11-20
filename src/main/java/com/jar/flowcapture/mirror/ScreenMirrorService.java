package com.jar.flowcapture.mirror;

import com.jar.flowcapture.config.FlowCaptureProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Serves the device mirror from the UiAutomator2 server's own MJPEG broadcaster instead of
 * polling Appium for screenshots.
 *
 * Why this exists, measured on a real device rather than assumed:
 *
 *   screenshot polling via Appium   0.26 fps   (~3.9s between frames)
 *   UiAutomator2 MJPEG broadcaster  ~10 fps    (~50 KB JPEG frames)
 *
 * The frame rate is the smaller half of the win. Every Appium screenshot is a WebDriver
 * command that holds this process's driver lock for its whole duration, so the mirror was
 * occupying the driver most of the time — and element lookups during replay had to queue
 * behind it. A replay step could burn its entire 25s timeout getting only a handful of
 * actual checks in, and report "never became visible" for an element that was on screen the
 * whole time. The broadcaster is a separate socket server on the device, outside the
 * WebDriver command queue, so it costs the driver nothing.
 */
@Slf4j
@Service
public class ScreenMirrorService {

    private final FlowCaptureProperties properties;
    private volatile boolean forwarded;

    public ScreenMirrorService(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    /** What the dashboard should point its <img> at. */
    public enum Mode { MJPEG, SCREENSHOT_POLLING }

    /**
     * Sets up the adb port forward and confirms the broadcaster answers. Called on connect.
     * Any failure here is non-fatal: the mirror falls back to screenshot polling, which is
     * slow but always works.
     */
    public synchronized Mode prepare() {
        var config = properties.getScreenStream();
        if (!config.isPreferMjpeg()) {
            log.info("MJPEG mirror disabled by config — using screenshot polling");
            return Mode.SCREENSHOT_POLLING;
        }
        try {
            forwardPort(config);
            if (probe()) {
                forwarded = true;
                log.info("Mirroring via the UiAutomator2 MJPEG broadcaster on host port {}", config.getHostMjpegPort());
                return Mode.MJPEG;
            }
            log.warn("MJPEG broadcaster did not respond on port {} — falling back to screenshot polling",
                    config.getHostMjpegPort());
        } catch (Exception e) {
            log.warn("Could not set up the MJPEG mirror ({}) — falling back to screenshot polling", e.getMessage());
        }
        forwarded = false;
        return Mode.SCREENSHOT_POLLING;
    }

    public boolean isAvailable() {
        return forwarded;
    }

    /**
     * Opens the upstream multipart stream. The caller pipes it straight to the browser,
     * which renders multipart/x-mixed-replace natively in an <img> — no JavaScript decoding
     * and no base64 inflation, unlike the SSE path.
     */
    public Upstream open() throws IOException {
        var config = properties.getScreenStream();
        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + config.getHostMjpegPort())
                .toURL().openConnection();
        connection.setConnectTimeout(3000);
        // No read timeout: this stream is meant to stay open indefinitely.
        connection.setReadTimeout(0);
        connection.connect();
        String contentType = connection.getContentType();
        if (contentType == null || !contentType.contains("multipart")) {
            connection.disconnect();
            throw new IOException("MJPEG broadcaster returned an unexpected content type: " + contentType);
        }
        return new Upstream(connection.getInputStream(), contentType, connection);
    }

    /** The upstream stream plus the exact content type, whose boundary the browser needs verbatim. */
    public record Upstream(InputStream body, String contentType, HttpURLConnection connection) implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing a stream the client already abandoned is routine.
            }
            connection.disconnect();
        }
    }

    /**
     * `adb forward` is idempotent, so this is safe to call on every connect — which matters,
     * because the forward is lost whenever the cable is unplugged and this is the path that
     * re-establishes it.
     */
    private void forwardPort(FlowCaptureProperties.ScreenStream config) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                config.getAdbPath(), "forward",
                "tcp:" + config.getHostMjpegPort(), "tcp:" + config.getDeviceMjpegPort());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("adb forward timed out");
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes()).trim();
            throw new IOException("adb forward failed: " + output);
        }
    }

    /** One short connection to confirm frames are actually coming, rather than trusting the forward. */
    private boolean probe() {
        try (Upstream upstream = open()) {
            byte[] buffer = new byte[4096];
            int read = upstream.body().read(buffer);
            return read > 0;
        } catch (Exception e) {
            log.debug("MJPEG probe failed: {}", e.getMessage());
            return false;
        }
    }
}
