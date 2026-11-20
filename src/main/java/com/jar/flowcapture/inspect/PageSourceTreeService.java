package com.jar.flowcapture.inspect;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.LocatorRanker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one page-source capture into the whole element tree, the way Appium Inspector
 * shows it: every node, every attribute, the absolute XPath, and — the part Inspector
 * does not give you — each node's candidate locators already counted against the screen,
 * so a human can see at a glance which of them identifies exactly one element.
 *
 * Counting is done from pre-built indexes rather than by re-walking the tree per candidate.
 * A screen with 400 nodes yields ~1500 candidates, and LocatorRanker's own counting is a
 * full tree scan each time; that is fine for the handful of candidates belonging to one
 * tapped element but quadratic when every node is counted, which is what this view does.
 */
@Slf4j
@Service
public class PageSourceTreeService {

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    /** Above this many nodes, per-node locator candidates are skipped to keep the response quick. */
    private static final int CANDIDATE_BUDGET_NODES = 2500;

    public record SourceNode(
            int id,
            int depth,
            String tag,
            String className,
            String resourceId,
            String contentDesc,
            String text,
            String bounds,
            int[] rect,
            String xpath,
            Map<String, String> attributes,
            List<CandidateLocator> candidates,
            List<SourceNode> children) {}

    public record Tree(SourceNode root, int nodeCount, Integer selectedId, boolean candidatesComputed) {}

    /**
     * @param x,y optional tap point in device pixels; when given, the smallest node whose
     *            bounds contain it is reported as {@code selectedId} — the same
     *            smallest-area rule the tap-to-record path uses, so the node highlighted
     *            here is the node a tap would hit.
     */
    public Tree build(String pageSourceXml, Integer x, Integer y) {
        Document doc = parse(pageSourceXml);
        Element docRoot = doc.getDocumentElement();

        Index index = new Index();
        index.collect(docRoot);
        boolean withCandidates = index.nodeCount <= CANDIDATE_BUDGET_NODES;

        Counter counter = new Counter();
        SourceNode root = toNode(docRoot, "", 0, counter, index, withCandidates);

        Integer selected = (x == null || y == null) ? null : smallestContaining(root, x, y);
        return new Tree(root, counter.next, selected, withCandidates);
    }

    private static final class Counter { int next = 0; }

