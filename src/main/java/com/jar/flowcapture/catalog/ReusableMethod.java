package com.jar.flowcapture.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One existing utility method the generator can call instead of writing new logic. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReusableMethod {

    /**
     * Every method from utilsPackage (ActionUtils / WaitUtils / WebUtils) and PaymentHelper
     * that makes sense as a recordable flow step. Deliberately excluded: retryUntil (takes a
     * Function lambda — not expressible in a UI), takeScreenShot / changeUrl /
     * configureSecurityChecks / changePaymentStatusForIOS, and everything in AllureReportUtil,
     * SlackNotifier, PerfTelemetryController and ConfigReader — those are framework and
     * reporting infrastructure, not steps in an app flow.
     */
    public enum Kind {
        // --- parameterless, execute immediately ---
        NAVIGATE_BACK, CLOSE_KEYBOARD, OPEN_APP, CLOSE_APP,
        SWITCH_TO_WEBVIEW, SWITCH_TO_NATIVE,
        SCROLL_DOWN, SCROLL_UP,
        COMPLETE_PHONEPE_PAYMENT, GO_TO_HOME,

        // --- take a target element that is NOT on screen yet (QA describes it) ---
        SCROLL_DOWN_UNTIL_VISIBLE, SCROLL_UP_UNTIL_VISIBLE, NAVIGATE_BACK_UNTIL_VISIBLE,

        // --- take a target element that IS on screen (QA taps it) ---
        SCROLL_TILL_END_OF_SECTION, HOLD_AND_DRAG, DRAG_LEFT_TO_RIGHT,
        WAIT_FOR_VISIBILITY, WAIT_FOR_PRESENCE, TAP_ELEMENT,

        // --- multiple elements ---
        WAIT_FOR_ANY_VISIBLE, ASSERT_DOM_LOADED, DRAG_COIN_TO_END,

        // --- numeric ---
        WAIT_FOR_SECONDS, IMPLICIT_WAIT,

        // --- mixed element + text / enum ---
        CLICK_AND_VERIFY, CHANGE_PAYMENT_STATUS,

        // --- takes an element, but the tool supplies it (see presetLocatorClass) ---
        RETURN_TO_HOME
    }

    private Kind kind;

    /**
     * Set on methods that take an element argument which the tool resolves itself from a
     * locator already declared in the target repo, instead of asking QA for it. Together
     * with presetLocatorField this names e.g. HomeScreenLocators.profileIcon, which
     * RETURN_TO_HOME passes to navigateBackUntilElementFound.
     *
     * The point is ergonomic: every test in the repo starts and ends on the home screen and
     * asserts it via that one locator, so making QA describe the same element on every
     * recording is pure friction — and a hand-described version would also miss the four
     * alternative XPaths the real @AndroidFindAll declares.
     */
    private String presetLocatorClass;

    /** Field name within presetLocatorClass, e.g. "profileIcon". */
    private String presetLocatorField;

    /** e.g. "ActionUtils.scrollUntilElementFound" */
    private String fullyQualifiedCall;

    /** Import needed in generated file, e.g. "utilsPackage.ActionUtils" (callers prepend "import "/"import static "). */
    private String importStatement;

    /** Shown to the QA engineer in the dashboard prompt/quick-action list. */
    private String humanDescription;

    /**
     * Ordered arguments this method takes — drives both the dashboard modal and the
     * generated call's argument list. Empty for parameterless methods, which can run
     * straight off a one-click button.
     */
    private List<MethodParam> params;

    /**
     * True when the method is an instance method on the target repo's class rather than
     * static, so generated code must call it through a field (e.g. `actionUtils.changePaymentStatus(...)`,
     * matching how MonthlySavingsPage already holds an ActionUtils instance) instead of
     * statically. Getting this wrong produces code that does not compile.
     */
    private boolean instanceMethod;

    /**
     * instanceMethod only: the class to declare and instantiate as a page field, e.g.
     * "ActionUtils" -> `ActionUtils actionUtils = new ActionUtils();`. Mirrors how
     * MonthlySavingsPage already declares `PaymentHelper payment = new PaymentHelper();`
     * and `ActionUtils ac = new ActionUtils();`.
     */
    private String instanceClassName;

    /** instanceMethod only: the field name used as the call receiver, e.g. "actionUtils". */
    private String instanceFieldName;

    /**
     * True for AssertionUtils.assertDOMLoaded, which is static but takes the driver as its
     * first argument — generated code must pass DriverFactory.driver ahead of the element
     * varargs, exactly as BaseSetup does today.
     */
    private boolean needsDriverArg;

    /**
     * True when the dashboard can record this call but cannot perform it on the device —
     * it depends on the target repo's own config or the payment simulator wiring
     * (completePhonePePayment, goToHome, changePaymentStatus). QA does it by hand on the
     * phone and presses the button so the generated code still calls the real method.
     * Single source of truth for UtilityExecutor and for the button's label.
     */
    private boolean manualOnDevice;

    /** Convenience for the old call sites: does this need at least one WebElement? */
    public boolean isRequiresTarget() {
        return params != null && params.stream().anyMatch(MethodParam::isElementKind);
    }

    /** A method with no arguments at all can be a single dashboard button with no modal. */
    /**
     * True when this method declares parameters but the tool fills them in, so the dashboard
     * offers it as a one-click button rather than opening an argument modal.
     */
    public boolean isAutoResolvedArgs() {
        return presetLocatorClass != null && !presetLocatorClass.isBlank();
    }

    public boolean isParameterless() {
        return params == null || params.isEmpty();
    }
}
