package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.config.FlowCaptureProperties;
import com.jar.flowcapture.mirror.ScreenMirrorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Streams device screenshots to the dashboard via Server-Sent Events — simplest
 * real-time option for a one-way "mirror the phone" feed, avoiding the WebSocket/STOMP
 * ceremony a bidirectional channel would need (clicks go back over a plain POST instead).
 */
@Slf4j
@RestController
public class ScreenStreamController {

    private final AppiumSessionService appiumSessionService;
    private final ScreenMirrorService screenMirrorService;
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public ScreenStreamController(AppiumSessionService appiumSessionService,
                                  ScreenMirrorService screenMirrorService,
                                  FlowCaptureProperties properties) {
        this.appiumSessionService = appiumSessionService;
        this.screenMirrorService = screenMirrorService;
        this.pollIntervalMs = properties.getScreenStream().getPollIntervalMs();
    }

    /**
     * Pipes the device's own MJPEG broadcast straight to the browser's <img>. The content
     * type is copied verbatim from upstream because it carries the multipart boundary the
     * browser parses frames on.
     *
     * Everything here is a byte copy: no decoding, no base64, and crucially no WebDriver
     * command, so a dashboard sitting open costs element lookups nothing.
     */
    @GetMapping("/api/screen-mjpeg")
    public void mjpeg(HttpServletResponse response) throws IOException {
        try (var upstream = screenMirrorService.open()) {
            response.setContentType(upstream.contentType());
            response.setHeader("Cache-Control", "no-store");
            InputStream in = upstream.body();
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // The browser navigating away or reloading closes this mid-copy every time;
            // that is the normal end of a mirror stream, not a fault worth logging loudly.
            log.debug("MJPEG stream ended: {}", e.getMessage());
        }
    }

    @GetMapping(value = "/api/screen-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout, client disconnect ends it

        // scheduleWithFixedDelay, NOT scheduleAtFixedRate: a device screenshot takes ~900ms
        // on a real phone, well over the poll interval, and fixed-rate would keep queueing
        // ticks that then serialise behind AppiumSessionService's lock — the mirror falls
        // progressively further behind the phone and taps land on stale coordinates. Fixed
        // delay waits for each capture to finish before counting the next interval.
        var future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!appiumSessionService.isActive()) return;
                String base64 = appiumSessionService.captureScreenshotBase64();
                emitter.send(SseEmitter.event().name("frame").data(base64));
            } catch (Exception e) {
                log.debug("Screen stream tick failed (likely client disconnected or session stopped): {}", e.getMessage());
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));

        return emitter;
    }
}