    private SourceNode toNode(Element el, String parentXpath, int depth,
                              Counter counter, Index index, boolean withCandidates) {
        int id = counter.next++;
        String tag = el.getTagName();
        String xpath = parentXpath.isEmpty() ? "/" + tag : parentXpath + "/" + tag + "[" + siblingPosition(el) + "]";

        List<CandidateLocator> candidates = withCandidates ? countedCandidates(el, index) : List.of();

        List<SourceNode> children = new ArrayList<>();
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                children.add(toNode(child, xpath, depth + 1, counter, index, withCandidates));
            }
        }

        return new SourceNode(
                id, depth, tag,
                attr(el, "class"), attr(el, "resource-id"), attr(el, "content-desc"), attr(el, "text"),
                attr(el, "bounds"), rect(attr(el, "bounds")),
                xpath, allAttributes(el), candidates, children);
    }

    /** 1-based position among siblings carrying the same tag, which is what an absolute XPath index means. */
    private int siblingPosition(Element el) {
        int position = 1;
        Node sibling = el.getPreviousSibling();
        while (sibling != null) {
            if (sibling instanceof Element e && e.getTagName().equals(el.getTagName())) position++;
            sibling = sibling.getPreviousSibling();
        }
        return position;
    }

    private Map<String, String> allAttributes(Element el) {
        Map<String, String> out = new LinkedHashMap<>();
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            out.put(a.getNodeName(), a.getNodeValue() == null ? "" : a.getNodeValue());
        }
        return out;
    }

    /**
     * Every candidate LocatorRanker would offer for this node, each carrying how many
     * elements on this screen it actually matches. Ambiguous ones are kept rather than
     * filtered: the whole point of this view is that a human decides, and "matches 14
     * elements" is the information that decision needs.
     */
    private List<CandidateLocator> countedCandidates(Element el, Index index) {
        List<CandidateLocator> candidates = LocatorRanker.rankCandidates(el);
        for (CandidateLocator candidate : candidates) {
            candidate.setMatchCount(index.count(candidate));
        }
        return candidates;
    }

    /** Pre-built counts, so counting a candidate is a map lookup instead of a tree walk. */
    private static final class Index {
        private final Map<String, Integer> byResourceId = new HashMap<>();
        private final Map<String, Integer> byContentDesc = new HashMap<>();
        /** (class, raw text) for every element carrying text — counted by locator semantics, not by key equality. */
        private final List<String[]> texts = new ArrayList<>();
        private final Map<String, List<Element>> anchorsById = new HashMap<>();
        private final Map<String, Integer> ancestorScoped = new HashMap<>();
        private int nodeCount;
        /** Kept so a candidate can be counted by evaluating its real XPath, not by guessing its shape. */
        private Element rootElement;

        void collect(Element node) {
            if (rootElement == null) rootElement = node;
            nodeCount++;
            String resourceId = attrOf(node, "resource-id");
            String contentDesc = attrOf(node, "content-desc");
            String rawText = rawAttrOf(node, "text");
            String className = attrOf(node, "class");

            if (!resourceId.isBlank()) {
                byResourceId.merge(resourceId, 1, Integer::sum);
                anchorsById.computeIfAbsent(resourceId, k -> new ArrayList<>()).add(node);
            }
            if (!contentDesc.isBlank()) byContentDesc.merge(contentDesc, 1, Integer::sum);
            if (!rawText.isBlank()) texts.add(new String[]{className, rawText});

            NodeList kids = node.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element child) collect(child);
            }
        }

        int count(CandidateLocator candidate) {
            return switch (candidate.getStrategy()) {
                case RESOURCE_ID -> byResourceId.getOrDefault(candidate.getValue(), 0);
                case CONTENT_DESC -> byContentDesc.getOrDefault(candidate.getValue(), 0);
                // Asked of the ranker rather than answered here: a text locator may be an
                // exact match or a whitespace-tolerant one, and only the code that built it
                // knows which. Counting it the other way is how "matches exactly 1 element"
                // was once reported for a locator the device matched zero times.
                case EXACT_TEXT_XPATH, TEXT_CONTAINS_UIAUTOMATOR -> textCount(candidate);
                case CLASS_NAME_XPATH, INDEXED_UNDER_ANCHOR ->
                        LocatorRanker.countByXPath(rootElement, candidate.getValue());
                case INDEX_BASED -> 0;   // carries a placeholder index; nothing to count
            };
        }

        private int textCount(CandidateLocator candidate) {
            int total = 0;
            for (String[] entry : texts) {
                if (LocatorRanker.textLocatorMatches(candidate, entry[0], entry[1])) total++;
            }
            return total;
        }

        private static final Pattern ANCESTOR_SCOPED = Pattern.compile("^//\\*\\[@resource-id=\"(.*)\"]//(.+)$");

        private int ancestorScopedCount(String xpath) {
            Matcher m = ANCESTOR_SCOPED.matcher(xpath);
            if (!m.matches()) return 0;
            String key = m.group(1) + "\u0000" + m.group(2);
            return ancestorScoped.computeIfAbsent(key, k -> {
                int total = 0;
                for (Element anchor : anchorsById.getOrDefault(m.group(1), List.of())) {
                    total += countDescendantsWithClass(anchor, m.group(2));
                }
                return total;
            });
        }

        private int countDescendantsWithClass(Element anchor, String className) {
            int total = 0;
            NodeList kids = anchor.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element child) {
                    if (attrOf(child, "class").equals(className)) total++;
                    total += countDescendantsWithClass(child, className);
                }
            }
            return total;
        }

        private static String attrOf(Element el, String name) {
            return rawAttrOf(el, name).trim();
        }

        private static String rawAttrOf(Element el, String name) {
            String v = el.getAttribute(name);
            return v == null ? "" : v;
        }
    }

    /** Smallest-area node containing the point — the one a tap at that point resolves to. */
    private Integer smallestContaining(SourceNode node, int x, int y) {
        Integer best = null;
        long bestArea = Long.MAX_VALUE;
        for (SourceNode candidate : flatten(node, new ArrayList<>())) {
            int[] r = candidate.rect();
            if (r == null) continue;
            if (x < r[0] || x > r[2] || y < r[1] || y > r[3]) continue;
            long area = (long) (r[2] - r[0]) * (r[3] - r[1]);
            if (area < bestArea) {
                bestArea = area;
                best = candidate.id();
            }
        }
        return best;
    }

    private List<SourceNode> flatten(SourceNode node, List<SourceNode> out) {
        out.add(node);
        node.children().forEach(child -> flatten(child, out));
        return out;
    }

    private int[] rect(String bounds) {
        if (bounds == null) return null;
        Matcher m = BOUNDS.matcher(bounds);
        if (!m.matches()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null ? "" : v.trim();
    }

    private Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse page source XML", e);
        }
    }
}
