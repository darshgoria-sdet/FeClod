package com.jar.flowcapture.catalog;

import com.jar.flowcapture.capture.RecordedStep;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Seeded from the actual reusable methods in utilsPackage.ActionUtils / WaitUtils /
 * WebUtils / AssertionUtils and PaymentHelper, read directly in Jar_Frontend_Automation.
 * When a recorded step uses one of these, codegen calls the existing method instead of
 * re-emitting equivalent logic — the direct fix for page objects duplicating
 * near-identical scroll/wait/navigation code today.
 *
 * Deliberately NOT catalogued: WaitUtils.retryUntil (takes a Function lambda, not
 * expressible in a UI), ActionUtils.takeScreenShot / changeUrl / configureSecurityChecks /
 * changePaymentStatusForIOS / changePaymentStatusBeforeLogin, and everything in
 * AllureReportUtil, SlackNotifier, PerfTelemetryController and ConfigReader — those are
 * framework/reporting infrastructure, not steps in an app flow.
 *
 * Note: wrapping a click in WaitUtils.waitForClick is NOT catalogued — every real tap in
 * the repo is wait-wrapped unconditionally, so PageClassGenerator applies it directly
 * rather than offering it as an optional "reuse?" suggestion.
 */
@Service
public class MethodCatalogService {

    private static final String ACTION_UTILS = "utilsPackage.ActionUtils";
    private static final String WAIT_UTILS = "utilsPackage.WaitUtils";
    private static final String WEB_UTILS = "utilsPackage.WebUtils";
    private static final String ASSERTION_UTILS = "utilsPackage.AssertionUtils";
    private static final String PAYMENT_HELPER = "utilsPackage.PaymentHelper";

    /**
     * The repo's canonical "are we on the home screen?" element. Declared with
     * @AndroidFindAll over four alternative XPaths, all of which this tool picks up from the
     * source file — so RETURN_TO_HOME checks for any of them, exactly as the real locator does.
     */
    private static final String HOME_LOCATORS_CLASS = "HomeScreenLocators";
    private static final String HOME_ANCHOR_FIELD = "profileIcon";

