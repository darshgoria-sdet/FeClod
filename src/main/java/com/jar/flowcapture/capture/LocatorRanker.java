package com.jar.flowcapture.capture;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
