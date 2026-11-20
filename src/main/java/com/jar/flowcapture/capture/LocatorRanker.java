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
        String rawText = rawAttr(el, "text");
        String text = rawText.trim();
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
            candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.EXACT_TEXT_XPATH, textXPath(className, rawText), className));
            String uiSelector = textUiSelector(rawText);
            if (uiSelector != null) {
                candidates.add(new CandidateLocator(
                        CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR, uiSelector, className));
            }
        }

        // Nothing on the element itself identifies it. This is the normal case for a Jetpack
        // Compose card: the tappable node is a bare android.view.View, and the only thing the
        // ranker could previously offer was an ancestor-scoped XPath that matched every View
        // under nav_host_fragment — 104 of them on the profile screen. The identity is there,
        // it is just one level down, on the label inside the card.
        //
        // Tapping that label works: a click is dispatched at the element's centre, which is
        // inside the clickable parent, so the parent's handler fires. Verified on the device
        // against this app's Offers tile.
        if (resourceId.isBlank() && contentDesc.isBlank() && text.isBlank()) {
            List<CandidateLocator> borrowed = fromIdentifyingDescendant(el);
            if (borrowed.isEmpty()) {
                // Compose does not always nest the label under the thing you tap. The coupon
                // card on this app's Coupons screen is a LEAF — zero children — while its
                // "₹100" and "Save Now" labels are siblings drawn on top of it. Structurally
                // there is nothing inside; visually the labels are plainly in the card, which
                // is how a person reads the screen, so fall back to reading it the same way.
                borrowed = fromLabelsWithinBounds(el);
            }
            candidates.addAll(borrowed);
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

            // Unique by construction, which is what makes it worth having: it guarantees every
            // element has at least one locator that identifies exactly one thing, so "nothing
            // here matches a single element" can never again be the best the tool can do.
            positionalUnderAnchor(el, className).ifPresent(candidates::add);

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

    /**
     * The exact-text locator for an element, built from its RAW text.
     *
     * Compose labels routinely carry padding the eye never sees — this app's profile tiles
     * expose "Offers \n" and "Spins \n", with a trailing space and newline. Trimming that
     * away before building the locator produced //android.widget.TextView[@text="Offers"],
     * which reads perfectly and matches nothing on the device, and the match count agreed
     * with it because the count was taken against the same trimmed value. A locator that is
     * wrong and confidently counted as unique is worse than no locator.
     *
     * So: exact match when the text really is exact — which keeps the form the repo's own
     * locators use — and normalize-space() only when the raw value has whitespace that the
     * literal cannot carry. The literal is normalized to match, because normalize-space also
     * collapses runs inside the string ("Round \nOff" becomes "Round Off").
     */
    public static String textXPath(String className, String rawText) {
        String normalized = normalizeSpace(rawText);
        return rawText.equals(normalized)
                ? "//%s[@text=\"%s\"]".formatted(className, escapeForXPath(rawText))
                : "//%s[normalize-space(@text)=\"%s\"]".formatted(className, escapeForXPath(normalized));
    }

    /**
     * UiSelector equivalent: exact text when it is exact, a contains match when it is only
     * padded at the ends.
     *
     * @return null when the label has whitespace INSIDE it that differs from the normalized
     *         form — "Send \nMoney" against textContains("Send Money") is a substring test
     *         that can never pass, and offering a locator that is dead on arrival is worse
     *         than offering one fewer. The XPath form handles these, via normalize-space().
     */
    public static String textUiSelector(String rawText) {
        String normalized = normalizeSpace(rawText);
        if (rawText.equals(normalized)) {
            return "new UiSelector().text(\"%s\")".formatted(escapeForJavaString(rawText));
        }
        if (!rawText.trim().equals(normalized)) return null;   // collapsed something inside
        return "new UiSelector().textContains(\"%s\")".formatted(escapeForJavaString(normalized));
    }

    /** What XPath's normalize-space() does: trim the ends, collapse every run inside to one space. */
    public static String normalizeSpace(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Locators built from the labels inside the element, one set per label.
     *
     * Every identifying descendant is offered, not just the first. The first is often the
     * least useful one: a coupon card's first label is "Up to", which is on all four cards,
     * while "₹100" two labels later identifies this one. Counting decides between them — the
     * ambiguous ones are dropped as soon as a unique one exists, so the extra candidates cost
     * nothing in the common case and rescue the uncommon one.
     */
    private static List<CandidateLocator> fromIdentifyingDescendant(Element el) {
        List<Element> descendants = new ArrayList<>();
        collectIdentifyingDescendants(el, 0, descendants);

        List<CandidateLocator> out = new ArrayList<>();
        descendants.forEach(descendant -> out.addAll(locatorsFor(descendant)));
        return out;
    }

    /** Every way of pointing at one labelled element, best-first. */
    private static List<CandidateLocator> locatorsFor(Element label) {
        List<CandidateLocator> out = new ArrayList<>();
        String id = attr(label, "resource-id");
        String desc = attr(label, "content-desc");
        String rawText = rawAttr(label, "text");
        String cls = attr(label, "class");

        if (!id.isBlank()) out.add(new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, id, cls));
        if (!desc.isBlank()) out.add(new CandidateLocator(CandidateLocator.Strategy.CONTENT_DESC, desc, cls));
        if (!rawText.trim().isBlank()) {
            out.add(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH, textXPath(cls, rawText), cls));
            String uiSelector = textUiSelector(rawText);
            if (uiSelector != null) {
                out.add(new CandidateLocator(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR, uiSelector, cls));
            }
        }
        return out;
    }

    /**
     * Locators from the labels that sit inside this element's rectangle, wherever they live
     * in the tree.
     *
     * Only used when the element has no identity and nothing identifying underneath it, and
     * only labels strictly smaller than the element itself are considered — an element is not
     * identified by something as big as the whole screen it sits on.
     */
    private static List<CandidateLocator> fromLabelsWithinBounds(Element el) {
        int[] bounds = rect(el);
        if (bounds == null) return List.of();
        long ownArea = area(bounds);
        if (ownArea <= 0) return List.of();

        List<Element> inside = new ArrayList<>();
        collectWhere(el.getOwnerDocument().getDocumentElement(), other -> {
            if (other == el || !identifies(other)) return false;
            int[] otherBounds = rect(other);
            return otherBounds != null && area(otherBounds) < ownArea && within(otherBounds, bounds);
        }, inside);

        List<CandidateLocator> out = new ArrayList<>();
        for (Element label : inside.subList(0, Math.min(inside.size(), MAX_IDENTIFYING_DESCENDANTS))) {
            out.addAll(locatorsFor(label));
        }
        return out;
    }

    private static boolean within(int[] inner, int[] outer) {
        return inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3];
    }

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    private static int[] rect(Element el) {
        Matcher m = BOUNDS.matcher(attr(el, "bounds"));
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }

    private static long area(int[] r) {
        return (long) (r[2] - r[0]) * (r[3] - r[1]);
    }

    /** How deep to look for a label before giving up — a card's text is one or two levels in. */
    private static final int MAX_DESCENDANT_DEPTH = 4;

    /** Enough to cover a card's labels without turning the candidate list into a page of noise. */
    private static final int MAX_IDENTIFYING_DESCENDANTS = 6;

    private static void collectIdentifyingDescendants(Element node, int depth, List<Element> out) {
        if (depth >= MAX_DESCENDANT_DEPTH || out.size() >= MAX_IDENTIFYING_DESCENDANTS) return;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                if (identifies(child)) out.add(child);
                collectIdentifyingDescendants(child, depth + 1, out);
                if (out.size() >= MAX_IDENTIFYING_DESCENDANTS) return;
            }
        }
    }

    private static boolean identifies(Element el) {
        return !attr(el, "resource-id").isBlank()
                || !attr(el, "content-desc").isBlank()
                || !attr(el, "text").isBlank();
    }

    /**
     * (//*[@resource-id="anchor"]//class)[n] — this element's position among the anchor's
     * descendants of its own class, in document order, which is the order XPath counts in.
     */
    private static java.util.Optional<CandidateLocator> positionalUnderAnchor(Element el, String className) {
        Element anchor = nearestAncestorWithId(el);
        if (anchor == null) return java.util.Optional.empty();
        List<Element> siblings = new ArrayList<>();
        collectWhere(anchor, e -> e != anchor && attr(e, "class").equals(className), siblings);
        int position = siblings.indexOf(el) + 1;
        if (position <= 0) return java.util.Optional.empty();
        String xpath = "(//*[@resource-id=\"%s\"]//%s)[%d]"
                .formatted(escapeForXPath(attr(anchor, "resource-id")), className, position);
        return java.util.Optional.of(new CandidateLocator(
                CandidateLocator.Strategy.INDEXED_UNDER_ANCHOR, xpath, className));
    }

    private static Element nearestAncestorWithId(Element el) {
        org.w3c.dom.Node parent = el.getParentNode();
        while (parent instanceof Element parentEl) {
            if (!attr(parentEl, "resource-id").isBlank()) return parentEl;
            parent = parentEl.getParentNode();
        }
        return null;
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
            case EXACT_TEXT_XPATH, TEXT_CONTAINS_UIAUTOMATOR -> countTextMatches(root, candidate);
            case CLASS_NAME_XPATH, INDEXED_UNDER_ANCHOR -> countByXPath(root, candidate.getValue());
            case INDEX_BASED -> 0;
        };
    }

    /**
     * Counts a text locator the way the device will evaluate it, rather than by rebuilding
     * the string it was made from. The two differ once normalize-space is in play: an element
     * whose text is "Offers" and one whose text is "Offers \n" are both matched by
     * normalize-space(@text)="Offers", and only a semantic count notices that.
     */
    private static int countTextMatches(Element root, CandidateLocator candidate) {
        return countWhere(root, el ->
                textLocatorMatches(candidate, attr(el, "class"), rawAttr(el, "text")));
    }

    /**
     * Whether a text locator would match an element with this class and this raw text.
     *
     * Public because the inspector counts the same candidates against the same screen from a
     * different data structure, and two implementations of "does this locator match" would
     * eventually disagree — which is exactly the failure this method exists to stop.
     */
    public static boolean textLocatorMatches(CandidateLocator candidate, String className, String rawText) {
        if (candidate.getElementClass() != null && !candidate.getElementClass().equals(className)) return false;
        if (rawText == null || rawText.isBlank()) return false;
        String target = textTargetOf(candidate);
        if (target == null) return false;
        if (!isLoose(candidate)) return rawText.equals(target);
        return candidate.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH
                ? normalizeSpace(rawText).equals(target)   // normalize-space(@text) = "…"
                : rawText.contains(target);                // UiSelector().textContains("…")
    }

    private static final Pattern EXACT_TEXT_VALUE = Pattern.compile("^//.*\\[@text=\"(.*)\"]$");
    private static final Pattern NORMALIZED_TEXT_VALUE = Pattern.compile("^//.*\\[normalize-space\\(@text\\)=\"(.*)\"]$");
    private static final Pattern UI_EXACT_VALUE = Pattern.compile("^new UiSelector\\(\\)\\.text\\(\"(.*)\"\\)$");
    private static final Pattern UI_CONTAINS_VALUE = Pattern.compile("^new UiSelector\\(\\)\\.textContains\\(\"(.*)\"\\)$");

    /** The string a text locator is looking for, read back out of the locator itself. */
    private static String textTargetOf(CandidateLocator candidate) {
        for (Pattern pattern : List.of(NORMALIZED_TEXT_VALUE, EXACT_TEXT_VALUE, UI_CONTAINS_VALUE, UI_EXACT_VALUE)) {
            Matcher m = pattern.matcher(candidate.getValue());
            if (m.matches()) return unescape(m.group(1));
        }
        return null;
    }

    /** True for the whitespace-tolerant forms: normalize-space(@text) and UiSelector().textContains(). */
    private static boolean isLoose(CandidateLocator candidate) {
        return NORMALIZED_TEXT_VALUE.matcher(candidate.getValue()).matches()
                || UI_CONTAINS_VALUE.matcher(candidate.getValue()).matches();
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    /**
     * Evaluates the XPath for real, against the page source it was built from.
     *
     * The hand-rolled approximation this replaces could only understand one shape of XPath
     * and silently returned 0 for anything else — which would have reported the positional
     * fallback as matching nothing at all.
     */
    public static int countByXPath(Element root, String expression) {
        try {
            var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.compile(expression)
                    .evaluate(root.getOwnerDocument(), javax.xml.xpath.XPathConstants.NODESET);
            return nodes.getLength();
        } catch (Exception e) {
            return 0;
        }
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
        return rawAttr(el, name).trim();
    }

    /** Untrimmed, because a locator has to say what the attribute actually holds. */
    private static String rawAttr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null ? "" : v;
    }

    private static String escapeForXPath(String text) {
        return text.replace("\"", "'");
    }

    private static String escapeForJavaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
