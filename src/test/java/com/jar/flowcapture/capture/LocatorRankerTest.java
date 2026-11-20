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

    /**
     * The ancestor-scoped XPath here matches 3 views, and it used to survive filtering because
     * nothing else identified the element at all. Now the positional fallback does, so the
     * ambiguous one is dropped rather than offered — the counting that proves it is three is
     * asserted directly, since the candidate no longer reaches the filtered list.
     */
    @Test
    void anAmbiguousAncestorScopedXPathIsDroppedOnceSomethingUniqueExists() throws Exception {
        Element target = parseAndFindTargetAt(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER, 1);
        Element root = root(ANCHOR_IS_A_SCREEN_LEVEL_CONTAINER);

        CandidateLocator scoped = LocatorRanker.rankCandidates(target).stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.CLASS_NAME_XPATH)
                .findFirst().orElseThrow();
        assertEquals(3, LocatorRanker.countByXPath(root, scoped.getValue()));

        List<CandidateLocator> filtered = LocatorRanker.countAndFilter(
                root, LocatorRanker.rankCandidates(target));
        assertTrue(filtered.stream().allMatch(c -> c.isUnique() || c.isBrittle()),
                "only unique locators survive, plus the flagged fallbacks: " + filtered);
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

    /**
     * The bug this guards: Compose labels on this app carry invisible padding — the profile
     * tiles expose "Offers \n" and "Spins \n". Trimming before building the locator produced
     * //android.widget.TextView[@text="Offers"], which reads correctly, counted as unique
     * against the same trimmed value, and matched zero elements on the device.
     */
    @Test
    void paddedTextGetsAWhitespaceTolerantLocatorRatherThanOneThatMatchesNothing() {
        Element el = parse("""
                <hierarchy>
                  <android.widget.TextView class="android.widget.TextView" text="Offers &#10;" bounds="[0,0][10,10]" />
                </hierarchy>
                """);

        var candidates = LocatorRanker.rankCandidates(firstChild(el));

        var xpath = candidates.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH)
                .findFirst().orElseThrow();
        assertEquals("//android.widget.TextView[normalize-space(@text)=\"Offers\"]", xpath.getValue());

        var uiSelector = candidates.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR)
                .findFirst().orElseThrow();
        assertEquals("new UiSelector().textContains(\"Offers\")", uiSelector.getValue());
    }

    /** Text with no padding keeps the exact form the repo's own hand-written locators use. */
    @Test
    void cleanTextStillGetsAPlainExactMatch() {
        Element el = parse("""
                <hierarchy>
                  <android.widget.TextView class="android.widget.TextView" text="Save in Gold" bounds="[0,0][10,10]" />
                </hierarchy>
                """);

        var xpath = LocatorRanker.rankCandidates(firstChild(el)).stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH)
                .findFirst().orElseThrow();

        assertEquals("//android.widget.TextView[@text=\"Save in Gold\"]", xpath.getValue());
    }

    /** normalize-space() collapses runs inside the string too, so the literal has to be collapsed to match. */
    @Test
    void internalWhitespaceIsCollapsedInTheLiteralAsWellAsTheComparison() {
        Element el = parse("""
                <hierarchy>
                  <android.widget.TextView class="android.widget.TextView" text="Round &#10;Off" bounds="[0,0][10,10]" />
                </hierarchy>
                """);

        var xpath = LocatorRanker.rankCandidates(firstChild(el)).stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH)
                .findFirst().orElseThrow();

        assertEquals("//android.widget.TextView[normalize-space(@text)=\"Round Off\"]", xpath.getValue());
    }

    /** A tolerant locator must be counted tolerantly, or the count contradicts the locator. */
    @Test
    void aWhitespaceTolerantLocatorCountsEveryElementItWouldActuallyMatch() {
        Element root = parse("""
                <hierarchy>
                  <android.widget.TextView class="android.widget.TextView" text="Offers &#10;" bounds="[0,0][10,10]" />
                  <android.widget.TextView class="android.widget.TextView" text="Offers" bounds="[0,20][10,30]" />
                </hierarchy>
                """);
        var padded = LocatorRanker.rankCandidates(firstChild(root));

        LocatorRanker.countAndFilter(root, padded);

        var xpath = padded.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.EXACT_TEXT_XPATH)
                .findFirst().orElseThrow();
        assertEquals(2, xpath.getMatchCount(), "normalize-space() matches the padded and the clean one");
    }

    /** The nth ELEMENT child — getChildNodes() also returns the whitespace between tags. */
    private Element elementChild(Element parent, int index) {
        var kids = parent.getChildNodes();
        int seen = 0;
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                if (seen++ == index) return child;
            }
        }
        throw new IllegalStateException("no element child at " + index);
    }

    private Element firstChild(Element root) {
        var kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) return child;
        }
        throw new IllegalStateException("no child element");
    }

    private Element parse(String xml) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            return factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The failure that kept coming back. A Jetpack Compose card is a bare android.view.View
     * with no id, no text and no content-description, so the only locator the ranker could
     * build was an ancestor-scoped XPath anchored on nav_host_fragment — 104 matches on this
     * app's profile screen. The identity was always there, one level down, on the label
     * inside the card.
     */
    @Test
    void anUnidentifiableElementBorrowsTheIdentityOfTheLabelInsideIt() {
        Element root = parse("""
                <hierarchy>
                  <android.widget.FrameLayout class="android.widget.FrameLayout" resource-id="com.jar:id/nav_host_fragment" bounds="[0,0][1200,2670]">
                    <android.view.View class="android.view.View" clickable="true" bounds="[378,2048][543,2328]">
                      <android.widget.TextView class="android.widget.TextView" text="Offers &#10;" bounds="[404,2227][518,2328]" />
                    </android.view.View>
                    <android.view.View class="android.view.View" bounds="[0,0][100,100]" />
                    <android.view.View class="android.view.View" bounds="[0,100][100,200]" />
                  </android.widget.FrameLayout>
                </hierarchy>
                """);
        Element card = elementChild(elementChild(root, 0), 0);

        var candidates = LocatorRanker.countAndFilter(root, LocatorRanker.rankCandidates(card));

        var best = candidates.get(0);
        assertEquals("//android.widget.TextView[normalize-space(@text)=\"Offers\"]", best.getValue());
        assertEquals(1, best.getMatchCount());
    }

    /** An element that identifies itself must not be described by something inside it. */
    @Test
    void anElementWithItsOwnIdIsNotDescribedByItsChildren() {
        Element root = parse("""
                <hierarchy>
                  <android.view.View class="android.view.View" resource-id="offers_tile" bounds="[0,0][100,100]">
                    <android.widget.TextView class="android.widget.TextView" text="Offers" bounds="[0,0][50,50]" />
                  </android.view.View>
                </hierarchy>
                """);

        var candidates = LocatorRanker.rankCandidates(firstChild(root));

        assertEquals(CandidateLocator.Strategy.RESOURCE_ID, candidates.get(0).getStrategy());
        assertTrue(candidates.stream().noneMatch(c -> c.getValue().contains("Offers")),
                "the child's text should not appear: " + candidates);
    }

    /**
     * The backstop. Every element now has at least one locator matching exactly one thing,
     * so "nothing here identifies a single element" can never again be the best on offer.
     */
    @Test
    void anElementWithNoIdentityAnywhereStillGetsAUniqueLocator() {
        Element root = parse("""
                <hierarchy>
                  <android.view.View class="android.view.View" resource-id="home_pager" bounds="[0,0][100,100]">
                    <android.view.View class="android.view.View" bounds="[0,0][50,50]" />
                    <android.view.View class="android.view.View" bounds="[0,50][50,100]" />
                    <android.view.View class="android.view.View" bounds="[50,0][100,50]" />
                  </android.view.View>
                </hierarchy>
                """);
        Element second = elementChild(elementChild(root, 0), 1);

        var candidates = LocatorRanker.countAndFilter(root, LocatorRanker.rankCandidates(second));

        var positional = candidates.stream()
                .filter(c -> c.getStrategy() == CandidateLocator.Strategy.INDEXED_UNDER_ANCHOR)
                .findFirst().orElseThrow(() -> new AssertionError("no positional fallback in " + candidates));
        assertEquals("(//*[@resource-id=\"home_pager\"]//android.view.View)[2]", positional.getValue());
        assertEquals(1, positional.getMatchCount(), "a positional locator is unique by construction");
        assertTrue(positional.isBrittle(), "and it must be flagged, because any layout change moves it");
    }

    /** Counting now evaluates the real XPath, so an expression of any shape is counted honestly. */
    @Test
    void anXPathOfAnyShapeIsCountedByEvaluatingIt() {
        Element root = parse("""
                <hierarchy>
                  <android.view.View class="android.view.View" resource-id="pager" bounds="[0,0][100,100]">
                    <android.widget.TextView class="android.widget.TextView" text="A" bounds="[0,0][10,10]" />
                    <android.widget.TextView class="android.widget.TextView" text="B" bounds="[0,10][10,20]" />
                  </android.view.View>
                </hierarchy>
                """);

        assertEquals(2, LocatorRanker.countByXPath(root, "//*[@resource-id=\"pager\"]//android.widget.TextView"));
        assertEquals(1, LocatorRanker.countByXPath(root, "(//*[@resource-id=\"pager\"]//android.widget.TextView)[2]"));
        assertEquals(0, LocatorRanker.countByXPath(root, "//nothing"));
    }

    /** "Send \nMoney" can never contain "Send Money", so no UiSelector candidate is offered. */
    @Test
    void noUiSelectorIsOfferedForALabelWithWhitespaceInsideIt() {
        Element root = parse("""
                <hierarchy>
                  <android.widget.TextView class="android.widget.TextView" text="Send &#10;Money" bounds="[0,0][10,10]" />
                </hierarchy>
                """);

        var candidates = LocatorRanker.rankCandidates(firstChild(root));

        assertNull(LocatorRanker.textUiSelector("Send \nMoney"));
        assertTrue(candidates.stream().noneMatch(
                        c -> c.getStrategy() == CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR),
                candidates.toString());
        assertEquals("//android.widget.TextView[normalize-space(@text)=\"Send Money\"]",
                candidates.get(0).getValue());
    }
}
