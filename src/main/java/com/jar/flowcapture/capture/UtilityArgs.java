package com.jar.flowcapture.capture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The resolved arguments for one parameterised utility call, in the same order as the
 * method's MethodParam list. Element arguments carry their own ranked locators so the
 * Locators class gets a field per target, exactly as a tapped element would.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UtilityArgs {

    /**
     * One WebElement argument. An ELEMENT_LIST param contributes several of these in a row,
     * all sharing the same paramName — the generator joins them into a varargs call.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ElementTarget {
        /** Which MethodParam this satisfies, e.g. "toClick". */
        private String paramName;
        /** Java field name in the generated Locators class, e.g. "proceedCta". */
        private String fieldName;
        /** Ranked locators for this element, best-first. */
        private List<CandidateLocator> candidateLocators;
        /**
         * Set when the locator was matched to one that already exists elsewhere in the
         * target repo and QA chose to reuse it, e.g. "PaymentLocators.phonePayButton".
         * Null means a new field is generated in this flow's own Locators class.
         */
        private ExistingLocatorRef reusedFrom;
    }

    private List<ElementTarget> elements = new ArrayList<>();

    /**
     * Non-element arguments (INT, STRING, PAYMENT_STATUS) keyed by MethodParam name,
     * stored as the raw strings QA entered. Rendered per-kind at codegen time: ints bare,
     * strings quoted and escaped, PaymentStatus as an enum constant reference.
     */
    private List<ScalarArg> scalars = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ScalarArg {
        private String paramName;
        private String value;
    }

    public List<ElementTarget> elementsFor(String paramName) {
        return elements.stream().filter(e -> paramName.equals(e.getParamName())).toList();
    }

    public String scalarFor(String paramName) {
        return scalars.stream().filter(s -> paramName.equals(s.getParamName()))
                .map(ScalarArg::getValue).findFirst().orElse(null);
    }
}
