package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.*;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import com.jar.flowcapture.codegen.*;
import com.jar.flowcapture.execute.UtilityExecutor;
import com.jar.flowcapture.replay.FlowReplayService;
import com.jar.flowcapture.replay.ReplayReport;
import com.jar.flowcapture.reuse.LocatorIndexService;
import com.jar.flowcapture.style.StyleConformanceChecker;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST surface the dashboard talks to: device session lifecycle, recording a flow
 * step at a time, previewing generated code live, and finishing a flow to trigger
 * codegen + verification + the style gate + file write.
 */
@RestController
@RequestMapping("/api/flow")
public class FlowSessionController {

    private final AppiumSessionService appiumSessionService;
    private final FlowSessionService flowSessionService;
    private final ClickResolverService clickResolverService;
    private final LocatorVerificationService locatorVerificationService;
    private final MethodCatalogService methodCatalogService;
    private final LocatorsClassGenerator locatorsClassGenerator;
    private final PageClassGenerator pageClassGenerator;
    private final TestClassGenerator testClassGenerator;
    private final StyleConformanceChecker styleConformanceChecker;
    private final FileWriterService fileWriterService;
    private final UtilityExecutor utilityExecutor;
    private final LocatorIndexService locatorIndexService;
    private final FlowReplayService flowReplayService;

    public FlowSessionController(AppiumSessionService appiumSessionService,
                                  FlowSessionService flowSessionService,
                                  ClickResolverService clickResolverService,
                                  LocatorVerificationService locatorVerificationService,
                                  MethodCatalogService methodCatalogService,
                                  LocatorsClassGenerator locatorsClassGenerator,
                                  PageClassGenerator pageClassGenerator,
                                  TestClassGenerator testClassGenerator,
                                  StyleConformanceChecker styleConformanceChecker,
                                  FileWriterService fileWriterService,
                                  UtilityExecutor utilityExecutor,
                                  LocatorIndexService locatorIndexService,
                                  FlowReplayService flowReplayService) {
        this.appiumSessionService = appiumSessionService;
        this.flowSessionService = flowSessionService;
        this.clickResolverService = clickResolverService;
        this.locatorVerificationService = locatorVerificationService;
        this.methodCatalogService = methodCatalogService;
        this.locatorsClassGenerator = locatorsClassGenerator;
        this.pageClassGenerator = pageClassGenerator;
        this.testClassGenerator = testClassGenerator;
        this.styleConformanceChecker = styleConformanceChecker;
        this.fileWriterService = fileWriterService;
        this.utilityExecutor = utilityExecutor;
        this.locatorIndexService = locatorIndexService;
        this.flowReplayService = flowReplayService;
    }

    @PostMapping("/device/connect")
    public Map<String, Object> connectDevice() {
        appiumSessionService.startSession();
        return deviceState();
    }

    /**
     * Connection state plus any foreground-package mismatch. The warning is advisory and never
     * blocks recording — the QA engineer may genuinely be mid-switch between builds — but it
     * has to be visible, because recording against the wrong build fails silently.
     * Map.of rejects null values, so the warning key is only present when there is one.
     */
    private Map<String, Object> deviceState() {
        boolean connected = appiumSessionService.isActive();
        String warning = connected ? appiumSessionService.foregroundPackageWarning() : null;
        return warning == null
                ? Map.of("connected", connected)
                : Map.of("connected", connected, "packageWarning", warning);
    }

    /**
     * Whether a device session is already live. The dashboard is stateless across reloads,
     * so without this it shows "not connected" even while a session is active — misleading,
     * and it leaves the screen mirror blank until the user clicks Connect again.
     */
    @GetMapping("/device/status")
    public Map<String, Object> deviceStatus() {
        return deviceState();
    }

    @PostMapping("/device/disconnect")
    public Map<String, Object> disconnectDevice() {
        appiumSessionService.stopSession();
        return Map.of("connected", false);
    }

    @PostMapping("/start")
    public RecordedFlow startFlow(@RequestBody StartFlowRequest request) {
        // The target repo is a live working copy QA may pull or edit between recordings, so
        // the reuse index is rebuilt per flow rather than once at boot — a stale index would
        // offer to reuse a locator that no longer exists.
        locatorIndexService.refresh();
        RecordedFlow flow = flowSessionService.startNewFlow(request.getFlowName());
        flow.setTargetArea(request.getTargetArea() != null ? request.getTargetArea() : TargetArea.GOLD);
        flowSessionService.persistCurrentState();
        return flow;
    }

