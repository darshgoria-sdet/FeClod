package com.jar.flowcapture.capture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A locator already declared somewhere in the target repo's locaters/ tree that matches
 * an element QA just captured. Surfaced so the same element is not declared a third or
 * fourth time (measured: 16% of the repo's 452 locator values are redundant declarations).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExistingLocatorRef {

    /** e.g. "PaymentLocators" */
    private String locatorsClassName;

    /** e.g. "locaters.goldLocators" */
    private String locatorsPackage;

    /** e.g. "phonePayButton" */
    private String fieldName;

    /** The locator value as declared there, for showing QA what matched. */
    private String locatorValue;

    /** Strategy as declared there — may differ from ours while matching the same element. */
    private CandidateLocator.Strategy strategy;

    /**
     * True when our candidate and the existing declaration use DIFFERENT strategies but
     * resolve to the same semantic key (e.g. ours is an exact-text XPath, theirs is an
     * accessibility id, both for "Skip"). Worth showing differently from an exact match,
     * since QA may legitimately want the strategy they captured.
     */
    private boolean equivalentNotExact;

    /** Every other class declaring this same locator — tells QA whether it is genuinely shared or a one-off. */
    private List<String> alsoDeclaredIn;

    /** Field accessor, e.g. "getPhonePayButton". */
    public String getterName() {
        if (fieldName == null || fieldName.isEmpty()) return "get";
        return "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    /** Page-object field name for the reused locators class, e.g. "paymentLocators". */
    public String instanceFieldName() {
        if (locatorsClassName == null || locatorsClassName.isEmpty()) return "locators";
        return Character.toLowerCase(locatorsClassName.charAt(0)) + locatorsClassName.substring(1);
    }
}
