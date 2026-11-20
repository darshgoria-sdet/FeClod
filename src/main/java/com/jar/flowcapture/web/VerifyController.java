package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.verify.RealTestRunService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * "Verify for real": run the generated TestNG class through Maven against the phone.
 *
 * The reason this exists is that step replay checks the step list, and once a flow carries a
 * try/catch or a hand edit the step list is no longer the program that was written. This runs
 * the program that was written.
 */
@Slf4j
@RestController
@RequestMapping("/api/flow/verify")
public class VerifyController {

    private final RealTestRunService realTestRunService;
    private final AppiumSessionService appiumSessionService;

    public VerifyController(RealTestRunService realTestRunService, AppiumSessionService appiumSessionService) {
        this.realTestRunService = realTestRunService;
        this.appiumSessionService = appiumSessionService;
    }

    @PostMapping
    public Map<String, Object> start(@RequestBody StartRequest request) {
        if (request.getTestClass() == null || request.getTestClass().isBlank()) {
            throw new IllegalArgumentException("Which test class should run?");
        }
        // The device can hold one UiAutomator2 session at a time, and the test is about to
        // create its own through the repo's DriverFactory. Keeping the studio's session open
        // would make the run fail on setup for a reason that has nothing to do with the flow.
        boolean wasConnected = appiumSessionService.isActive();
        if (wasConnected) {
            log.info("Releasing the studio's device session so the real test can take it");
            appiumSessionService.stopSession();
        }
        realTestRunService.start(request.getTestClass(), request.getEnv());
        return Map.of("started", true, "releasedDeviceSession", wasConnected);
    }

    @Data
    public static class StartRequest {
        /** Fully qualified, e.g. com.jar.testCases.goldTestCase.Test4Test. */
        private String testClass;
        /** The suite's "env" parameter; PHYSICAL_DEVICE when not given. */
        private String env;
    }

    @GetMapping("/status")
    public RealTestRunService.Status status() {
        return realTestRunService.status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        realTestRunService.stop();
        return Map.of("stopped", true);
    }
}
