package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.TryBlock;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageClassGeneratorTest {

    private final PageClassGenerator generator = new PageClassGenerator(new MethodCatalogService());

    private RecordedStep tap(String label) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(label);
        step.setElementFieldName(NameUtils.toFieldName(label));
        step.setCandidateLocators(List.of(
                new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, "com.jar:id/x", "android.view.View")));
        return step;
    }

    private RecordedStep scrollDown() {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.SCROLL_DOWN);
        step.setMatchedExistingMethod(ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE.name());
        return step;
    }

    private RecordedStep utilityCall(ReusableMethod.Kind kind) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        step.setUtilityMethodKind(kind.name());
        step.setActionLabel("some long human description from the catalog");
        return step;
    }

    private RecordedStep utilityWithArgs(ReusableMethod.Kind kind, UtilityArgs args) {
        RecordedStep step = utilityCall(kind);
        step.setUtilityArgs(args);
        return step;
    }

    private UtilityArgs.ElementTarget target(String paramName, String fieldName) {
        return new UtilityArgs.ElementTarget(paramName, fieldName,
                List.of(new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID, "com.jar:id/t", "android.view.View")),
                null);
    }

    @Test
    void scrollUntilElementFoundPassesItsTargetElement() {
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(target("element", "rewardsBanner"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Festive Savings");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.SCROLL_DOWN_UNTIL_VISIBLE, args));

        String content = generator.generate(flow, "FestiveSavingsLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains("ActionUtils.scrollUntilElementFound(festiveSavingsLocators.getRewardsBanner());"), content);
        assertFalse(content.contains("TODO"), "a supplied target must not leave a placeholder:\n" + content);
    }

    @Test
    void waitForAnyVisibleRendersTimeoutThenVarargsElements() {
        UtilityArgs args = new UtilityArgs();
        args.getScalars().add(new UtilityArgs.ScalarArg("timeoutSeconds", "15"));
        args.getElements().add(target("elements", "successScreen"));
        args.getElements().add(target("elements", "pendingScreen"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Txn Branch");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.WAIT_FOR_ANY_VISIBLE, args));

        String content = generator.generate(flow, "TxnBranchLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains(
                "WaitUtils.waitForAnyVisible(15, txnBranchLocators.getSuccessScreen(), txnBranchLocators.getPendingScreen());"),
                content);
    }

    /** A non-numeric timeout must fall back to the declared default rather than emit code that will not compile. */
    @Test
    void nonNumericIntArgumentFallsBackToTheDefault() {
        UtilityArgs args = new UtilityArgs();
        args.getScalars().add(new UtilityArgs.ScalarArg("seconds", "abc"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Bad Wait");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.WAIT_FOR_SECONDS, args));

        String content = generator.generate(flow, "BadWaitLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains("WaitUtils.waitForSeconds(2);"), content);
    }

    @Test
    void changePaymentStatusCallsThroughAnInstanceFieldWithEnumArgs() {
        UtilityArgs args = new UtilityArgs();
        args.getScalars().add(new UtilityArgs.ScalarArg("autoPayStatus", "PENDING"));
        args.getScalars().add(new UtilityArgs.ScalarArg("manualPayStatus", "FAILURE"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Pending Txn");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.CHANGE_PAYMENT_STATUS, args));

        String content = generator.generate(flow, "PendingTxnLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains("ActionUtils actionUtils = new ActionUtils();"),
                "instance methods need a field — calling it statically would not compile:\n" + content);
        assertTrue(content.contains("actionUtils.changePaymentStatus(PaymentStatus.PENDING, PaymentStatus.FAILURE);"), content);
        assertTrue(content.contains("import enums.PaymentStatus;"), content);
    }

    @Test
    void clickAndVerifyRendersTwoElementsThenTwoQuotedStrings() {
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(target("toClick", "proceedCta"));
        args.getElements().add(target("expectedAfter", "paymentSheet"));
        args.getScalars().add(new UtilityArgs.ScalarArg("actionDesc", "tapped Proceed"));
        args.getScalars().add(new UtilityArgs.ScalarArg("resultDesc", "payment sheet opened"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Pay Now");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.CLICK_AND_VERIFY, args));

        String content = generator.generate(flow, "PayNowLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains(
                "ActionUtils.clickAndVerify(payNowLocators.getProceedCta(), payNowLocators.getPaymentSheet(), "
                        + "\"tapped Proceed\", \"payment sheet opened\");"), content);
    }

    @Test
    void assertDomLoadedPassesDriverFirst() {
        UtilityArgs args = new UtilityArgs();
        args.getElements().add(target("elements", "header"));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Dom Check");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.ASSERT_DOM_LOADED, args));

        String content = generator.generate(flow, "DomCheckLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains("AssertionUtils.assertDOMLoaded(DriverFactory.driver, domCheckLocators.getHeader());"), content);
        assertTrue(content.contains("import basePackage.DriverFactory;"), content);
    }

    // ---- locator reuse ----

    @Test
    void reusedLocatorCallsThroughTheExistingClassInsteadOfRedeclaring() {
        RecordedStep tap = tap("pay button");
        tap.setReusedLocator(new ExistingLocatorRef("PaymentLocators", "locaters.goldLocators",
                "phonePayButton", "com.phonepe.simulator:id/pay_button",
                CandidateLocator.Strategy.RESOURCE_ID, false, List.of("MonthlySavingsLocators")));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Festive Savings");
        flow.addStep(tap);

        String content = generator.generate(flow, "FestiveSavingsLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(content.contains("import locaters.goldLocators.PaymentLocators;"), content);
        assertTrue(content.contains("PaymentLocators paymentLocators = new PaymentLocators(null);"), content);
        assertTrue(content.contains("waitForClick(paymentLocators.getPhonePayButton()).click();"), content);
        assertFalse(content.contains("festiveSavingsLocators.getPayButton()"),
                "the reused element must not also be referenced on the new locators class:\n" + content);
    }

    @Test
    void reusingTheSameClassTwiceDeclaresOneFieldAndOneImport() {
        ExistingLocatorRef ref = new ExistingLocatorRef("PaymentLocators", "locaters.goldLocators",
                "phonePayButton", "com.phonepe.simulator:id/pay_button",
                CandidateLocator.Strategy.RESOURCE_ID, false, List.of());
        RecordedStep first = tap("pay button");
        first.setReusedLocator(ref);
        RecordedStep second = tap("pay button again");
        second.setReusedLocator(ref);

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Double Pay");
        flow.addStep(first);
        flow.addStep(second);

        String content = generator.generate(flow, "DoublePayLocators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertEquals(1, content.lines().filter(l -> l.trim().equals("import locaters.goldLocators.PaymentLocators;")).count(),
                "duplicate imports do not compile:\n" + content);
        assertEquals(1, content.lines().filter(l -> l.contains("new PaymentLocators(null)")).count(),
                "duplicate fields do not compile:\n" + content);
    }

    @Test
    void backQuickActionEmitsTheRealNavigateBackCallAndItsImport() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Festive Savings");
        flow.addStep(tap("festive savings card"));
        flow.addStep(utilityCall(ReusableMethod.Kind.NAVIGATE_BACK));

        var result = generator.generate(flow, "FestiveSavingsLocators", "locaters.goldLocators", "pageObjectModel.gold");
        String content = result.fileContent();

        assertTrue(content.contains("ActionUtils.navigateBack();"),
                "Back must call the existing ActionUtils method, not re-tap a located back button:\n" + content);
        assertTrue(content.contains("import utilsPackage.ActionUtils;"),
                "the ActionUtils import must be emitted for the generated call to compile:\n" + content);
    }

    @Test
    void utilityCallContributesNoLocatorField() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Back Only");
        flow.addStep(utilityCall(ReusableMethod.Kind.NAVIGATE_BACK));

        var result = generator.generate(flow, "BackOnlyLocators", "locaters.goldLocators", "pageObjectModel.gold");

        assertFalse(result.fileContent().contains("getNull"),
                "a utility step has no element, so it must not dereference a locator field:\n" + result.fileContent());
    }

    @Test
    void webViewQuickActionsEmitWebUtilsImportNotActionUtils() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Web Hop");
        flow.addStep(utilityCall(ReusableMethod.Kind.SWITCH_TO_WEBVIEW));
        flow.addStep(utilityCall(ReusableMethod.Kind.SWITCH_TO_NATIVE));

        var result = generator.generate(flow, "WebHopLocators", "locaters.goldLocators", "pageObjectModel.gold");
        String content = result.fileContent();

        assertTrue(content.contains("WebUtils.switchToWebView();"), content);
        assertTrue(content.contains("WebUtils.switchToNative();"), content);
        assertTrue(content.contains("import utilsPackage.WebUtils;"),
                "WebUtils calls need their own import:\n" + content);
    }

    @Test
    void repeatedUtilityCallsDoNotDuplicateTheirImport() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Back Twice");
        flow.addStep(utilityCall(ReusableMethod.Kind.NAVIGATE_BACK));
        flow.addStep(utilityCall(ReusableMethod.Kind.CLOSE_KEYBOARD));

        var result = generator.generate(flow, "BackTwiceLocators", "locaters.goldLocators", "pageObjectModel.gold");
        String content = result.fileContent();

        long actionUtilsImports = content.lines().filter(l -> l.trim().equals("import utilsPackage.ActionUtils;")).count();
        assertEquals(1, actionUtilsImports,
                "duplicate imports do not compile in Java:\n" + content);
    }

    @Test
    void scrollFollowedByTapPassesTheTappedElementAsTheScrollTarget() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Instant Save Abc");
        flow.addStep(scrollDown());
        flow.addStep(tap("abc button"));

        var result = generator.generate(flow, "InstantSaveAbcLocators", "locaters.goldLocators", "pageObjectModel.gold");

        assertTrue(result.fileContent().contains("ActionUtils.scrollUntilElementFound(instantSaveAbcLocators.getAbcButton());"),
                "expected the scroll call to target the tapped element, got:\n" + result.fileContent());
        assertFalse(result.fileContent().contains("TODO: target element"),
                "scroll-then-tap should never leave the old dead placeholder");
    }

    @Test
    void scrollWithNoFollowingTapFallsBackToTodoPlaceholder() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Dangling Scroll");
        flow.addStep(scrollDown());

        var result = generator.generate(flow, "DanglingScrollLocators", "locaters.goldLocators", "pageObjectModel.gold");

        assertTrue(result.fileContent().contains("TODO"),
                "a scroll with nothing tapped after it has no target to infer and should say so");
    }

    @Test
    void scrollBeforeScreenBoundaryDoesNotBorrowATapFromTheNextScreen() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Cross Screen Scroll");
        flow.addStep(scrollDown());

        RecordedStep boundary = new RecordedStep();
        boundary.setActionType(RecordedStep.ActionType.SCREEN_BOUNDARY);
        boundary.setScreenName("NextScreen");
        flow.addStep(boundary);
        flow.addStep(tap("unrelated button"));

        var result = generator.generate(flow, "CrossScreenScrollLocators", "locaters.goldLocators", "pageObjectModel.gold");

        // "getUnrelatedButton()" legitimately appears once, in its own TAP step's line —
        // the bug this guards against is the *scroll* line also referencing it as a target.
        String scrollLine = result.fileContent().lines()
                .filter(l -> l.contains("ActionUtils.scrollUntilElementFound"))
                .findFirst().orElseThrow(() -> new AssertionError("expected a scroll call in generated output"));
        assertFalse(scrollLine.contains("UnrelatedButton"),
                "a tap on a different screen must not be used as this scroll's target: " + scrollLine);
        assertTrue(scrollLine.contains("TODO"),
                "with no tap on the same screen, the scroll should fall back to a TODO: " + scrollLine);
    }

    /**
     * The whole point of RETURN_TO_HOME: the generated page must call the repo's existing
     * home-screen locator, not declare a fifth copy of the profile icon in this flow's own
     * Locators class. Written as the exact line QA asked for.
     */
    @Test
    void returnToHomeCallsTheExistingHomeScreenLocator() {
        ExistingLocatorRef homeAnchor = new ExistingLocatorRef(
                "HomeScreenLocators", "locaters.goldLocators", "profileIcon",
                "//android.view.View[@content-desc='ProfileIcon']",
                CandidateLocator.Strategy.EXACT_TEXT_XPATH, false, List.of());

        UtilityArgs args = new UtilityArgs();
        args.getElements().add(new UtilityArgs.ElementTarget("element", "profileIcon",
                List.of(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH,
                        "//android.view.View[@content-desc='ProfileIcon']", "android.view.View")),
                homeAnchor));

        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("InstantSaveAbc");
        flow.addStep(utilityWithArgs(ReusableMethod.Kind.RETURN_TO_HOME, args));

        String code = generator.generate(flow, "InstantSaveAbcLocators", "locaters.goldLocators",
                "pageObjectModel.gold").fileContent();

        assertTrue(code.contains(
                        "ActionUtils.navigateBackUntilElementFound(homeScreenLocators.getProfileIcon());"),
                "expected the exact repo call, got:\n" + code);
        assertTrue(code.contains("import locaters.goldLocators.HomeScreenLocators;"));
        assertTrue(code.contains("HomeScreenLocators homeScreenLocators = new HomeScreenLocators(null);"));
        assertFalse(code.contains("instantSaveAbcLocators.getProfileIcon()"),
                "the profile icon must come from the existing class, never from this flow's own");
    }

    /**
     * An inserted wait has to land where it was inserted. Codegen walks the step list in
     * order, so this is really a check that insertion and generation agree about position —
     * the wait exists precisely because the tap after it was happening too early.
     */
    @Test
    void anInsertedWaitIsGeneratedBetweenTheStepsItWasInsertedBetween() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");
        flow.addStep(tap("offers card"));
        flow.addStep(tap("first coupon"));

        UtilityArgs args = new UtilityArgs();
        args.getScalars().add(new UtilityArgs.ScalarArg("seconds", "3"));
        flow.insertStep(1, utilityWithArgs(ReusableMethod.Kind.WAIT_FOR_SECONDS, args));

        String code = generator.generate(flow, "Test4Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        List<String> calls = code.lines().map(String::trim)
                .filter(l -> l.endsWith(";") && !l.startsWith("import") && !l.startsWith("private"))
                .toList();
        int waitLine = indexOfLineContaining(calls, "waitForSeconds(3)");
        int firstTap = indexOfLineContaining(calls, "getOffersCard");
        int secondTap = indexOfLineContaining(calls, "getFirstCoupon");

        assertTrue(waitLine > firstTap && waitLine < secondTap,
                "the wait should sit between the two taps, got: " + calls);
        assertTrue(code.contains("import utilsPackage.WaitUtils;"), code);
    }

    private int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return fail("no generated line contains " + needle + " in " + lines);
    }

    /**
     * The optional-screen case this exists for: a coupon sheet that shows on some runs. The
     * generated shape matches HandlingBottomSheets, which is how the repo already writes it
     * by hand — @Slf4j on the class, log.info in the catch.
     */
    @Test
    void wrappedStepsAreGeneratedInsideATryWithTheRepoSCatchIdiom() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");
        flow.addStep(tap("offers card"));
        flow.addStep(tap("dismiss coupon sheet"));
        flow.addStep(tap("first coupon"));
        TryBlock block = new TryBlock();
        block.setNote("the coupon sheet is not shown on every run");
        flow.wrapSteps(1, 1, block);

        String code = generator.generate(flow, "Test4Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(code.contains("import lombok.extern.slf4j.Slf4j;"), code);
        assertTrue(code.contains("@Slf4j\npublic class Test4Page"), code);
        assertTrue(code.contains("""
                        try {
                            waitForClick(test4Locators.getDismissCouponSheet()).click();
                        } catch (Exception e) {
                            log.info("the coupon sheet is not shown on every run");
                            log.info(e.getMessage());
                        }
                """.stripTrailing()), code);
        // the steps outside the block stay where they were, at the original indent
        assertTrue(code.contains("        waitForClick(test4Locators.getOffersCard()).click();\n        try {"), code);
    }

    @Test
    void severalStepsShareOneTryBlock() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");
        flow.addStep(tap("open sheet"));
        flow.addStep(tap("confirm"));
        flow.wrapSteps(0, 1, new TryBlock());

        String code = generator.generate(flow, "Test4Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertEquals(1, code.split("try \\{", -1).length - 1, "one try for both steps:\n" + code);
        assertEquals(1, code.split("catch \\(Exception e\\)", -1).length - 1, code);
    }

    /** FAIL_WITH_MESSAGE still fails — it just fails in the flow author's words. */
    @Test
    void aFailWithMessageBlockThrowsInsteadOfLogging() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");
        flow.addStep(tap("pay"));
        TryBlock block = new TryBlock();
        block.setOnFailure(TryBlock.OnFailure.FAIL_WITH_MESSAGE);
        block.setNote("payment screen never opened");
        flow.wrapSteps(0, 0, block);

        String code = generator.generate(flow, "Test4Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(code.contains("throw new AssertionError(\"payment screen never opened\", e);"), code);
        assertFalse(code.contains("log.info"), "nothing logs, so the class needs no @Slf4j: " + code);
        assertFalse(code.contains("@Slf4j"), code);
    }

    /** A flow with no blocks must generate exactly what it did before any of this existed. */
    @Test
    void aFlowWithNoTryBlocksIsUnchanged() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test4");
        flow.addStep(tap("offers card"));

        String code = generator.generate(flow, "Test4Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertFalse(code.contains("try {"), code);
        assertFalse(code.contains("@Slf4j"), code);
        assertTrue(code.contains("        waitForClick(test4Locators.getOffersCard()).click();\n"), code);
    }

    /** The backup route, generated the way the team writes it by hand. */
    @Test
    void recoveryStepsAreGeneratedInsideTheCatch() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test5");
        flow.addStep(tap("primary way"));
        flow.addStep(tap("other way"));
        flow.addStep(tap("carry on"));
        TryBlock block = new TryBlock();
        block.setOnFailure(TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        block.setNote("the sheet sometimes opens the other way round");
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        String code = generator.generate(flow, "Test5Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertTrue(code.contains("""
                        try {
                            waitForClick(test5Locators.getPrimaryWay()).click();
                        } catch (Exception e) {
                            log.info("the sheet sometimes opens the other way round");
                            log.info(e.getMessage());
                            waitForClick(test5Locators.getOtherWay()).click();
                        }
                """.stripTrailing()), code);
    }

    /** A backup step appears once, in the catch — never also in the main sequence. */
    @Test
    void aRecoveryStepIsNotAlsoEmittedInline() {
        RecordedFlow flow = new RecordedFlow();
        flow.setFlowName("Test5");
        flow.addStep(tap("primary way"));
        flow.addStep(tap("other way"));
        TryBlock block = new TryBlock();
        block.setOnFailure(TryBlock.OnFailure.RUN_RECOVERY_STEPS);
        flow.wrapSteps(0, 0, block);
        flow.assignToCatch(1, 1, block.getId());

        String code = generator.generate(flow, "Test5Locators", "locaters.goldLocators", "pageObjectModel.gold")
                .fileContent();

        assertEquals(1, code.split("getOtherWay", -1).length - 1, code);
    }
}
