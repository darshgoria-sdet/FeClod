package com.jar.flowcapture.capture;

import com.jar.flowcapture.appium.AppiumSessionService;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.springframework.stereotype.Service;

/**
 * Re-resolves a step's chosen locator against the device's current page-source right
 * before a flow is written to disk — confirms the generated code will actually find
 * the element, rather than trusting whatever matched at the moment it was tapped.
 * Catches the case where an app re-render between recording and finishing silently
 * invalidated a locator that looked fine a few steps ago.
 */
@Slf4j
@Service
public class LocatorVerificationService {

    private final AppiumSessionService appiumSessionService;

    public LocatorVerificationService(AppiumSessionService appiumSessionService) {
        this.appiumSessionService = appiumSessionService;
    }

    public record VerificationResult(boolean found, int matchCount, String detail) {}

    /** Requires an active Appium session — caller decides whether to skip verification when none is connected. */
    public VerificationResult verify(CandidateLocator locator) {
        By by = toSeleniumBy(locator);
        if (by == null) {
            return new VerificationResult(false, 0, "Cannot verify an INDEX_BASED locator without a real instance index set");
        }

        try {
            int count = appiumSessionService.countMatches(by);
            if (count == 0) {
                return new VerificationResult(false, 0, "No element matched this locator on the current screen");
            }
            if (count > 1) {
                return new VerificationResult(true, count,
                        count + " elements matched — locator is ambiguous, consider a more specific one");
            }
            return new VerificationResult(true, 1, "Matched exactly one element");
        } catch (Exception e) {
            log.warn("Locator verification failed: {}", e.getMessage());
            return new VerificationResult(false, 0, "Verification error: " + e.getMessage());
        }
    }

    private By toSeleniumBy(CandidateLocator locator) {
        return LocatorBy.toBy(locator);
    }
}