    @Data
    public static class StartFlowRequest {
        private String flowName;
        private TargetArea targetArea;
    }

    /** Resumes a session persisted by a previous (possibly crashed) run of this app, if one exists. */
    @GetMapping("/resumable")
    public Map<String, Object> resumable() {
        boolean hasActive = flowSessionService.hasActiveFlow();
        return Map.of("hasActiveFlow", hasActive, "flow", hasActive ? flowSessionService.requireCurrentFlow() : Map.of());
    }

    /** Discards the in-progress flow with no codegen and no file write — distinct from /finish, which always generates and writes. */
    @PostMapping("/discard")
    public Map<String, Object> discard() {
        flowSessionService.endFlow();
        return Map.of("discarded", true);
    }

    /**
     * Called when QA clicks a point on the mirrored screenshot. Actually taps the
     * device (the dashboard is a real remote control, not just an observer — without
     * this, the phone never advances and nothing downstream has anything real to show),
     * captures a thumbnail for the step log, records the step, and returns ranked
     * candidate locators plus a live preview of the generated code so far.
     */
    @PostMapping("/step/tap")
    public StepResponse recordTap(@RequestBody TapRequest request) {
        String pageSource = appiumSessionService.capturePageSource();
        ClickResolverService.ResolvedElement resolved =
                clickResolverService.resolve(pageSource, request.getX(), request.getY());

        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.TAP);
        step.setActionLabel(request.getActionLabel());
        step.setCandidateLocators(resolved.candidates());
        step.setElementFieldName(NameUtils.toFieldName(request.getActionLabel()));

        appiumSessionService.tap(request.getX(), request.getY());
        step.setScreenshotThumbnailBase64(captureThumbnailSafely());

        ReusableMethod suggestion = methodCatalogService.suggestFor(step);
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.addStep(step);
        flowSessionService.persistCurrentState();

