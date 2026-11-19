package com.jar.flowcapture.execute;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.LocatorBy;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.catalog.ReusableMethod;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs a catalogued utility method against the live device, so clicking the button in the
 * dashboard does the same thing the generated test will later do when it calls that method.
 *
 * Deliberate trade-off worth knowing: this re-implements each method's behaviour rather
 * than invoking utilsPackage directly (that code lives in a different repo and depends on
 * its own static DriverFactory). The loops and gesture geometry are copied from the real
 * implementations and pinned by tests, but a change to ActionUtils will not automatically
 * show up here — this is the one place where the two can drift apart.
 */
@Slf4j
@Service
public class UtilityExecutor {

    private final AppiumSessionService appium;

    public UtilityExecutor(AppiumSessionService appium) {
        this.appium = appium;
    }

    /**
     * @throws IllegalArgumentException when a required argument is missing or an element
     *         cannot be found — surfaced to the dashboard's error banner, with the step
     *         NOT recorded, mirroring the real methods returning false rather than
     *         generating a call that would not work.
     */
    public void execute(ReusableMethod method, UtilityArgs args) {
        switch (method.getKind()) {
            // --- parameterless ---
            case NAVIGATE_BACK -> appium.pressBack();
            case CLOSE_KEYBOARD -> appium.closeKeyboard();
            case OPEN_APP -> appium.openApp();
            case CLOSE_APP -> appium.closeApp();
            case SWITCH_TO_WEBVIEW -> appium.switchToWebView();
            case SWITCH_TO_NATIVE -> appium.switchToNative();
            case SCROLL_DOWN -> appium.scrollDown();
            case SCROLL_UP -> appium.scrollUp();

            // --- off-screen target: run the real search loop ---
            case SCROLL_DOWN_UNTIL_VISIBLE -> requireFound(
                    appium.scrollUntilElementFound(by(args, "element")),
                    "Element not found after " + AppiumSessionService.MAX_ATTEMPTS + " downward scrolls");
            case SCROLL_UP_UNTIL_VISIBLE -> requireFound(
                    appium.scrollUpUntilElementFound(by(args, "element")),
                    "Element not found after " + AppiumSessionService.MAX_ATTEMPTS + " upward scrolls");
            // Pinned to HomeScreenLocators.profileIcon, which the repo declares as four
            // alternative XPaths — all of them are tried, matching @AndroidFindAll's own
            // OR semantics, rather than only the highest-ranked one.
            case RETURN_TO_HOME -> requireFound(
                    appium.navigateBackUntilAnyFound(everyLocatorFor(args, "element")),
                    "Still not on the home screen after " + AppiumSessionService.MAX_ATTEMPTS
                            + " back presses — the profile icon never appeared. The app may be on a screen "
                            + "that swallows back, or a dialog may be open.");
            case NAVIGATE_BACK_UNTIL_VISIBLE -> requireFound(
                    appium.navigateBackUntilElementFound(by(args, "element")),
                    "Element not found after " + AppiumSessionService.MAX_ATTEMPTS + " back navigations");

            // --- on-screen target ---
            case SCROLL_TILL_END_OF_SECTION -> appium.scrollTillEndOfSection(by(args, "sectionElement"));
            case HOLD_AND_DRAG -> appium.holdAndDrag(by(args, "element"));
            case DRAG_LEFT_TO_RIGHT -> appium.dragLeftToRight(by(args, "element"));
            case TAP_ELEMENT -> appium.tapElementAt(by(args, "element"));
            case WAIT_FOR_VISIBILITY, WAIT_FOR_PRESENCE -> requireFound(
                    appium.waitForVisible(by(args, "element"), 20),
                    "Element did not become visible within 20s");

            // --- several elements ---
            case WAIT_FOR_ANY_VISIBLE -> requireFound(
                    appium.waitForAnyVisible(allBy(args, "elements"), intArg(args, "timeoutSeconds", 20)),
                    "None of the given elements became visible within the timeout");
            case ASSERT_DOM_LOADED -> {
                List<By> all = allBy(args, "elements");
                for (By by : all) {
                    requireFound(appium.isElementVisible(by), "Element not present on the current screen: " + by);
                }
            }
            case DRAG_COIN_TO_END -> appium.dragCoinToEnd(by(args, "coinElement"), by(args, "goldBarElement"));

            // --- numeric: nothing to do on the device, the wait only matters at playback ---
            case WAIT_FOR_SECONDS, IMPLICIT_WAIT -> log.info(
                    "{} recorded with no device action — it only affects timing in the generated test",
                    method.getKind());

            // --- mixed ---
            case CLICK_AND_VERIFY -> {
                appium.tapElementAt(by(args, "toClick"));
                requireFound(appium.waitForVisible(by(args, "expectedAfter"), 20),
                        "Clicked successfully, but the expected element did not appear within 20s");
            }

            // --- not executable here ---
            case COMPLETE_PHONEPE_PAYMENT, GO_TO_HOME, CHANGE_PAYMENT_STATUS -> throw new IllegalArgumentException(
                    method.getFullyQualifiedCall() + " cannot be driven from the dashboard — it depends on the "
                            + "target repo's own config and payment simulator wiring. Perform it by hand on the "
                            + "phone, then record it as a step so the generated code still calls the real method.");
        }
    }

    /** True for methods the dashboard can both record AND perform on the device. */
    public boolean isExecutable(ReusableMethod method) {
        return !method.isManualOnDevice();
    }

    private void requireFound(boolean found, String message) {
        if (!found) throw new IllegalArgumentException(message);
    }

    private By by(UtilityArgs args, String paramName) {
        List<By> all = allBy(args, paramName);
        return all.get(0);
    }

    /**
     * Every locator on the element argument, not just the best one — for a repo field
     * declared with @AndroidFindAll, any of them matching means the element is there.
     */
    private List<By> everyLocatorFor(UtilityArgs args, String paramName) {
        var targets = args == null ? List.<UtilityArgs.ElementTarget>of() : args.elementsFor(paramName);
        List<By> all = targets.stream()
                .flatMap(t -> (t.getCandidateLocators() == null ? List.<com.jar.flowcapture.capture.CandidateLocator>of()
                        : t.getCandidateLocators()).stream())
                .map(LocatorBy::toBy)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (all.isEmpty()) {
            throw new IllegalArgumentException("No usable locator for element argument: " + paramName);
        }
        return all;
    }

    private List<By> allBy(UtilityArgs args, String paramName) {
        var targets = args == null ? List.<UtilityArgs.ElementTarget>of() : args.elementsFor(paramName);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Missing required element argument: " + paramName);
        }
        List<By> result = targets.stream().map(t -> LocatorBy.firstExecutable(t.getCandidateLocators())).toList();
        if (result.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "One of the chosen elements only has an index-based locator, which cannot be resolved "
                            + "automatically — pick an element with a resource-id, content-description or text");
        }
        return result;
    }

    private int intArg(UtilityArgs args, String paramName, int fallback) {
        String raw = args == null ? null : args.scalarFor(paramName);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(paramName + " must be a whole number, got: " + raw);
        }
    }
}
