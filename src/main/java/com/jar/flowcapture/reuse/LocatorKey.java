package com.jar.flowcapture.reuse;

import com.jar.flowcapture.capture.CandidateLocator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a locator to a semantic key so the same element written two different ways is
 * recognised as the same element.
 *
 * This normalisation is the whole point of the reuse feature. In the target repo "Skip" is
 * declared as an exact-text XPath in OnboardingLocators AND as an accessibility id in
 * HomeScreenLocators; raw string comparison sees two unrelated locators, while both reduce
 * to TEXT:Skip here. Measured across the repo: 452 locator values, only 377 distinct, with
 * 74 redundant declarations — most of which only surface once strategies are compared
 * semantically rather than literally.
 */
public final class LocatorKey {

    private LocatorKey() {}

    private static final Pattern XPATH_TEXT =
            Pattern.compile("@text\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern XPATH_CONTAINS_TEXT =
            Pattern.compile("contains\\s*\\(\\s*@text\\s*,\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern XPATH_RESOURCE_ID =
            Pattern.compile("@resource-id\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern XPATH_CONTENT_DESC =
            Pattern.compile("@content-desc\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern UIA_TEXT =
            Pattern.compile("\\.text\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern UIA_TEXT_CONTAINS =
            Pattern.compile("\\.textContains\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern UIA_RESOURCE_ID =
            Pattern.compile("\\.resourceId\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern UIA_DESC =
            Pattern.compile("\\.description\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    /**
     * @return a key like "TEXT:Skip" or "RESOURCE_ID:com.x:id/y", or null when the locator
     *         carries no stable semantic identity (index-based, or a bare class-name XPath).
     *         A null key must never match anything — two different index-based locators
     *         pointing at different elements would otherwise collide.
     */
    public static String of(CandidateLocator.Strategy strategy, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return null;
        String value = unescape(rawValue).trim();

        switch (strategy) {
            case RESOURCE_ID -> { return "RESOURCE_ID:" + value; }
            case CONTENT_DESC -> { return "TEXT:" + normaliseText(value); }
            // Both describe an element that has no identifying attribute of its own.
            // CLASS_NAME_XPATH is built by LocatorRanker as
            // //*[@resource-id="<nearest ANCESTOR id>"]//android.view.View — the resource-id in
            // it belongs to a container, not to the element, so parsing it out would key every
            // anonymous child of that container to the same thing. Caught live: a tap on a
            // Compose screen matched DevLocators via the shared nav_host_fragment id.
            case INDEX_BASED, CLASS_NAME_XPATH -> { return null; }
            default -> { /* fall through to structural parsing below */ }
        }

        String found = finalStepGroup(XPATH_RESOURCE_ID, value);
        if (found != null) return "RESOURCE_ID:" + found;
        found = firstGroup(UIA_RESOURCE_ID, value);
        if (found != null) return "RESOURCE_ID:" + found;

        found = finalStepGroup(XPATH_TEXT, value);
        if (found != null) return "TEXT:" + normaliseText(found);
        found = firstGroup(UIA_TEXT, value);
        if (found != null) return "TEXT:" + normaliseText(found);

        found = finalStepGroup(XPATH_CONTENT_DESC, value);
        if (found != null) return "TEXT:" + normaliseText(found);
        found = firstGroup(UIA_DESC, value);
        if (found != null) return "TEXT:" + normaliseText(found);

        // "contains" / "textContains" are a weaker claim than an exact match, so they get
        // their own namespace — a partial-text locator must not be treated as interchangeable
        // with an exact-text one for a longer label that merely contains it.
        found = firstGroup(XPATH_CONTAINS_TEXT, value);
        if (found != null) return "TEXT_CONTAINS:" + normaliseText(found);
        found = firstGroup(UIA_TEXT_CONTAINS, value);
        if (found != null) return "TEXT_CONTAINS:" + normaliseText(found);

        // Nothing identifying — e.g. "//android.widget.EditText", which matches every text
        // field on the screen. Six fields in the repo share exactly that value across five
        // files; treating them as "the same element" would be wrong, so return null.
        return null;
    }

    public static String of(CandidateLocator locator) {
        return locator == null ? null : of(locator.getStrategy(), locator.getValue());
    }

    /** Collapses whitespace and newlines so a label that wraps across two lines matches its single-line form. */
    private static String normaliseText(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Turns the Java source form (\" and \\n) back into the real characters before comparing. */
    private static String unescape(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    private static String firstGroup(Pattern pattern, String value) {
        Matcher m = pattern.matcher(value);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Like firstGroup, but only when the predicate sits on the LAST step of the XPath — i.e.
     * nothing navigates further down after it. An attribute in a non-final step belongs to an
     * ancestor, not to the element being located, so keying on it makes every descendant of a
     * shared container look like the same element.
     *
     * Found live, twice: a tap resolved to //*[@resource-id="...nav_host_fragment"]//android.view.View,
     * and the repo's own DevLocators.profileIconHamburger is a ten-level path descending from
     * that same nav_host_fragment. Before this check the two matched each other.
     *
     * The match region covers the closing quote of the attribute value, so a '/' inside the
     * value itself does not count as further navigation.
     */
    private static String finalStepGroup(Pattern pattern, String value) {
        Matcher m = pattern.matcher(value);
        if (!m.find()) return null;
        String remainder = value.substring(m.end());
        return remainder.contains("/") ? null : m.group(1);
    }
}