    private final List<ReusableMethod> catalog = List.of(

            // ---------- parameterless ----------
            m(ReusableMethod.Kind.NAVIGATE_BACK, "ActionUtils.navigateBack", ACTION_UTILS,
                    "Presses the real Android back action — not a tap on a UI back button, so it works "
                            + "even on screens using gesture navigation with no visible back icon"),
            m(ReusableMethod.Kind.CLOSE_KEYBOARD, "ActionUtils.closeKeyboard", ACTION_UTILS,
                    "Dismisses the on-screen keyboard"),
            m(ReusableMethod.Kind.OPEN_APP, "ActionUtils.openApp", ACTION_UTILS,
                    "Brings the Jar app to the foreground by package ID"),
            m(ReusableMethod.Kind.CLOSE_APP, "ActionUtils.closeApp", ACTION_UTILS,
                    "Terminates the Jar app process"),
            m(ReusableMethod.Kind.SWITCH_TO_WEBVIEW, "WebUtils.switchToWebView", WEB_UTILS,
                    "Switches the driver context to the app's WebView"),
            m(ReusableMethod.Kind.SWITCH_TO_NATIVE, "WebUtils.switchToNative", WEB_UTILS,
                    "Switches the driver context back to native"),
            m(ReusableMethod.Kind.SCROLL_DOWN, "ActionUtils.scrollDownGeneric", ACTION_UTILS,
                    "Performs a single downward swipe gesture"),
            m(ReusableMethod.Kind.SCROLL_UP, "ActionUtils.scrollUp", ACTION_UTILS,
                    "Performs a single upward swipe gesture"),
            instance(ReusableMethod.Kind.COMPLETE_PHONEPE_PAYMENT, "completePhonePePayment", PAYMENT_HELPER,
                    "PaymentHelper", "paymentHelper",
                    "Completes the PhonePe simulator payment (enter PIN, confirm)"),
            instance(ReusableMethod.Kind.GO_TO_HOME, "goToHome", PAYMENT_HELPER,
                    "PaymentHelper", "paymentHelper",
                    "Returns to the app home screen after a payment"),

            // ---------- target is OFF screen: QA describes it ----------
            withParams(ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE, "ActionUtils.scrollUntilElementFound", ACTION_UTILS,
                    "Scrolls down repeatedly (max 6) until the given element becomes visible",
                    List.of(MethodParam.offScreenElement("element", "Which element should it scroll down to?"))),
            withParams(ReusableMethod.Kind.SCROLL_UP_UNTIL_VISIBLE, "ActionUtils.scrollUpUntilElementFound", ACTION_UTILS,
                    "Scrolls up repeatedly (max 6) until the given element becomes visible",
                    List.of(MethodParam.offScreenElement("element", "Which element should it scroll up to?"))),
            withParams(ReusableMethod.Kind.NAVIGATE_BACK_UNTIL_VISIBLE, "ActionUtils.navigateBackUntilElementFound", ACTION_UTILS,
                    "Presses back repeatedly (max 6) until the given element becomes visible",
                    List.of(MethodParam.offScreenElement("element", "Press back until which element appears?"))),

            // Same method as above, but with the element already chosen. Every test in the repo
            // starts and ends on the home screen and proves it with HomeScreenLocators.profileIcon,
            // so this is the single most-repeated navigation in the suite — worth one button
            // rather than re-describing the same element on every recording.
            presetElement(ReusableMethod.Kind.RETURN_TO_HOME, "ActionUtils.navigateBackUntilElementFound", ACTION_UTILS,
                    "Presses back until the home screen profile icon is visible — the standard "
                            + "start/end state for every test in this repo",
                    HOME_LOCATORS_CLASS, HOME_ANCHOR_FIELD),

            // ---------- target IS on screen: QA taps it ----------
            withParams(ReusableMethod.Kind.SCROLL_TILL_END_OF_SECTION, "ActionUtils.scrollTillEndOfSection", ACTION_UTILS,
                    "Swipes one scrollable section to its end without affecting overall page scroll",
                    List.of(MethodParam.onScreenElement("sectionElement", "Tap the scrollable section"))),
            withParams(ReusableMethod.Kind.HOLD_AND_DRAG, "ActionUtils.holdAndDrag", ACTION_UTILS,
                    "Holds an element and drags it to the bottom of the screen, e.g. to dismiss a bottom sheet",
                    List.of(MethodParam.onScreenElement("element", "Tap the element to hold and drag"))),
            withParams(ReusableMethod.Kind.DRAG_LEFT_TO_RIGHT, "ActionUtils.dragLeftToRight", ACTION_UTILS,
                    "Drags an element from left to right, e.g. a swipe-to-confirm slider",
                    List.of(MethodParam.onScreenElement("element", "Tap the element to drag"))),
            withParams(ReusableMethod.Kind.WAIT_FOR_VISIBILITY, "WaitUtils.waitForVisibility", WAIT_UTILS,
                    "Waits until the element is visible before continuing",
                    List.of(MethodParam.onScreenElement("element", "Tap the element to wait for"))),
            withParams(ReusableMethod.Kind.WAIT_FOR_PRESENCE, "WaitUtils.waitForPresence", WAIT_UTILS,
                    "Waits until the element is present in the DOM (the iOS SF Symbol workaround)",
                    List.of(MethodParam.onScreenElement("element", "Tap the element to wait for"))),
            withParams(ReusableMethod.Kind.TAP_ELEMENT, "WaitUtils.tapElement", WAIT_UTILS,
                    "Taps an element by coordinate — the fallback when a normal click does not register",
                    List.of(MethodParam.onScreenElement("element", "Tap the element to coordinate-tap"))),

            // ---------- several elements ----------
            withParams(ReusableMethod.Kind.WAIT_FOR_ANY_VISIBLE, "WaitUtils.waitForAnyVisible", WAIT_UTILS,
                    "Polls until the first of several possible next-screens appears",
                    List.of(MethodParam.intParam("timeoutSeconds", "Timeout in seconds", 20),
                            MethodParam.elementList("elements", "Tap each element that could appear"))),
            driverFirst(ReusableMethod.Kind.ASSERT_DOM_LOADED, "AssertionUtils.assertDOMLoaded", ASSERTION_UTILS,
                    "Asserts the screen's key elements have loaded",
                    List.of(MethodParam.elementList("elements", "Tap each element that must be present"))),
            withParams(ReusableMethod.Kind.DRAG_COIN_TO_END, "ActionUtils.dragCoinToEnd", ACTION_UTILS,
                    "Drags the coin element onto the gold bar element (the Swipe-to-Buy flow)",
                    List.of(MethodParam.onScreenElement("coinElement", "Tap the coin element"),
                            MethodParam.onScreenElement("goldBarElement", "Tap the gold bar element"))),

            // ---------- numeric ----------
            withParams(ReusableMethod.Kind.WAIT_FOR_SECONDS, "WaitUtils.waitForSeconds", WAIT_UTILS,
                    "Explicit fixed sleep — last resort, prefer a conditional wait where one fits",
                    List.of(MethodParam.intParam("seconds", "How many seconds?", 2))),
            withParams(ReusableMethod.Kind.IMPLICIT_WAIT, "ActionUtils.implicitWait", ACTION_UTILS,
                    "Sets the driver's implicit wait",
                    List.of(MethodParam.intParam("second", "Implicit wait in seconds", 10))),

            // ---------- mixed ----------
            withParams(ReusableMethod.Kind.CLICK_AND_VERIFY, "ActionUtils.clickAndVerify", ACTION_UTILS,
                    "Clicks one element and asserts another appears as a result",
                    List.of(MethodParam.onScreenElement("toClick", "Tap the element to click"),
                            MethodParam.onScreenElement("expectedAfter", "Tap the element expected afterwards"),
                            MethodParam.stringParam("actionDesc", "Describe the action, e.g. \"tapped Proceed\""),
                            MethodParam.stringParam("resultDesc", "Describe the result, e.g. \"payment sheet opened\""))),
            instanceWithParams(ReusableMethod.Kind.CHANGE_PAYMENT_STATUS, "changePaymentStatus", ACTION_UTILS,
                    "ActionUtils", "actionUtils",
                    "Sets the simulated autopay and manual payment outcomes — the Success/Pending/Failed switch",
                    List.of(MethodParam.paymentStatus("autoPayStatus", "Autopay result"),
                            MethodParam.paymentStatus("manualPayStatus", "Manual payment result")))
    );

