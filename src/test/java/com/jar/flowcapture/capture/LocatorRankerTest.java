package com.jar.flowcapture.capture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocatorRankerTest {

    // No resource-id, content-desc, or text on the target element itself — only an
    // ancestor has a resource-id. This is the common "index-based locator today"
    // case in the real repo (radioForSelection, dropDownArrow): a bare container
    // View identified only by position. CLASS_NAME_XPATH should offer a scoped
    // xpath anchored on that ancestor before falling back to raw instance(n).
    private static final String NO_OWN_IDENTITY = """
            <hierarchy>
              <android.widget.FrameLayout resource-id="com.jar:id/setup_card" bounds="[0,0][1080,2280]">
                <android.view.View class="android.view.View" bounds="[100,900][500,1050]" clickable="true" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private static final String NO_ANCESTOR_IDENTITY_EITHER = """
            <hierarchy>
              <android.widget.FrameLayout bounds="[0,0][1080,2280]">
                <android.view.View class="android.view.View" bounds="[100,900][500,1050]" clickable="true" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private Element parseAndFindTarget(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return (Element) doc.getElementsByTagName("android.view.View").item(0);
    }

    @Test
    void offersAncestorScopedXPathBeforeIndexBasedWhenAnAncestorHasAResourceId() throws Exception {
        Element target = parseAndFindTarget(NO_OWN_IDENTITY);
        List<CandidateLocator> candidates = LocatorRanker.rankCandidates(target);

        assertTrue(candidates.stream().anyMatch(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH),
                "expected a CLASS_NAME_XPATH candidate anchored on the ancestor's resource-id");

        CandidateLocator classNameXPath = candidates.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH)
                .findFirst().orElseThrow();
        assertTrue(classNameXPath.getValue().contains("com.jar:id/setup_card"));

        // Still ranked ahead of the brittle index-based fallback.
        int classNameXPathIndex = candidates.indexOf(classNameXPath);
        int indexBasedIndex = indexOfStrategy(candidates, CandidateLocator.Strategy.INDEX_BASED);
        assertTrue(classNameXPathIndex < indexBasedIndex);
    }

    @Test
    void fallsStraightToIndexBasedWhenNoAncestorHasAResourceIdEither() throws Exception {
        Element target = parseAndFindTarget(NO_ANCESTOR_IDENTITY_EITHER);
        List<CandidateLocator> candidates = LocatorRanker.rankCandidates(target);

        assertTrue(candidates.stream().noneMatch(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH));
        assertTrue(candidates.stream().anyMatch(c -> c.getStrategy() == CandidateLocator.Strategy.INDEX_BASED));
    }

    private int indexOfStrategy(List<CandidateLocator> candidates, CandidateLocator.Strategy strategy) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).getStrategy() == strategy) return i;
        }
        return -1;
    }

    /**
     * The exact locator shape QA flagged as unreliable, reproduced:
     * //*[@resource-id="...:id/nav_host_fragment"]//android.view.View
     *
     * nav_host_fragment is the app's root container, so this matches every View on screen and
     * resolves to whichever is first in the page source. It reads like a scoped locator and is
     * effectively "any view in the app" — which is why counting matters more than inspection.
     */
    private static final String ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER = """
            <hierarchy>
              <android.widget.FrameLayout resource-id="com.jar:id/nav_host_fragment" bounds="[0,0][1080,2280]">
                <android.view.View class="android.view.View" bounds="[0,0][1080,300]" />
                <android.view.View class="android.view.View" bounds="[100,900][500,1050]" clickable="true" />
                <android.view.View class="android.view.View" bounds="[0,1500][1080,1800]" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    /** Same shape, but the anchor really does scope to one element. */
    private static final String ANCHOR_SCOPES_TO_ONE = """
            <hierarchy>
              <android.widget.FrameLayout resource-id="com.jar:id/setup_card" bounds="[0,0][1080,1200]">
                <android.view.View class="android.view.View" bounds="[100,900][500,1050]" clickable="true" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private Element root(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private List<CandidateLocator> countAndFilter(String xml) throws Exception {
        Element target = parseAndFindTargetAt(xml, 1);
        return LocatorRanker.countAndFilter(root(xml), LocatorRanker.rankCandidates(target));
    }

    private Element parseAndFindTargetAt(String xml, int index) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return (Element) doc.getElementsByTagName("android.view.View").item(index);
    }

    @Test
    void dropsAnAncestorScopedXPathWhoseAnchorIsAWholeScreenContainer() throws Exception {
        List<CandidateLocator> filtered = countAndFilter(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER);

        assertTrue(filtered.stream().noneMatch(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH
                        && c.isUnique()),
                "a locator matching 3 views must not be offered as if it identified one");
    }

    @Test
    void countsHowManyElementsAnAncestorScopedXPathReallyMatches() throws Exception {
        Element target = parseAndFindTargetAt(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER, 1);
        List<CandidateLocator> counted = LocatorRanker.countAndFilter(
                root(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER), LocatorRanker.rankCandidates(target));

        CandidateLocator scoped = counted.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH)
                .findFirst().orElseThrow();
        assertEquals(3, scoped.getMatchCount());
        assertTrue(scoped.isAmbiguous());
    }

    @Test
    void keepsAnAncestorScopedXPathThatGenuinelyIdentifiesOneElement() throws Exception {
        Element target = parseAndFindTargetAt(ANCHOR_SCOPES_TO_ONE, 0);
        List<CandidateLocator> counted = LocatorRanker.countAndFilter(
                root(ANCHOR_SCOPES_TO_ONE), LocatorRanker.rankCandidates(target));

        CandidateLocator scoped = counted.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH)
                .findFirst().orElseThrow();
        assertEquals(1, scoped.getMatchCount());
        assertTrue(scoped.isUnique());
    }

    /** A screen with two identical resource-ids: common with list rows, and silently wrong today. */
    @Test
    void dropsAResourceIdThatAppearsTwiceOnTheScreen() throws Exception {
        String xml = """
                <hierarchy>
                  <android.widget.FrameLayout bounds="[0,0][1080,2280]">
                    <android.view.View class="android.view.View" resource-id="row_cta" content-desc="first" bounds="[0,0][1080,300]" />
                    <android.view.View class="android.view.View" resource-id="row_cta" content-desc="second" bounds="[0,400][1080,700]" />
                  </android.widget.FrameLayout>
                </hierarchy>
                """;
        Element target = parseAndFindTargetAt(xml, 0);
        List<CandidateLocator> counted = LocatorRanker.countAndFilter(root(xml), LocatorRanker.rankCandidates(target));

        assertTrue(counted.stream().noneMatch(c -> c.getStrategy() == CandidateLocator.Strategy.RESOURCE_ID
                        && c.isUnique()),
                "resource-id appears twice, so it cannot identify this element");
        // The content-desc still does, and should survive as the usable candidate.
        assertTrue(counted.stream().anyMatch(c -> c.getStrategy() == CandidateLocator.Strategy.CONTENT_DESC
                && c.isUnique()));
    }

    /**
     * Filtering must never leave QA with nothing. When no locator is unique the ambiguous ones
     * are still returned, carrying their counts, so the choice is informed rather than absent.
     */
    @Test
    void returnsAmbiguousCandidatesWhenNothingIsUnique() throws Exception {
        List<CandidateLocator> filtered = countAndFilter(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER);
        assertFalse(filtered.isEmpty());
    }
}
