package com.jar.flowcapture.capture;

import lombok.Data;

import java.util.List;

/**
 * One captured user action during exploration: a tap, a text entry, or an explicit
 * scroll (QA presses "I scrolled" rather than us inferring gestures from screenshots —
 * explicit beats inferred for v1 reliability).
 */
@Data
public class RecordedStep {

    public enum ActionType { TAP, INPUT, SCROLL_DOWN, SCROLL_UP, SCREEN_BOUNDARY, UTILITY_CALL }

    private ActionType actionType;

    /** Human label, e.g. "tap ABC button" — QA types this once per step, drives generated method/variable naming. */
    private String actionLabel;

    /** Candidate locators for the tapped/typed element, ranked best-first by LocatorRanker. */
    private List<CandidateLocator> candidateLocators;

    /** Chosen candidate's Java field name, e.g. "abcButton" — derived from actionLabel. */
    private String elementFieldName;

    /** Only set for ActionType.INPUT. */
    private String inputValue;

    /** Only set for ActionType.SCREEN_BOUNDARY — QA-given name for the screen entered, e.g. "InstantSaveNewScreen". */
    private String screenName;

    /** Set when MethodCatalogService finds an existing reusable method matching this step (e.g. a scroll helper). */
    private String matchedExistingMethod;

    /**
     * Only set for ActionType.UTILITY_CALL — the ReusableMethod.Kind name() of an
     * explicit quick-action the QA engineer invoked directly (Back, close keyboard,
     * open/close app, switch WebView context, etc.), distinct from matchedExistingMethod
     * which records an *accepted suggestion* for an inferred step (e.g. scroll-then-tap).
     * A UTILITY_CALL is never inferred — it's a deliberate dashboard button press.
     */
    private String utilityMethodKind;

    /**
     * UTILITY_CALL only: the resolved arguments for a parameterised method, in MethodParam
     * order. Null for a parameterless quick action like Back, which needs none.
     */
    private UtilityArgs utilityArgs;

    /**
     * TAP/INPUT only: set when this element's locator already exists elsewhere in the target
     * repo and QA chose to reuse it rather than declare it again. When set, the Locators
     * class emits no field for this step and the Page class calls through the existing
     * class instead.
     */
    private ExistingLocatorRef reusedLocator;

    /**
     * Base64 PNG thumbnail captured right after this step executed on the device —
     * lets QA visually confirm what a step actually did when reviewing the log,
     * instead of trusting a text label alone. Null if the screenshot capture failed
     * (never blocks recording — a missing thumbnail is not worth losing the step over).
     */
    private String screenshotThumbnailBase64;

    /**
     * Set after FlowSessionController re-resolves this step's chosen locator against
     * the device's current page-source right before the flow is finished — confirms
     * the generated locator still finds the same element rather than shipping a
     * locator that looked fine when recorded but silently stopped matching.
     */
    private Boolean verifiedBeforeWrite;
}
