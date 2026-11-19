package com.jar.flowcapture.capture;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a tap point on the mirrored screenshot back to the underlying UiAutomator2
 * page-source element whose "bounds" rectangle contains that point, then ranks
 * candidate locators for it. This is the same correlation Appium Inspector performs,
 * done here instead of a bundled desktop app so it can feed codegen directly.
 */
@Slf4j
@Service
public class ClickResolverService {

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    public record ResolvedElement(Element xmlElement, List<CandidateLocator> candidates) {}

    /**
     * @param pageSourceXml raw driver.getPageSource() output
     * @param clickX,clickY click coordinates in *device* pixel space (caller must already
     *                      have scaled from the mirrored image's rendered size to device size)
     */
    public ResolvedElement resolve(String pageSourceXml, int clickX, int clickY) {
        Document doc = parse(pageSourceXml);
        List<Element> candidates = new ArrayList<>();
        collectElementsContainingPoint(doc.getDocumentElement(), clickX, clickY, candidates);

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No element found at (%d, %d) in current page source".formatted(clickX, clickY));
        }

        // Smallest-area match = the most specific element under the tap point
        // (an outer container and its button both contain the point; we want the button).
        Element best = candidates.stream()
                .min(java.util.Comparator.comparingLong(this::area))
                .orElseThrow();

        return new ResolvedElement(best, LocatorRanker.rankCandidates(best));
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

    private void collectElementsContainingPoint(Element node, int x, int y, List<Element> out) {
        String bounds = node.getAttribute("bounds");
        if (bounds != null && !bounds.isBlank() && containsPoint(bounds, x, y)) {
            out.add(node);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                collectElementsContainingPoint(child, x, y, out);
            }
        }
    }

    private boolean containsPoint(String bounds, int x, int y) {
        Matcher m = BOUNDS_PATTERN.matcher(bounds);
        if (!m.matches()) return false;
        int left = Integer.parseInt(m.group(1));
        int top = Integer.parseInt(m.group(2));
        int right = Integer.parseInt(m.group(3));
        int bottom = Integer.parseInt(m.group(4));
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private long area(Element node) {
        Matcher m = BOUNDS_PATTERN.matcher(node.getAttribute("bounds"));
        if (!m.matches()) return Long.MAX_VALUE;
        long left = Long.parseLong(m.group(1));
        long top = Long.parseLong(m.group(2));
        long right = Long.parseLong(m.group(3));
        long bottom = Long.parseLong(m.group(4));
        return (right - left) * (bottom - top);
    }
}
