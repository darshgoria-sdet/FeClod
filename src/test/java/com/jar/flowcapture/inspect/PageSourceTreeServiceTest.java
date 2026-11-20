package com.jar.flowcapture.inspect;

import com.jar.flowcapture.capture.CandidateLocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageSourceTreeServiceTest {

    /**
     * Deliberately contains the two shapes that matter: a uniquely identified button, and a
     * list whose rows share a class and sit under one screen-level container — the case that
     * makes an ancestor-scoped XPath look fine and match everything.
     */
    private static final String PAGE_SOURCE = """
            <hierarchy>
              <android.widget.FrameLayout class="android.widget.FrameLayout" bounds="[0,0][1080,2280]" resource-id="com.jar:id/nav_host_fragment">
                <android.view.View class="android.view.View" resource-id="com.jar:id/abc_button" content-desc="ABC Button" bounds="[100,900][500,1050]" clickable="true" />
                <android.widget.TextView class="android.widget.TextView" text="Offers" bounds="[40,120][400,280]" />
                <android.widget.TextView class="android.widget.TextView" text="Offers" bounds="[40,300][400,460]" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private final PageSourceTreeService service = new PageSourceTreeService();

    @Test
    void buildsTheWholeTreeWithEveryAttribute() {
        var tree = service.build(PAGE_SOURCE, null, null);

        assertEquals(5, tree.nodeCount(), "hierarchy + frame + 3 children");
        var button = find(tree.root(), "com.jar:id/abc_button");
        assertEquals("true", button.attributes().get("clickable"));
        assertEquals("ABC Button", button.contentDesc());
        assertArrayEquals(new int[]{100, 900, 500, 1050}, button.rect());
    }

    @Test
    void givesEachNodeItsAbsoluteXPath() {
        var tree = service.build(PAGE_SOURCE, null, null);

        assertEquals("/hierarchy/android.widget.FrameLayout[1]/android.view.View[1]",
                find(tree.root(), "com.jar:id/abc_button").xpath());
    }

    /** Sibling position is per-tag, so the second TextView is [2] even though it is the third child. */
    @Test
    void numbersSiblingsOfTheSameTagIndependently() {
        var tree = service.build(PAGE_SOURCE, null, null);
        var frame = tree.root().children().get(0);

        assertEquals("/hierarchy/android.widget.FrameLayout[1]/android.widget.TextView[2]",
                frame.children().get(2).xpath());
    }

    @Test
    void countsAUniqueLocatorAsMatchingOneElement() {
        var tree = service.build(PAGE_SOURCE, null, null);

        var byResourceId = candidate(find(tree.root(), "com.jar:id/abc_button"),
                CandidateLocator.Strategy.RESOURCE_ID);
        assertEquals(1, byResourceId.getMatchCount());
        assertTrue(byResourceId.isUnique());
    }

    /**
     * The failure this whole view exists for: two rows share class and text, so the text
     * locator resolves to whichever the driver returns first. The count is what says so.
     */
    @Test
    void countsADuplicatedTextLocatorAsAmbiguous() {
        var tree = service.build(PAGE_SOURCE, null, null);
        var firstOffers = tree.root().children().get(0).children().get(1);

        var byText = candidate(firstOffers, CandidateLocator.Strategy.EXACT_TEXT_XPATH);
        assertEquals(2, byText.getMatchCount());
        assertTrue(byText.isAmbiguous());
    }

    /** An ancestor-scoped XPath anchored on a screen-level container matches every row under it. */
    @Test
    void countsAnAncestorScopedLocatorAgainstTheWholeAnchorSubtree() {
        var tree = service.build(PAGE_SOURCE, null, null);
        var firstOffers = tree.root().children().get(0).children().get(1);

        var scoped = candidate(firstOffers, CandidateLocator.Strategy.CLASS_NAME_XPATH);
        assertEquals(2, scoped.getMatchCount(), "both TextViews live under nav_host_fragment");
    }

    @Test
    void selectsTheSmallestNodeContainingATapPoint() {
        var tree = service.build(PAGE_SOURCE, 300, 975);

        assertNotNull(tree.selectedId());
        assertEquals("com.jar:id/abc_button", byId(tree.root(), tree.selectedId()).resourceId());
    }

    @Test
    void reportsNoSelectionWhenNoPointWasGiven() {
        assertNull(service.build(PAGE_SOURCE, null, null).selectedId());
    }

    private PageSourceTreeService.SourceNode find(PageSourceTreeService.SourceNode node, String resourceId) {
        if (resourceId.equals(node.resourceId())) return node;
        for (var child : node.children()) {
            var hit = find(child, resourceId);
            if (hit != null) return hit;
        }
        return null;
    }

    private PageSourceTreeService.SourceNode byId(PageSourceTreeService.SourceNode node, int id) {
        if (node.id() == id) return node;
        for (var child : node.children()) {
            var hit = byId(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private CandidateLocator candidate(PageSourceTreeService.SourceNode node, CandidateLocator.Strategy strategy) {
        List<CandidateLocator> hits = node.candidates().stream()
                .filter(c -> c.getStrategy() == strategy).toList();
        assertFalse(hits.isEmpty(), "no " + strategy + " candidate for " + node.xpath());
        return hits.get(0);
    }
}
