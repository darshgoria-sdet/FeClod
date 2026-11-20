package com.jar.flowcapture.capture;

import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/**
 * Converts a ranked CandidateLocator into a Selenium By. Shared by locator verification
 * and by live execution of catalogued utility methods, so both resolve a locator exactly
 * the same way — a locator that verifies but executes differently would be worse than
 * no verification at all.
 */
public final class LocatorBy {

    private LocatorBy() {}

    /**
     * @return null for INDEX_BASED, which LocatorRanker always emits with a
     *         "/* TODO: set correct instance index *\/" placeholder — not an executable
     *         selector until a human fills it in.
     */
    public static By toBy(CandidateLocator locator) {
        if (locator == null) return null;
        return switch (locator.getStrategy()) {
            case RESOURCE_ID -> resourceIdBy(locator.getValue());
            case CONTENT_DESC -> AppiumBy.accessibilityId(locator.getValue());
            case EXACT_TEXT_XPATH, CLASS_NAME_XPATH, INDEXED_UNDER_ANCHOR -> By.xpath(locator.getValue());
            case TEXT_CONTAINS_UIAUTOMATOR -> AppiumBy.androidUIAutomator(locator.getValue());
            case INDEX_BASED -> null;
        };
    }

    /**
     * UiAutomator2's "id" strategy resolves a BARE id against the app package, turning
     * "home_tab_centre_cta" into "<pkg>:id/home_tab_centre_cta". Jetpack Compose testTags
     * appear in the page source as a resource-id with NO package prefix, so they never match
     * that way. Verified on a real device: AppiumBy.id("home_tab_centre_cta") timed out after
     * 20s while //*[@resource-id="home_tab_centre_cta"] found the same element immediately.
     *
     * A fully-qualified id (containing ":id/") still uses the native strategy, which is faster
     * than an XPath scan and is what the target repo's own hand-written locators use.
     */
    public static By resourceIdBy(String value) {
        if (value == null) return null;
        return isFullyQualified(value)
                ? AppiumBy.id(value)
                : By.xpath("//*[@resource-id=\"" + value.replace("\"", "\\\"") + "\"]");
    }

    /** True for ids like "com.jar.app:id/pay_button"; false for a bare Compose testTag. */
    public static boolean isFullyQualified(String resourceId) {
        return resourceId != null && resourceId.contains(":id/");
    }

    /** First executable locator in a ranked list, skipping ones that cannot be resolved (INDEX_BASED). */
    public static By firstExecutable(java.util.List<CandidateLocator> candidates) {
        if (candidates == null) return null;
        for (CandidateLocator c : candidates) {
            By by = toBy(c);
            if (by != null) return by;
        }
        return null;
    }
}
