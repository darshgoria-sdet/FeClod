package com.jar.flowcapture.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One argument a catalogued method takes. Replaces the old boolean requiresTarget, which
 * could only express "this needs exactly one WebElement" — not enough once the catalog
 * covers waitForAnyVisible(int, WebElement...), clickAndVerify(el, el, String, String)
 * and changePaymentStatus(PaymentStatus, PaymentStatus).
 *
 * The dashboard builds its modal from this list, so adding a method with a new argument
 * shape needs no UI change.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MethodParam {

    public enum Kind {
        /** A single WebElement. */
        ELEMENT,
        /** Varargs WebElement... — QA supplies one or more. */
        ELEMENT_LIST,
        /** A plain int, e.g. a timeout in seconds. */
        INT,
        /** A free-text String, e.g. clickAndVerify's action/result descriptions. */
        STRING,
        /** One of the repo's PaymentStatus enum constants. */
        PAYMENT_STATUS
    }

    private Kind kind;

    /** Argument name, used for the generated comment and as the modal field key. */
    private String name;

    /** What the dashboard asks QA for this argument. */
    private String prompt;

    /**
     * ELEMENT/ELEMENT_LIST only. True when the element is, by definition, NOT on screen
     * when the action starts — scrollUntilElementFound exists precisely because its target
     * is below the fold, so QA cannot tap it on the mirror and must describe it instead
     * (by visible text / resource-id / content-desc). False means "tap it on the mirror".
     */
    private boolean offScreen;

    /** Prefilled value for INT/STRING params, e.g. a sensible default timeout. Null for none. */
    private String defaultValue;

    /**
     * Allowed values for an enum-backed param, so the dashboard can render a dropdown without
     * hardcoding the target repo's constants. Mirrors enums.PaymentStatus, which has four
     * constants — FAILED and FAILURE are genuinely distinct server-side states there, so both
     * must be offered rather than collapsed into one.
     */
    private List<String> options;

    public static MethodParam onScreenElement(String name, String prompt) {
        return new MethodParam(Kind.ELEMENT, name, prompt, false, null, null);
    }

    public static MethodParam offScreenElement(String name, String prompt) {
        return new MethodParam(Kind.ELEMENT, name, prompt, true, null, null);
    }

    public static MethodParam elementList(String name, String prompt) {
        return new MethodParam(Kind.ELEMENT_LIST, name, prompt, false, null, null);
    }

    public static MethodParam intParam(String name, String prompt, int defaultValue) {
        return new MethodParam(Kind.INT, name, prompt, false, String.valueOf(defaultValue), null);
    }

    public static MethodParam stringParam(String name, String prompt) {
        return new MethodParam(Kind.STRING, name, prompt, false, null, null);
    }

    public static MethodParam paymentStatus(String name, String prompt) {
        return new MethodParam(Kind.PAYMENT_STATUS, name, prompt, false, "SUCCESS",
                List.of("SUCCESS", "PENDING", "FAILED", "FAILURE"));
    }

    public boolean isElementKind() {
        return kind == Kind.ELEMENT || kind == Kind.ELEMENT_LIST;
    }
}
