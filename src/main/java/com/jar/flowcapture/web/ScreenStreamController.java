package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.config.FlowCaptureProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public ScreenStreamController(AppiumSessionService appiumSessionService, FlowCaptureProperties properties) {
        this.appiumSessionService = appiumSessionService;
        this.pollIntervalMs = properties.getScreenStream().getPollIntervalMs();
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