        return new StepResponse(step, suggestion, buildPreview(flow), existingLocatorFor(flow, step));
    }

    @Data
    public static class TapRequest {
        private int x;
        private int y;
        private String actionLabel;
    }

    @Data
    public static class StepResponse {
        private final RecordedStep step;
        private final ReusableMethod suggestedExistingMethod;
        private final PreviewResponse preview;
        /** Non-null when this element's locator is already declared elsewhere in the repo and QA should be asked whether to reuse it. */
        private final ExistingLocatorRef existingLocator;

        public StepResponse(RecordedStep step, ReusableMethod suggestedExistingMethod, PreviewResponse preview) {
            this(step, suggestedExistingMethod, preview, null);
        }

        public StepResponse(RecordedStep step, ReusableMethod suggestedExistingMethod,
                            PreviewResponse preview, ExistingLocatorRef existingLocator) {
            this.step = step;
            this.suggestedExistingMethod = suggestedExistingMethod;
            this.preview = preview;
            this.existingLocator = existingLocator;
        }
    }

    /**
     * Looks for an existing declaration of this step's element, excluding the Locators class
     * this flow is itself generating — an intra-flow duplicate is deduped silently by
     * LocatorsClassGenerator, and prompting for it would be noise.
     */
    private ExistingLocatorRef existingLocatorFor(RecordedFlow flow, RecordedStep step) {
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        return locatorIndexService.findExisting(step.getCandidateLocators(), generating).orElse(null);
    }

    @PostMapping("/step/input")
    public StepResponse recordInput(@RequestBody InputStepRequest request) {
        String pageSource = appiumSessionService.capturePageSource();
        ClickResolverService.ResolvedElement resolved =
                clickResolverService.resolve(pageSource, request.getX(), request.getY());

        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.INPUT);
        step.setActionLabel(request.getActionLabel());
        step.setCandidateLocators(resolved.candidates());
        step.setElementFieldName(NameUtils.toFieldName(request.getActionLabel()));
        step.setInputValue(request.getValue());

        appiumSessionService.tap(request.getX(), request.getY());
        appiumSessionService.typeText(request.getValue());
        step.setScreenshotThumbnailBase64(captureThumbnailSafely());

        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.addStep(step);
        flowSessionService.persistCurrentState();

        return new StepResponse(step, null, buildPreview(flow), existingLocatorFor(flow, step));
    }

    @Data
    public static class InputStepRequest {
        private int x;
        private int y;
        private String actionLabel;
        private String value;
    }

    /** QA presses an explicit "I scrolled down/up" button rather than us inferring gestures — more reliable for v1. */
    @PostMapping("/step/scroll")
    public StepResponse recordScroll(@RequestBody ScrollRequest request) {
        RecordedStep step = new RecordedStep();
        step.setActionType(request.isDown() ? RecordedStep.ActionType.SCROLL_DOWN : RecordedStep.ActionType.SCROLL_UP);
        step.setActionLabel(request.isDown() ? "scroll down" : "scroll up");

        if (request.isDown()) {
            appiumSessionService.scrollDown();
        } else {
            appiumSessionService.scrollUp();
        }
        step.setScreenshotThumbnailBase64(captureThumbnailSafely());

        ReusableMethod suggestion = methodCatalogService.suggestFor(step);
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.addStep(step);
        flowSessionService.persistCurrentState();

        return new StepResponse(step, suggestion, buildPreview(flow));
    }

    @Data
    public static class ScrollRequest {
        private boolean down;
    }

    /**
     * Explicit quick-action buttons for parameterless utility methods (Back, close
     * keyboard, open/close app, switch WebView context) — these call the real device
     * action directly, never a UI-element tap, matching how ActionUtils.navigateBack()
     * etc. work in the target repo. Distinct from the tap flow's locator-based reuse
     * suggestion, since these actions have no element to tap in the first place.
     */
    @PostMapping("/step/utility")
    public StepResponse recordUtilityCall(@RequestBody UtilityCallRequest request) {
        ReusableMethod method = methodCatalogService.byKind(request.getMethodKind())
                .orElseThrow(() -> new IllegalArgumentException("Unknown utility method: " + request.getMethodKind()));

        UtilityArgs args = request.getArgs() == null ? new UtilityArgs() : request.getArgs();
        RecordedFlow flow = flowSessionService.requireCurrentFlow();

        // Fill in any argument the tool supplies itself (RETURN_TO_HOME's home-screen anchor)
        // before naming, so it goes through the same path as a QA-picked element.
        applyPresetArgs(method, args);

        // Name every element argument and resolve its locators before executing, so a failure
        // mid-execution still leaves the flow untouched rather than half-recorded.
        nameElementTargets(flow, method, args);

        if (utilityExecutor.isExecutable(method)) {
            // Throws on a missing argument or an element that cannot be found. Nothing is
            // recorded in that case — matching the real methods returning false rather than
            // generating a call that would not work at playback.
            utilityExecutor.execute(method, args);
        }

        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        step.setActionLabel(method.getHumanDescription());
        step.setUtilityMethodKind(method.getKind().name());
        step.setUtilityArgs(args);
        step.setScreenshotThumbnailBase64(captureThumbnailSafely());

        flow.addStep(step);
        flowSessionService.persistCurrentState();

        return new StepResponse(step, null, buildPreview(flow));
    }

    /**
     * Supplies the element argument for a method pinned to a known repo locator, so QA gets a
     * one-click button instead of re-describing the same element every recording.
     *
     * The resolved target carries reusedFrom, which is the whole point: the generated code
     * calls homeScreenLocators.getProfileIcon() rather than redeclaring a fifth copy of that
     * locator in this flow's own Locators class.
     */
    private void applyPresetArgs(ReusableMethod method, UtilityArgs args) {
        if (!method.isAutoResolvedArgs()) return;
        if (!args.elementsFor("element").isEmpty()) return;

        String cls = method.getPresetLocatorClass();
        String field = method.getPresetLocatorField();
        var declared = locatorIndexService.findDeclared(cls, field);
        if (declared.isEmpty()) {
            // The index is rebuilt per flow, but the repo is a live working copy — one retry
            // covers the case where the file appeared after this flow started.
            locatorIndexService.refresh();
            declared = locatorIndexService.findDeclared(cls, field);
        }
        var found = declared.orElseThrow(() -> new IllegalArgumentException(
                cls + "." + field + " was not found in the target repo's locaters/ tree — it may have been "
                        + "renamed or moved. Use \"navigateBackUntilElementFound\" and pick the element by hand."));

        UtilityArgs.ElementTarget target = new UtilityArgs.ElementTarget();
        target.setParamName("element");
        target.setFieldName(found.ref().getFieldName());
        target.setCandidateLocators(found.candidates());
        target.setReusedFrom(found.ref());
        args.getElements().add(target);
    }

    /**
     * Gives each element argument a Java field name and, where the locator already exists in
     * the repo, attaches that reference so codegen calls through the existing class instead of
     * declaring the element again.
     */
    private void nameElementTargets(RecordedFlow flow, ReusableMethod method, UtilityArgs args) {
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        for (UtilityArgs.ElementTarget target : args.getElements()) {
            if (target.getFieldName() == null || target.getFieldName().isBlank()) {
                target.setFieldName(NameUtils.toFieldName(
                        target.getParamName() == null ? method.getKind().name() : target.getParamName()));
            }
            if (target.getReusedFrom() == null) {
                locatorIndexService.findExisting(target.getCandidateLocators(), generating)
                        .ifPresent(target::setReusedFrom);
            }
        }
    }

    @Data
    public static class UtilityCallRequest {
        private String methodKind;
        /** Null or empty for parameterless quick actions. */
        private UtilityArgs args;
    }

    /**
     * Replays every recorded step against the device, in order, and reports what passed.
     *
     * Recording proves each step worked once, in isolation, with a human pausing between
     * them. It does not prove the flow runs as a flow — which is the thing the generated
     * test will actually do. Running it here, before any code is written, turns a class of
     * failures that would otherwise show up as a flaky test days later into a report QA
     * reads while the phone is still in their hand.
     */
    @PostMapping("/replay")
    public ReplayReport replay(@RequestBody(required = false) ReplayRequest request) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        if (flow.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Nothing to replay — record at least one step first");
        }
        boolean restart = request != null && request.isRestartApp();
        return flowReplayService.replay(flow, restart);
    }

    @Data
    public static class ReplayRequest {
        /**
         * Close and reopen the app first, so replay starts from the same state a real test
         * run would. Off by default: QA may want to replay from where the phone already is.
         */
        private boolean restartApp;
    }

    /**
     * Resolves a point on the mirrored screen into ranked candidate locators WITHOUT
     * recording a step or touching the device — used by the parameterised-action modal so QA
     * can pick an element as an argument without that tap also becoming a step of its own.
     */
    @PostMapping("/resolve-element")
    public ResolveElementResponse resolveElement(@RequestBody TapRequest request) {
        String pageSource = appiumSessionService.capturePageSource();
        ClickResolverService.ResolvedElement resolved =
                clickResolverService.resolve(pageSource, request.getX(), request.getY());
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        return new ResolveElementResponse(
                resolved.candidates(),
                locatorIndexService.findExisting(resolved.candidates(), generating).orElse(null));
    }

    @Data
    public static class ResolveElementResponse {
        private final List<CandidateLocator> candidates;
        private final ExistingLocatorRef existingLocator;
    }

    /**
     * Builds candidate locators from text / resource-id / content-desc that QA typed, for an
     * element that is NOT currently on screen — the case scrollUntilElementFound exists for,
     * where the target cannot be tapped on the mirror because it is below the fold.
     */
    @PostMapping("/describe-element")
    public ResolveElementResponse describeElement(@RequestBody DescribeElementRequest request) {
        List<CandidateLocator> candidates = new ArrayList<>();
        String value = request.getValue() == null ? "" : request.getValue().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Describe the element by its visible text, resource-id or content-description");
        }

        switch (request.getKind() == null ? "TEXT" : request.getKind()) {
            case "RESOURCE_ID" -> candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.RESOURCE_ID, value, null));
            case "CONTENT_DESC" -> candidates.add(new CandidateLocator(
                    CandidateLocator.Strategy.CONTENT_DESC, value, null));
            default -> {
                // Exact text first, then a textContains fallback — a label that wraps across
                // two lines will not match an exact single-line XPath, and that exact failure
                // has already bitten this generator once.
                candidates.add(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH,
                        "//*[@text=\"" + value.replace("\"", "\\\"") + "\"]", null));
                candidates.add(new CandidateLocator(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR,
                        "new UiSelector().textContains(\"" + value.replace("\"", "\\\"") + "\")", null));
            }
        }

        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        return new ResolveElementResponse(candidates,
                locatorIndexService.findExisting(candidates, generating).orElse(null));
    }

    @Data
    public static class DescribeElementRequest {
        /** TEXT (default), RESOURCE_ID or CONTENT_DESC. */
        private String kind;
        private String value;
    }

    /** QA accepted the "this locator already exists — reuse it?" prompt for a recorded step. */
    @PostMapping("/step/{index}/reuse-locator")
    public StepResponse acceptLocatorReuse(@PathVariable int index, @RequestBody ExistingLocatorRef ref) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        List<RecordedStep> steps = flow.getSteps();
        if (index < 0 || index >= steps.size()) {
            throw new IllegalArgumentException("No step at index " + index);
        }
        steps.get(index).setReusedLocator(ref);
        flowSessionService.persistCurrentState();
        return new StepResponse(steps.get(index), null, buildPreview(flow));
    }

    /** Every catalogued method that needs arguments, with its parameter shapes, so the dashboard can build modals generically. */
    @GetMapping("/parameterised-actions")
    public List<ReusableMethod> parameterisedActions() {
        return methodCatalogService.parameterisedActions();
    }

    /** Size of the reuse index, so the dashboard can show whether the target repo was found. */
    @GetMapping("/locator-index")
    public LocatorIndexService.IndexStats locatorIndex() {
        return locatorIndexService.stats();
    }

    @GetMapping("/quick-actions")
    public List<ReusableMethod> quickActions() {
        return methodCatalogService.quickActions();
    }

    @PostMapping("/step/screen-boundary")
    public StepResponse recordScreenBoundary(@RequestBody ScreenBoundaryRequest request) {
        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.SCREEN_BOUNDARY);
        step.setScreenName(request.getScreenName());
        step.setScreenshotThumbnailBase64(captureThumbnailSafely());

        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.addStep(step);
        flowSessionService.persistCurrentState();

        return new StepResponse(step, null, buildPreview(flow));
    }

    @Data
    public static class ScreenBoundaryRequest {
        private String screenName;
    }

    /** Removes the most recently recorded step — recovery from a mis-tap without restarting the whole flow. Does not undo the physical tap already performed on the device. */
    @PostMapping("/step/undo")
    public StepResponse undoLastStep() {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        RecordedStep removed = flow.removeLastStep();
        flowSessionService.persistCurrentState();
        return new StepResponse(removed, null, buildPreview(flow));
    }

    /** QA accepts the "use existing method X?" suggestion shown after a tap/scroll step. */
    @PostMapping("/step/{index}/accept-reuse")
    public Map<String, Object> acceptReuseSuggestion(@PathVariable int index, @RequestBody AcceptReuseRequest request) {
        List<RecordedStep> steps = flowSessionService.requireCurrentFlow().getSteps();
        if (index < 0 || index >= steps.size()) {
            throw new IllegalArgumentException("No step at index " + index);
        }
        steps.get(index).setMatchedExistingMethod(request.getMethodKind());
        flowSessionService.persistCurrentState();
        return Map.of("accepted", true);
    }

    @Data
    public static class AcceptReuseRequest {
        private String methodKind;
    }

    @GetMapping("/current")
    public RecordedFlow currentFlow() {
        return flowSessionService.requireCurrentFlow();
    }

    @GetMapping("/method-catalog")
    public List<ReusableMethod> methodCatalog() {
        return methodCatalogService.all();
    }

    @GetMapping("/target-areas")
    public TargetArea[] targetAreas() {
        return TargetArea.values();
    }

    /** Live preview of the generated Locators/Page classes from the flow recorded so far, with no file write — lets QA catch a bad name/locator mid-recording instead of only at the end. */
    @GetMapping("/preview")
    public PreviewResponse preview() {
        return buildPreview(flowSessionService.requireCurrentFlow());
    }

    private PreviewResponse buildPreview(RecordedFlow flow) {
        TargetArea area = flow.getTargetArea() != null ? flow.getTargetArea() : TargetArea.GOLD;
        var locatorsResult = locatorsClassGenerator.generate(flow, area.getLocatorsPackage());
        var pageResult = pageClassGenerator.generate(flow, locatorsResult.className(), area.getLocatorsPackage(), area.getPagePackage());
        var testResult = testClassGenerator.generate(flow, pageResult.className(), area.getPagePackage(), area.getTestPackage());
        return new PreviewResponse(locatorsResult.fileContent(), pageResult.fileContent(), testResult.fileContent());
    }

    @Data
    public static class PreviewResponse {
        private final String locatorsFileContent;
        private final String pageFileContent;
        private final String testFileContent;
    }

    /**
     * Finishes the flow: re-verifies every step's chosen locator against the device's
     * current screen (skipped per-step if no device is connected — a verification
     * failure here is informational, never a reason to block finishing, since the
     * app may have simply navigated elsewhere since recording), runs codegen for
     * Locators/Page/Test, the style gate, and writes files into the target repo.
     */
    @PostMapping("/finish")
    public FinishResponse finish(@RequestBody FinishRequest request) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        TargetArea area = flow.getTargetArea() != null ? flow.getTargetArea() : TargetArea.GOLD;

        List<String> verificationWarnings = verifyLocatorsIfPossible(flow);

        var locatorsResult = locatorsClassGenerator.generate(flow, area.getLocatorsPackage());
        var pageResult = pageClassGenerator.generate(flow, locatorsResult.className(), area.getLocatorsPackage(), area.getPagePackage());
        var testResult = testClassGenerator.generate(flow, pageResult.className(), area.getPagePackage(), area.getTestPackage());

        List<StyleConformanceChecker.Finding> locatorFindings =
                styleConformanceChecker.checkLocatorsFile(locatorsResult.fileContent());
        List<StyleConformanceChecker.Finding> pageFindings =
                styleConformanceChecker.checkPageFile(pageResult.fileContent());
        List<StyleConformanceChecker.Finding> testFindings =
                styleConformanceChecker.checkTestFile(testResult.fileContent());

        if (styleConformanceChecker.hasBlockingErrors(locatorFindings)
                || styleConformanceChecker.hasBlockingErrors(pageFindings)
                || styleConformanceChecker.hasBlockingErrors(testFindings)) {
            flowSessionService.endFlow();
            return new FinishResponse(null, null, null, locatorFindings, pageFindings, testFindings, verificationWarnings,
                    "Blocking style errors found — files NOT written. Fix generator output and retry.");
        }

        var locatorsWrite = fileWriterService.writeLocatorsClass(
                area.getLocatorsRelativePath(), locatorsResult.className(), locatorsResult.fileContent(), request.isAllowOverwrite());
        var pageWrite = fileWriterService.writePageClass(
                area.getPageRelativePath(), pageResult.className(), pageResult.fileContent(), request.isAllowOverwrite());
        var testWrite = fileWriterService.writeTestClass(
                area.getTestRelativePath(), testResult.className(), testResult.fileContent(), request.isAllowOverwrite());

        flowSessionService.endFlow();

        String message = "Files written. Review the diff in Jar_Frontend_Automation before committing — nothing here runs git.";
        if (locatorsWrite.isCollisionAvoided() || pageWrite.isCollisionAvoided() || testWrite.isCollisionAvoided()) {
            message = "A file with that name already existed, so generated output was written to a "
                    + "*GeneratedDraft.java file instead of overwriting it — review and merge manually, "
                    + "or re-run finish with allowOverwrite=true if you're sure.";
        }

        return new FinishResponse(locatorsWrite.getPath().toString(), pageWrite.getPath().toString(), testWrite.getPath().toString(),
                locatorFindings, pageFindings, testFindings, verificationWarnings, message);
    }

    private List<String> verifyLocatorsIfPossible(RecordedFlow flow) {
        List<String> warnings = new ArrayList<>();

        // Checked here as well as at connect time: the foreground app can change mid-recording,
        // and this is the last moment before locators are written into the target repo.
        String packageWarning = appiumSessionService.foregroundPackageWarning();
        if (packageWarning != null) warnings.add(packageWarning);

        if (!appiumSessionService.isActive()) {
            warnings.add("No active device session — skipped locator verification. Connect a device before finishing to catch stale locators.");
            return warnings;
        }

        for (RecordedStep step : flow.getSteps()) {
            if (step.getCandidateLocators() == null || step.getCandidateLocators().isEmpty()) continue;
            CandidateLocator chosen = step.getCandidateLocators().get(0);
            var result = locatorVerificationService.verify(chosen);
            step.setVerifiedBeforeWrite(result.found());
            if (!result.found()) {
                warnings.add("Step \"" + step.getActionLabel() + "\": " + result.detail()
                        + " — the app may have moved past the screen this was recorded on, which is expected for later steps.");
            }
        }
        return warnings;
    }

    @Data
    public static class FinishRequest {
        private boolean allowOverwrite;
    }

    @Data
    public static class FinishResponse {
        private final String locatorsFilePath;
        private final String pageFilePath;
        private final String testFilePath;
        private final List<StyleConformanceChecker.Finding> locatorFindings;
        private final List<StyleConformanceChecker.Finding> pageFindings;
        private final List<StyleConformanceChecker.Finding> testFindings;
        private final List<String> verificationWarnings;
        private final String message;
    }

    /** A thumbnail failure should never interrupt recording — null just means the step log shows no image for that step. */
    private String captureThumbnailSafely() {
        try {
            return appiumSessionService.captureScreenshotBase64();
        } catch (Exception e) {
            return null;
        }
    }
}
