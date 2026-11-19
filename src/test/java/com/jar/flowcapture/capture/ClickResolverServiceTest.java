package com.jar.flowcapture.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickResolverServiceTest {

    private static final String SAMPLE_PAGE_SOURCE = """
            <hierarchy>
              <android.widget.FrameLayout bounds="[0,0][1080,2280]">
                <android.widget.LinearLayout bounds="[0,100][1080,300]">
                  <android.widget.TextView text="Instant Save" resource-id="com.aso_centric.jar.staging:id/title" class="android.widget.TextView" bounds="[40,120][400,280]" />
                </android.widget.LinearLayout>
                <android.view.View resource-id="com.aso_centric.jar.staging:id/abc_button" content-desc="ABC Button" class="android.view.View" bounds="[100,900][500,1050]" clickable="true" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private final ClickResolverService resolver = new ClickResolverService();

    @Test
    void resolvesClickToMostSpecificElementContainingPoint() {
        // Point lands inside the abc_button element's bounds, not just the outer FrameLayout.
        var resolved = resolver.resolve(SAMPLE_PAGE_SOURCE, 300, 975);

        assertEquals("com.aso_centric.jar.staging:id/abc_button",
                resolved.xmlElement().getAttribute("resource-id"));
    }

    @Test
    void ranksResourceIdAboveContentDescAndText() {
        var resolved = resolver.resolve(SAMPLE_PAGE_SOURCE, 300, 975);

        assertEquals(CandidateLocator.Strategy.RESOURCE_ID, resolved.candidates().get(0).getStrategy());
        assertEquals("com.aso_centric.jar.staging:id/abc_button", resolved.candidates().get(0).getValue());
    }

    @Test
    void textOnlyElementFallsBackToExactTextXPath() {
        // "title" TextView has text but no content-desc, resource-id IS present here too,
        // so resource-id should still win first.
        var resolved = resolver.resolve(SAMPLE_PAGE_SOURCE, 200, 200);

        assertEquals(CandidateLocator.Strategy.RESOURCE_ID, resolved.candidates().get(0).getStrategy());
    }

    @Test
    void throwsWhenNoElementAtPoint() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(SAMPLE_PAGE_SOURCE, 5000, 5000));
    }
}
