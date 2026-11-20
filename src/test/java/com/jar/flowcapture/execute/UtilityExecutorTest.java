package com.jar.flowcapture.execute;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import com.jar.flowcapture.config.FlowCaptureProperties;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtilityExecutorTest {

    /** Lets a test say which single locator exists on the "device". */
    private static class FakeDevice extends AppiumSessionService {
        List<By> lastSearched = List.of();
        String matchesContaining;

        FakeDevice() {
            super(new FlowCaptureProperties());
        }

        @Override
        public synchronized By scrollUntilAnyFound(List<By> locators) {
            lastSearched = locators;
            return locators.stream()
                    .filter(by -> matchesContaining != null && by.toString().contains(matchesContaining))
                    .findFirst().orElse(null);
        }
    }

    private final FakeDevice device = new FakeDevice();
    private final UtilityExecutor executor = new UtilityExecutor(device);
    private final MethodCatalogService catalog = new MethodCatalogService();

    /** The candidate shape /describe-element builds for a typed label, in its real order. */
    private UtilityArgs describedAs(String label) {
        List<CandidateLocator> candidates = new ArrayList<>(List.of(
                new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH, "//*[@text=\"" + label + "\"]", null),
                new CandidateLocator(CandidateLocator.Strategy.CONTENT_DESC, label, null),
                new CandidateLocator(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR,
                        "new UiSelector().textContains(\"" + label + "\")", null)));
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(new UtilityArgs.ElementTarget("element", "offersLink", candidates, null));
        return args;
    }

    private ReusableMethod scrollUntilVisible() {
        return catalog.byKind(ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE.name()).orElseThrow();
    }

    /**
     * The reported failure: QA described an off-screen element by its visible text, the exact
     * text XPath did not match, and the search gave up — without ever trying the textContains
     * fallback that /describe-element had built for exactly this case.
     */
    @Test
    void scrollSearchTriesEveryDescribedLocatorNotJustTheFirst() {
        device.matchesContaining = "textContains";   // only the third candidate exists

        executor.execute(scrollUntilVisible(), describedAs("Offers"));

        assertEquals(3, device.lastSearched.size(),
                "all three described locators must be searched, got: " + device.lastSearched);
    }

    /**
     * Codegen writes the FIRST candidate, so a search that succeeds on a fallback must reorder
     * them — otherwise the scroll works on screen and the generated locator still does not.
     */
    @Test
    void theLocatorThatMatchedBecomesTheOneThatGetsGenerated() {
        device.matchesContaining = "textContains";
        UtilityArgs args = describedAs("Offers");

        executor.execute(scrollUntilVisible(), args);

        CandidateLocator first = args.elementsFor("element").get(0).getCandidateLocators().get(0);
        assertEquals(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR, first.getStrategy());
        assertTrue(first.getValue().contains("textContains"));
    }

    /** Nothing is lost by promotion — the other candidates stay available behind the winner. */
    @Test
    void promotionReordersRatherThanDiscards() {
        device.matchesContaining = "textContains";
        UtilityArgs args = describedAs("Offers");

        executor.execute(scrollUntilVisible(), args);

        assertEquals(3, args.elementsFor("element").get(0).getCandidateLocators().size());
    }

    /** A failure should say how hard it looked, so QA can tell "wrong text" from "wrong screen". */
    @Test
    void aFailureSaysHowManyLocatorsWereTried() {
        device.matchesContaining = null;   // nothing matches

        var error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(scrollUntilVisible(), describedAs("Offers")));

        assertTrue(error.getMessage().contains("3 locator(s)"), error.getMessage());
        assertTrue(error.getMessage().contains("downward scrolls"), error.getMessage());
    }

    @Test
    void anElementArgumentWithOnlyAnIndexBasedLocatorIsRejectedClearly() {
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(new UtilityArgs.ElementTarget("element", "row",
                List.of(new CandidateLocator(CandidateLocator.Strategy.INDEX_BASED,
                        "new UiSelector().instance(2)", null)), null));

        var error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(scrollUntilVisible(), args));

        assertTrue(error.getMessage().contains("index-based"), error.getMessage());
    }
}
