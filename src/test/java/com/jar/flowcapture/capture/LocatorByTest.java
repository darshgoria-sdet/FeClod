package com.jar.flowcapture.capture;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import static org.junit.jupiter.api.Assertions.*;

class LocatorByTest {

    /**
     * Found on a real device: AppiumBy.id("home_tab_centre_cta") timed out after 20s, while
     * //*[@resource-id="home_tab_centre_cta"] found the same element immediately. UiAutomator2
     * resolves a bare id against the app package, so a Jetpack Compose testTag — which appears
     * in the page source with no package prefix — never matches the native id strategy.
     */
    @Test
    void bareComposeTestTagResolvesByXpathNotById() {
        By by = LocatorBy.resourceIdBy("home_tab_centre_cta");
        assertEquals(By.xpath("//*[@resource-id=\"home_tab_centre_cta\"]"), by);
    }

    /** A fully-qualified id keeps the native strategy — faster, and what the repo's own locators use. */
    @Test
    void fullyQualifiedIdKeepsTheNativeStrategy() {
        assertTrue(LocatorBy.isFullyQualified("com.aso_centric.jar.staging:id/textView"));
        assertFalse(LocatorBy.isFullyQualified("home_tab_centre_cta"));
        assertNotEquals(By.xpath("//*[@resource-id=\"com.jar:id/x\"]"), LocatorBy.resourceIdBy("com.jar:id/x"));
    }

    /** INDEX_BASED carries a TODO placeholder, so it is never executable. */
    @Test
    void indexBasedIsNotExecutable() {
        assertNull(LocatorBy.toBy(new CandidateLocator(CandidateLocator.Strategy.INDEX_BASED,
                "new UiSelector().className(\"android.view.View\").instance(N)", null)));
    }

    @Test
    void firstExecutableSkipsUnresolvableCandidates() {
        By by = LocatorBy.firstExecutable(java.util.List.of(
                new CandidateLocator(CandidateLocator.Strategy.INDEX_BASED, "x", null),
                new CandidateLocator(CandidateLocator.Strategy.CONTENT_DESC, "Skip", null)));
        assertNotNull(by);
    }
}
