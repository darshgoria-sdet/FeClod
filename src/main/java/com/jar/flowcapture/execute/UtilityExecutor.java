package com.jar.flowcapture.execute;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.CandidateLocator;
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

            // --- off-screen target: run the real search loop over EVERY alternative ---
            case SCROLL_DOWN_UNTIL_VISIBLE -> searchAndPromote(args, "element",
                    appium::scrollUntilAnyFound,
                    "Not found after " + AppiumSessionService.MAX_ATTEMPTS + " downward scrolls");
            case SCROLL_UP_UNTIL_VISIBLE -> searchAndPromote(args, "element",
                    appium::scrollUpUntilAnyFound,
                    "Not found after " + AppiumSessionService.MAX_ATTEMPTS + " upward scrolls");
            // Pinned to HomeScreenLocators.profileIcon, which the repo declares as four
            // alternative XPaths — all of them are tried, matching @AndroidFindAll's own
            // OR semantics, rather than only the highest-ranked one.
            case RETURN_TO_HOME -> searchAndPromote(args, "element",
                    appium::navigateBackUntilAnyFound,
                    "Still not on the home screen after " + AppiumSessionService.MAX_ATTEMPTS
                            + " back presses — the profile icon never appeared. The app may be on a screen "
                            + "that swallows back, or a dialog may be open.");
            case NAVIGATE_BACK_UNTIL_VISIBLE -> searchAndPromote(args, "element",
                    appium::navigateBackUntilAnyFound,
                    "Not found after " + AppiumSessionService.MAX_ATTEMPTS + " back navigations");

            // --- on-screen target ---
            case SCROLL_TILL_END_OF_SECTION -> appium.scrollTillEndOfSection(by(args, "sectionElement"));
            case HOLD_AND_DRAG -> appium.holdAndDrag(by(args, "element"));
            case DRAG_LEFT_TO_RIGHT -> appium.dragLeftToRight(by(args, "element"));
            case TAP_ELEMENT -> appium.tapElementAt(by(args, "element"));
            case WAIT_FOR_VISIBILITY, WAIT_FOR_PRESENCE -> searchAndPromote(args, "element",
                    locators -> appium.findFirstVisible(locators, 20),
                    "Did not become visible within 20s");

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

            // --- numeric ---
            // A fixed wait really waits. It is the one step whose entire purpose is elapsed
            // time, so skipping it during replay would make the replay faster than the test
            // it is checking — and the reason someone inserts one is that the app needs those
            // seconds. Logging it and moving on would verify a flow nobody is going to run.
            case WAIT_FOR_SECONDS -> pause(intArg(args, "seconds", 2));
            // The implicit wait is driver-wide state, and the studio shares this driver with
            // its own element lookups and the mirror. Setting it here would quietly change how
            // every later step behaves, so it is recorded for codegen and not applied live.
            case IMPLICIT_WAIT -> log.info(
                    "IMPLICIT_WAIT recorded but not applied — it is driver-wide state the studio also uses");

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

    /** A search that takes every alternative locator and reports which one matched. */
    private interface Search {
        By run(List<By> locators);
    }

    /**
     * Runs a search over every locator for an element argument and, on success, moves the one
     * that matched to the front of that argument's candidate list.
     *
     * Searching all the alternatives is what makes the action work: a described off-screen
     * target is a guess at how the element identifies itself — QA types "Offers" and the label
     * may be exposed as text, as a content-desc, or inside a longer string — and the fallback
     * candidates exist precisely for that. Trying only the first one discarded them.
     *
     * The promotion is the half that is easy to miss. Codegen writes the FIRST candidate into
     * the Locators class, so without reordering, QA would watch the scroll succeed and still
     * be handed a locator that does not resolve. The locator written is now the one proven.
     */
    private void searchAndPromote(UtilityArgs args, String paramName, Search search, String failureMessage) {
        var targets = args == null ? List.<UtilityArgs.ElementTarget>of() : args.elementsFor(paramName);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Missing required element argument: " + paramName);
        }
        UtilityArgs.ElementTarget target = targets.get(0);
        List<CandidateLocator> candidates = executableCandidates(target);
        List<By> locators = candidates.stream().map(LocatorBy::toBy).toList();

        By hit = search.run(locators);
        if (hit == null) {
            throw new IllegalArgumentException(failureMessage + " — tried "
                    + locators.size() + " locator(s): " + locators);
        }
        promote(target, candidates.get(locators.indexOf(hit)));
    }

    private List<CandidateLocator> executableCandidates(UtilityArgs.ElementTarget target) {
        List<CandidateLocator> candidates = (target.getCandidateLocators() == null
                ? List.<CandidateLocator>of() : target.getCandidateLocators()).stream()
                .filter(c -> LocatorBy.toBy(c) != null)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No usable locator for this element — the only candidate is index-based, which "
                            + "cannot be resolved automatically");
        }
        return candidates;
    }

    /**
     * Reorders in place so the proven locator is first. A locator reused from an existing repo
     * class is left alone: the generated call goes through that class's own field, so the
     * candidate order behind it changes nothing.
     */
    private void promote(UtilityArgs.ElementTarget target, CandidateLocator winner) {
        if (target.getReusedFrom() != null) return;
        List<CandidateLocator> reordered = new java.util.ArrayList<>();
        reordered.add(winner);
        for (CandidateLocator c : target.getCandidateLocators()) {
            if (c != winner) reordered.add(c);
        }
        target.setCandidateLocators(reordered);
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

    /**
     * Capped because this runs inside the studio's request thread: a mistyped "600" in the
     * wait field should not take the dashboard away for ten minutes. The generated test keeps
     * whatever number was recorded — the cap is on what the tool itself will sit through.
     */
    private static final int MAX_PAUSE_SECONDS = 60;

    private void pause(int seconds) {
        int capped = Math.max(0, Math.min(seconds, MAX_PAUSE_SECONDS));
        if (capped != seconds) {
            log.warn("Waiting {}s instead of the recorded {}s — the studio caps its own pauses", capped, seconds);
        }
        try {
            Thread.sleep(capped * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Wait interrupted", e);
        }
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
