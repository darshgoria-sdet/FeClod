package com.jar.flowcapture.reuse;

import com.jar.flowcapture.capture.CandidateLocator.Strategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocatorKeyTest {

    /**
     * The case that makes the whole feature work. In the target repo "Skip" is declared as an
     * exact-text XPath in OnboardingLocators AND as an accessibility id in HomeScreenLocators.
     * Raw string comparison sees two unrelated locators; both must reduce to one key or most
     * real duplicates stay invisible.
     */
    @Test
    void sameTextViaDifferentStrategiesSharesOneKey() {
        String viaXpath = LocatorKey.of(Strategy.EXACT_TEXT_XPATH, "//android.widget.TextView[@text=\"Skip\"]");
        String viaAccessibility = LocatorKey.of(Strategy.CONTENT_DESC, "Skip");
        String viaUiSelector = LocatorKey.of(Strategy.TEXT_CONTAINS_UIAUTOMATOR, "new UiSelector().text(\"Skip\")");

        assertEquals(viaXpath, viaAccessibility);
        assertEquals(viaXpath, viaUiSelector);
        assertEquals("TEXT:Skip", viaXpath);
    }

    @Test
    void resourceIdNormalisesAcrossXpathAndIdForms() {
        String viaId = LocatorKey.of(Strategy.RESOURCE_ID, "com.phonepe.simulator:id/pay_button");
        String viaXpath = LocatorKey.of(Strategy.EXACT_TEXT_XPATH,
                "//android.widget.Button[@resource-id=\"com.phonepe.simulator:id/pay_button\"]");
        assertEquals(viaId, viaXpath);
        assertEquals("RESOURCE_ID:com.phonepe.simulator:id/pay_button", viaId);
    }

    /** A label wrapping onto two lines must match its single-line form — the "Festival\nSavings" case. */
    @Test
    void wrappedLabelMatchesSingleLineForm() {
        assertEquals(LocatorKey.of(Strategy.CONTENT_DESC, "Festival Savings"),
                LocatorKey.of(Strategy.CONTENT_DESC, "Festival\\nSavings"));
    }

    /**
     * "//android.widget.EditText" matches every text field on screen and is declared by six
     * different fields across five files in the target repo. Treating those as the same
     * element would make the tool suggest reusing an unrelated field.
     */
    /**
     * Regression for a false positive found on a real device: LocatorRanker builds
     * CLASS_NAME_XPATH as //*[@resource-id="<ancestor id>"]//android.view.View. Parsing that
     * resource-id out treats it as the element's own identity, so every anonymous child of a
     * shared container keys to the same value — a tap on a Compose home screen matched an
     * unrelated DevLocators field through nav_host_fragment.
     */
    @Test
    void ancestorScopedXpathIsNotTreatedAsTheElementsIdentity() {
        assertNull(LocatorKey.of(Strategy.CLASS_NAME_XPATH,
                "//*[@resource-id=\"com.aso_centric.jar.staging:id/nav_host_fragment\"]//android.view.View"));
    }

    /**
     * Regression for the second false positive found live. DevLocators.profileIconHamburger is
     * declared as a ten-level path descending FROM nav_host_fragment; the id names the
     * container, not the element, so it must not key as that element's identity.
     */
    @Test
    void attributeInANonFinalStepBelongsToAnAncestor() {
        assertNull(LocatorKey.of(Strategy.EXACT_TEXT_XPATH,
                "(//android.widget.FrameLayout[@resource-id=\"com.aso_centric.jar.staging:id/nav_host_fragment\"])[2]"
                        + "/androidx.compose.ui.platform.ComposeView/android.view.View/android.view.View"));
    }

    /** The same id IS an identity when the predicate is the last step. */
    @Test
    void attributeOnTheFinalStepStillKeys() {
        assertEquals("RESOURCE_ID:com.jar:id/pay",
                LocatorKey.of(Strategy.EXACT_TEXT_XPATH, "//android.widget.Button[@resource-id=\"com.jar:id/pay\"]"));
    }

    /** A '/' inside the matched value must not be mistaken for further navigation. */
    @Test
    void slashInsideTheTextValueDoesNotLookLikeNavigation() {
        assertEquals("TEXT:Buy/Sell",
                LocatorKey.of(Strategy.EXACT_TEXT_XPATH, "//android.widget.TextView[@text=\"Buy/Sell\"]"));
    }

    @Test
    void nonIdentifyingLocatorsProduceNoKey() {
        assertNull(LocatorKey.of(Strategy.CLASS_NAME_XPATH, "//android.widget.EditText"));
        assertNull(LocatorKey.of(Strategy.INDEX_BASED,
                "new UiSelector().className(\"android.view.View\").instance(14)"));
        assertNull(LocatorKey.of(Strategy.EXACT_TEXT_XPATH, ""));
    }

    /** A partial-text match is a weaker claim than an exact one and must not be interchangeable with it. */
    @Test
    void containsTextIsNotTheSameKeyAsExactText() {
        assertNotEquals(
                LocatorKey.of(Strategy.EXACT_TEXT_XPATH, "//android.widget.TextView[@text=\"Stop Saving\"]"),
                LocatorKey.of(Strategy.TEXT_CONTAINS_UIAUTOMATOR, "new UiSelector().textContains(\"Stop Saving\")"));
    }
}