    private static ReusableMethod m(ReusableMethod.Kind kind, String call, String imp, String desc) {
        return ReusableMethod.builder().kind(kind).fullyQualifiedCall(call).importStatement(imp)
                .humanDescription(desc).params(List.of()).build();
    }

    private static ReusableMethod withParams(ReusableMethod.Kind kind, String call, String imp, String desc,
                                             List<MethodParam> params) {
        return ReusableMethod.builder().kind(kind).fullyQualifiedCall(call).importStatement(imp)
                .humanDescription(desc).params(params).build();
    }

    /**
     * A method whose sole element argument is supplied by the tool from a locator that already
     * exists in the target repo, so it needs no modal. The param is still declared, because
     * codegen renders the argument list from it just like any other call.
     */
    private static ReusableMethod presetElement(ReusableMethod.Kind kind, String call, String imp, String desc,
                                                String locatorClass, String locatorField) {
        return ReusableMethod.builder().kind(kind).fullyQualifiedCall(call).importStatement(imp)
                .humanDescription(desc)
                .params(List.of(MethodParam.offScreenElement("element", "Supplied automatically")))
                .presetLocatorClass(locatorClass).presetLocatorField(locatorField).build();
    }

    private static ReusableMethod driverFirst(ReusableMethod.Kind kind, String call, String imp, String desc,
                                              List<MethodParam> params) {
        return ReusableMethod.builder().kind(kind).fullyQualifiedCall(call).importStatement(imp)
                .humanDescription(desc).params(params).needsDriverArg(true).build();
    }

    private static ReusableMethod instance(ReusableMethod.Kind kind, String method, String imp,
                                           String className, String fieldName, String desc) {
        return instanceWithParams(kind, method, imp, className, fieldName, desc, List.of());
    }

    /**
     * Instance methods here all happen to be manual-on-device: PaymentHelper drives the
     * PhonePe simulator and ActionUtils.changePaymentStatus drives the app's own Dev Tools
     * screen, neither of which this tool reproduces. They are still catalogued so the
     * generated code calls the real method rather than open-coding the flow again.
     */
    private static ReusableMethod instanceWithParams(ReusableMethod.Kind kind, String method, String imp,
                                                     String className, String fieldName, String desc,
                                                     List<MethodParam> params) {
        return ReusableMethod.builder().kind(kind).fullyQualifiedCall(fieldName + "." + method)
                .importStatement(imp).humanDescription(desc).params(params)
                .instanceMethod(true).instanceClassName(className).instanceFieldName(fieldName)
                .manualOnDevice(true).build();
    }

    public List<ReusableMethod> all() {
        return catalog;
    }

    public Optional<ReusableMethod> byKind(String kindName) {
        if (kindName == null) return Optional.empty();
        return catalog.stream().filter(m -> m.getKind().name().equals(kindName)).findFirst();
    }

    /**
     * Methods offered as one-click buttons with no modal. SCROLL_DOWN/SCROLL_UP are
     * parameterless but excluded here: they are already recorded through /step/scroll,
     * which emits proper SCROLL_DOWN/SCROLL_UP step types and feeds the scroll-then-tap
     * reuse suggestion. Offering them twice would duplicate the existing buttons.
     */
    public List<ReusableMethod> quickActions() {
        return catalog.stream()
                .filter(m -> m.isParameterless() || m.isAutoResolvedArgs())
                .filter(m -> m.getKind() != ReusableMethod.Kind.SCROLL_DOWN
                        && m.getKind() != ReusableMethod.Kind.SCROLL_UP)
                .toList();
    }

    /** Methods that need a modal to collect arguments before they can run. */
    public List<ReusableMethod> parameterisedActions() {
        return catalog.stream()
                .filter(m -> !m.isParameterless())
                .filter(m -> !m.isAutoResolvedArgs())
                .toList();
    }

    /**
     * A recorded SCROLL_DOWN/SCROLL_UP step immediately followed by a TAP on an element
     * that was not present pre-scroll is the exact pattern scrollUntilElementFound exists
     * for — surface it as a suggestion rather than letting codegen emit a raw swipe-loop.
     */
    public ReusableMethod suggestFor(RecordedStep step) {
        return switch (step.getActionType()) {
            case SCROLL_DOWN -> byKind(ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE.name()).orElse(null);
            case SCROLL_UP -> byKind(ReusableMethod.Kind.SCROLL_UP_UNTIL_VISIBLE.name()).orElse(null);
            default -> null;
        };
    }
}
