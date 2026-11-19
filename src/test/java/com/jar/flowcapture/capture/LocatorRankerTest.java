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
}
