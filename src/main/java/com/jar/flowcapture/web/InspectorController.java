package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.LocatorBy;
import com.jar.flowcapture.inspect.PageSourceTreeService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The inspector: the device's current element tree with every attribute, the way Appium
 * Inspector shows it, plus a locator bench for trying a selector against the live device
 * before it is committed to a step.
 *
 * It exists because the ranker's first choice is a guess about stability, and only the
 * person who knows the app knows which attribute survives the next release. Showing the
 * whole tree turns that guess into a decision.
 */
@Slf4j
@RestController
@RequestMapping("/api/flow/inspect")
public class InspectorController {

    private final AppiumSessionService appiumSessionService;
    private final PageSourceTreeService pageSourceTreeService;

    public InspectorController(AppiumSessionService appiumSessionService,
                               PageSourceTreeService pageSourceTreeService) {
        this.appiumSessionService = appiumSessionService;
        this.pageSourceTreeService = pageSourceTreeService;
    }

    /**
     * @param x,y optional tap point in device pixels — the node a tap there would hit comes
     *            back as selectedId, so clicking the mirror selects in the tree.
     */
    @GetMapping("/tree")
    public PageSourceTreeService.Tree tree(@RequestParam(required = false) Integer x,
                                           @RequestParam(required = false) Integer y) {
        return pageSourceTreeService.build(appiumSessionService.capturePageSource(), x, y);
    }

    /** The raw XML, for when the tree view is not enough and QA wants to read the source itself. */
    @GetMapping("/source")
    public Map<String, String> source() {
        return Map.of("xml", appiumSessionService.capturePageSource());
    }

    /**
     * Runs a locator against the device right now and reports what it finds.
     *
     * The count is what matters: a locator matching 0 elements is broken, one matching
     * several resolves to whichever the driver returns first, which is not reliably the
     * element anyone meant. Timing comes back too, because an XPath that scans the whole
     * hierarchy can pass and still make a suite slow.
     */
    @PostMapping("/test-locator")
    public TestResult testLocator(@RequestBody CandidateLocator locator) {
        By by = LocatorBy.toBy(locator);
        if (by == null) {
            throw new IllegalArgumentException(
                    "This strategy carries a placeholder index and cannot be run — fill in the instance number first");
        }
        long startedAt = System.currentTimeMillis();
        int matches;
        try {
            matches = appiumSessionService.countMatches(by);
        } catch (RuntimeException e) {
            // A malformed XPath is a user mistake, not a studio failure — report it as a result.
            return new TestResult(by.toString(), 0, false, System.currentTimeMillis() - startedAt,
                    "Invalid locator: " + rootMessage(e));
        }
        boolean visible = matches > 0 && appiumSessionService.isElementVisible(by);
        long elapsed = System.currentTimeMillis() - startedAt;
        return new TestResult(by.toString(), matches, visible, elapsed, verdict(matches, visible));
    }

    private String verdict(int matches, boolean visible) {
        if (matches == 0) return "No element on this screen matches it";
        if (matches > 1) return matches + " elements match — the driver will take the first one, which may not be yours";
        return visible ? "Matches exactly one element, on screen" : "Matches exactly one element, but it is off screen right now";
    }

    private String rootMessage(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        int cut = message.indexOf("\n");
        return cut > 0 ? message.substring(0, cut) : message;
    }

    @Data
    public static class TestResult {
        private final String resolvedAs;
        private final int matchCount;
        private final boolean visible;
        private final long elapsedMs;
        private final String verdict;
    }
}
