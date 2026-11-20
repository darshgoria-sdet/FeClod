package com.jar.flowcapture.capture;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One possible way to locate a tapped element, with a strategy tier matching the
 * priority already implicit in Jar_Frontend_Automation's locator classes:
 * resource-id > content-desc > exact text > uiAutomator textContains > index-based (last resort).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CandidateLocator {

    public enum Strategy {
        RESOURCE_ID(1, false),
        CONTENT_DESC(2, false),
        EXACT_TEXT_XPATH(3, false),
        TEXT_CONTAINS_UIAUTOMATOR(4, false),
        CLASS_NAME_XPATH(5, false),
        INDEX_BASED(6, true);

        public final int priority;
        public final boolean brittle;

        Strategy(int priority, boolean brittle) {
            this.priority = priority;
            this.brittle = brittle;
        }
    }

    private Strategy strategy;

    /** The actual locator value, e.g. the resource-id string, or the full xpath/uiAutomator expression. */
    private String value;

    /** android class attribute from page-source, used to pick @AndroidFindBy(xpath=...) vs (id=...) etc. */
    private String elementClass;

    /**
     * How many elements on the captured screen this locator actually matches. 1 is the only
     * good answer: a locator matching several elements resolves to whichever happens to come
     * first in the page source, which is not reliably the element that was tapped.
     *
     * 0 means "not counted" (nothing has measured it yet), not "matches nothing".
     */
    private int matchCount;

    /** Keeps every existing 3-arg call site working now that match counting is measured separately. */
    public CandidateLocator(Strategy strategy, String value, String elementClass) {
        this(strategy, value, elementClass, 0);
    }

    /** True when this locator identifies exactly one element on the screen it was captured from. */
    public boolean isUnique() {
        return matchCount == 1;
    }

    /** A counted locator that matches more than one element — resolves to an arbitrary one of them. */
    public boolean isAmbiguous() {
        return matchCount > 1;
    }

    public boolean isBrittle() {
        return strategy.brittle;
    }
}
