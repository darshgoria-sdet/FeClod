package com.jar.flowcapture.capture;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds candidate locators for an element in priority order matching the strategy
 * already implicit across Jar_Frontend_Automation's *Locators.java files: prefer
 * resource-id and content-desc (stable across copy/layout changes) over exact-text
 * xpath (breaks on copy changes) over index-based UiSelector().instance(n) (breaks
 * on any layout change — flagged brittle so generated code carries a warning comment).
 */
public class LocatorRanker {

    public static List<CandidateLocator> rankCandidates(Element el) {
        List<CandidateLocator> candidates = new ArrayList<>();

        String resourceId = attr(el, "resource-id");
        String contentDesc = attr(el, "content-desc");
        String text = attr(el, "text");
        String className = attr(el, "class");

        if (!resourceId.isBlank()) {
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.RESOURCE_ID, resourceId, className));
        }

        if (!contentDesc.isBlank()) {
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.CONTENT_DESC, contentDesc, className));
        }

        if (!text.isBlank()) {
            String xpath = "//%s[@text=\"%s\"]".formatted(className, escapeForXPath(text));
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.EXACT_TEXT_XPATH, xpath, className));

            String uiAutomator = "new UiSelector().text(\"%s\")".formatted(escapeForJavaString(text));
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR, uiAutomator, className));
        }

        if (!className.isBlank()) {
            // Scope to the nearest ancestor that has its own resource-id, e.g.
            // "//*[@resource-id='...card']//android.view.View" — more stable than a
            // screen-wide instance(n) because it survives other parts of the screen
            // changing, as long as the anchor ancestor and local structure don't move.
            String ancestorResourceId = nearestAncestorResourceId(el);
            if (ancestorResourceId != null) {
                String xpath = "//*[@resource-id=\"%s\"]//%s".formatted(ancestorResourceId, className);
                candidates.add(new CandidateLocator(
                        CandidateLocator.Strategy.CLASS_NAME_XPATH, xpath, className));
            }

            // Last resort: index-based, matching the pattern already seen in
            // MonthlySavingsLocators (radioForSelection, dropDownArrow) — only offered
            // when nothing more stable exists, and always flagged brittle downstream.
            String uiAutomator = "new UiSelector().className(\"%s\").instance(N) /* TODO: set correct instance index */"
                    .formatted(className);
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.INDEX_BASED, uiAutomator, className));
        }

        candidates.sort(Comparator.comparingInt(c -> c.getStrategy().priority));
        return candidates;
    }

    private static final Pattern ANCESTOR_SCOPED =
            Pattern.compile("^//\\*\\[@resource-id=\"(.*)\"]//(.+)$");

    /**
     * Counts each candidate against the screen it was captured from and drops the ones that
     * do not identify a single element.
     *
     * This is the difference between a locator that looks plausible and one that works. The
     * ancestor-scoped form in particular reads fine — "//*[@resource-id='...card']//android.view.View"
     * — but when the nearest ancestor carrying an id happens to be a screen-level container
     * such as nav_host_fragment, it matches every View in the app and silently resolves to the
     * first one. Counting catches that; reading the XPath does not.
     *
     * Counting happens here, against the page source already in memory, rather than by asking
     * the device once per candidate: same answer, no extra round-trips, and it works for
     * candidates that would never be chosen anyway.
     */
    public static List<CandidateLocator> countAndFilter(Element root, List<CandidateLocator> candidates) {
        List<CandidateLocator> counted = new ArrayList<>();
        for (CandidateLocator candidate : candidates) {
            if (candidate.getStrategy() == CandidateLocator.Strategy.INDEX_BASED) {
                counted.add(candidate);   // never counted: it carries a placeholder index
                continue;
            }
            candidate.setMatchCount(countMatches(root, candidate));
            counted.add(candidate);
        }

        List<CandidateLocator> unique = counted.stream().filter(CandidateLocator::isUnique).toList();
        if (!unique.isEmpty()) {
            // Keep the index-based fallback at the end so a human still has an escape hatch.
            List<CandidateLocator> result = new ArrayList<>(unique);
            counted.stream()
                    .filter(c -> c.getStrategy() == CandidateLocator.Strategy.INDEX_BASED)
                    .forEach(result::add);
            return result;
        }
        // Nothing resolved uniquely. Returning the ambiguous ones anyway is right: QA can see
        // the match counts and decide, which beats offering nothing but an index placeholder.
        return counted;
    }

    static int countMatches(Element root, CandidateLocator candidate) {
        return switch (candidate.getStrategy()) {
            case RESOURCE_ID -> countWhere(root, el -> attr(el, "resource-id").equals(candidate.getValue()));
            case CONTENT_DESC -> countWhere(root, el -> attr(el, "content-desc").equals(candidate.getValue()));
            case EXACT_TEXT_XPATH, TEXT_CONTAINS_UIAUTOMATOR -> countWhere(root,
                    el -> attr(el, "class").equals(candidate.getElementClass())
                            && !attr(el, "text").isBlank()
                            && candidate.getValue().contains(escapeForXPath(attr(el, "text")))
                            && textMatches(candidate, attr(el, "text")));
            case CLASS_NAME_XPATH -> countAncestorScoped(root, candidate.getValue());
            case INDEX_BASED -> 0;
        };
    }

    /** The two text strategies embed the text differently, so compare against the raw text. */
    private static boolean textMatches(CandidateLocator candidate, String text) {
        return candidate.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH
                ? candidate.getValue().equals("//%s[@text=\"%s\"]".formatted(candidate.getElementClass(), escapeForXPath(text)))
                : candidate.getValue().equals("new UiSelector().text(\"%s\")".formatted(escapeForJavaString(text)));
    }

    /** Counts descendants of the anchor element that carry the given class — what the XPath means. */
    private static int countAncestorScoped(Element root, String xpath) {
        Matcher m = ANCESTOR_SCOPED.matcher(xpath);
        if (!m.matches()) return 0;
        String anchorId = m.group(1);
        String className = m.group(2);

        int total = 0;
        List<Element> anchors = new ArrayList<>();
        collectWhere(root, el -> attr(el, "resource-id").equals(anchorId), anchors);
        for (Element anchor : anchors) {
            total += countWhere(anchor, el -> el != anchor && attr(el, "class").equals(className));
        }
        return total;
    }

    private interface ElementTest { boolean test(Element el); }

    private static int countWhere(Element root, ElementTest test) {
        List<Element> hits = new ArrayList<>();
        collectWhere(root, test, hits);
        return hits.size();
    }

    private static void collectWhere(Element node, ElementTest test, List<Element> out) {
        if (test.test(node)) out.add(node);
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childEl) collectWhere(childEl, test, out);
        }
    }

    private static String nearestAncestorResourceId(Element el) {
        org.w3c.dom.Node parent = el.getParentNode();
        while (parent instanceof Element parentEl) {
            String id = attr(parentEl, "resource-id");
            if (!id.isBlank()) return id;
            parent = parentEl.getParentNode();
        }
        return null;
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null ? "" : v.trim();
    }

    private static String escapeForXPath(String text) {
        return text.replace("\"", "'");
    }

    private static String escapeForJavaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
