package com.jar.flowcapture.web;

import com.jar.flowcapture.appium.AppiumSessionService;
import com.jar.flowcapture.capture.*;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.ReusableMethod;
import com.jar.flowcapture.codegen.*;
import com.jar.flowcapture.execute.UtilityExecutor;
import com.jar.flowcapture.mirror.ScreenMirrorService;
import com.jar.flowcapture.replay.FlowReplayService;
import com.jar.flowcapture.replay.ReplayGate;
import com.jar.flowcapture.library.FlowLibraryService;
import com.jar.flowcapture.settings.OutputPathService;
import com.jar.flowcapture.replay.ReplayReport;
import com.jar.flowcapture.reuse.LocatorIndexService;
import com.jar.flowcapture.style.StyleConformanceChecker;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST surface the dashboard talks to: device session lifecycle, recording a flow
 * step at a time, previewing generated code live, and finishing a flow to trigger
 * codegen + verification + the style gate + file write.
 */
@Slf4j
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
    private final ScreenMirrorService screenMirrorService;
    private final ReplayGate replayGate;
    private final OutputPathService outputPathService;
    private final FlowLibraryService flowLibraryService;

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
                                  FlowReplayService flowReplayService,
                                  ScreenMirrorService screenMirrorService,
                                  ReplayGate replayGate,
                                  OutputPathService outputPathService,
                                  FlowLibraryService flowLibraryService) {
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
        this.screenMirrorService = screenMirrorService;
        this.replayGate = replayGate;
        this.outputPathService = outputPathService;
        this.flowLibraryService = flowLibraryService;
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
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("connected", connected);
        if (!connected) return state;

        String warning = appiumSessionService.foregroundPackageWarning();
        if (warning != null) state.put("packageWarning", warning);

        // Set up the fast mirror here rather than at boot: the adb forward it depends on is
        // lost every time the cable is unplugged, and connect is the one path guaranteed to
        // run afterwards.
        state.put("mirror", screenMirrorService.prepare().name());

        // The dashboard maps a click to device coordinates with this, not with the mirrored
        // image's pixel size, which is downscaled on the MJPEG path.
        try {
            int[] size = appiumSessionService.screenSize();
            state.put("screenWidth", size[0]);
            state.put("screenHeight", size[1]);
        } catch (RuntimeException e) {
            log.warn("Could not read the device screen size: {}", e.getMessage());
        }
        return state;
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
        replayGate.clear();
        RecordedFlow flow = flowSessionService.startNewFlow(request.getFlowName());
        flow.setTargetArea(request.getTargetArea() != null ? request.getTargetArea() : TargetArea.GOLD);
        if (request.getGenerateAssertions() != null) {
            flow.setGenerateAssertions(request.getGenerateAssertions());
        }
        flowSessionService.persistCurrentState();
        return flow;
    }

    @Data
    public static class StartFlowRequest {
        private String flowName;
        private TargetArea targetArea;
        /** Boxed so "not sent" is distinguishable from "sent false" and can keep the default. */
        private Boolean generateAssertions;
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
        replayGate.clear();
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

        return new StepResponse(step, suggestion, buildPreview(flow), existingLocatorFor(flow, step),
                locatorWarningFor(resolved.candidates()));
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
        /**
         * Non-null when the element this step captured has no locator that identifies it
         * uniquely. Said at capture time because that is when the screen is still in front
         * of the person who can fix it; left unsaid, it surfaces much later as a replay that
         * passes without doing anything, or as a flaky generated test.
         */
        private final String locatorWarning;

        public StepResponse(RecordedStep step, ReusableMethod suggestedExistingMethod, PreviewResponse preview) {
            this(step, suggestedExistingMethod, preview, null, null);
        }

        public StepResponse(RecordedStep step, ReusableMethod suggestedExistingMethod,
                            PreviewResponse preview, ExistingLocatorRef existingLocator) {
            this(step, suggestedExistingMethod, preview, existingLocator, null);
        }

        public StepResponse(RecordedStep step, ReusableMethod suggestedExistingMethod,
                            PreviewResponse preview, ExistingLocatorRef existingLocator,
                            String locatorWarning) {
            this.step = step;
            this.suggestedExistingMethod = suggestedExistingMethod;
            this.preview = preview;
            this.existingLocator = existingLocator;
            this.locatorWarning = locatorWarning;
        }
    }

    /**
     * Whether this element can be found again reliably, in one sentence.
     *
     * The ranker already counts each candidate against the captured screen, and when nothing
     * resolves uniquely it returns the ambiguous ones anyway rather than offering nothing.
     * That is the right call, but until now the count went no further than the data model:
     * a tap whose only locator matched 104 elements was recorded, generated and replayed with
     * nothing ever saying so.
     */
    private String locatorWarningFor(List<CandidateLocator> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        CandidateLocator best = candidates.stream()
                .filter(c -> c.getStrategy() != CandidateLocator.Strategy.INDEX_BASED)
                .findFirst().orElse(null);
        if (best == null) {
            return "This element exposes no id, text or content-description — the only locator "
                    + "available is an index placeholder you have to fill in by hand. "
                    + "Open the Inspector to pick a usable one.";
        }
        if (best.isAmbiguous()) {
            return "The best locator for this element matches " + best.getMatchCount()
                    + " elements on this screen, so a click can land on the wrong one. "
                    + "Open the Inspector and pick a locator that matches exactly one.";
        }
        return null;
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

        return new StepResponse(step, null, buildPreview(flow), existingLocatorFor(flow, step),
                locatorWarningFor(resolved.candidates()));
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
     * Adds a step at a chosen position WITHOUT performing it on the device.
     *
     * This is the editing counterpart of /step/utility, and the difference is deliberate.
     * Recording a step means doing it: the method runs, the app moves, and the step describes
     * what happened. Inserting one between two existing steps cannot mean that — the phone is
     * not on the screen that step belongs to, so running it there would either fail or, worse,
     * do something to a screen the flow never visits at that point.
     *
     * So nothing runs. The flow gains a step, the generated code gains a line, and whether the
     * sequence still works is a question for Replay — which can answer it, because it starts
     * from the beginning.
     */
    @PostMapping("/step/insert")
    public StepResponse insertStep(@RequestBody InsertStepRequest request) {
        ReusableMethod method = methodCatalogService.byKind(request.getMethodKind())
                .orElseThrow(() -> new IllegalArgumentException("Unknown utility method: " + request.getMethodKind()));

        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        UtilityArgs args = request.getArgs() == null ? new UtilityArgs() : request.getArgs();
        applyPresetArgs(method, args);
        nameElementTargets(flow, method, args);

        RecordedStep step = new RecordedStep();
        step.setActionType(RecordedStep.ActionType.UTILITY_CALL);
        step.setActionLabel(method.getHumanDescription());
        step.setUtilityMethodKind(method.getKind().name());
        step.setUtilityArgs(args);
        // No thumbnail: the screen in front of us is not the screen this step belongs to, and a
        // picture of the wrong screen is worse than none.

        flow.insertStep(request.getAt(), step);
        flowSessionService.persistCurrentState();
        return new StepResponse(step, null, buildPreview(flow));
    }

    @Data
    public static class InsertStepRequest {
        /** 0 puts the step before the first one; a value equal to the step count appends. */
        private int at;
        private String methodKind;
        private UtilityArgs args;
    }

    /**
     * Wraps a run of steps in try/catch.
     *
     * Nothing is executed and nothing changes on the phone — this is purely a statement about
     * how the generated test should treat a failure in those steps. Replay honours it too, so
     * the check and the generated code agree about which steps are allowed to be missing.
     */
    @PostMapping("/try-block")
    public StepResponse wrapInTryBlock(@RequestBody TryBlockRequest request) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        TryBlock block = new TryBlock();
        block.setOnFailure(request.getOnFailure() == null ? TryBlock.OnFailure.CONTINUE : request.getOnFailure());
        block.setNote(request.getNote());
        if (block.getOnFailure() == TryBlock.OnFailure.FAIL_WITH_MESSAGE
                && (block.getNote() == null || block.getNote().isBlank())) {
            throw new IllegalArgumentException(
                    "Say what went wrong — the whole point of this option is a better message than the driver's");
        }
        // Re-wrapping steps that already belong to a block moves them out of it; a block left
        // with no steps would otherwise keep generating an empty try/catch.
        flow.wrapSteps(request.getFrom(), request.getTo(), block);
        if (block.getOnFailure() == TryBlock.OnFailure.RUN_RECOVERY_STEPS) {
            if (request.getCatchFrom() == null || request.getCatchTo() == null) {
                throw new IllegalArgumentException("Pick the steps that should run instead when the try fails");
            }
            flow.assignToCatch(request.getCatchFrom(), request.getCatchTo(), block.getId());
            if (flow.recoveryStepsFor(block.getId()).isEmpty()) {
                throw new IllegalArgumentException("A backup route with no steps in it would do nothing");
            }
        }
        flow.pruneEmptyTryBlocks();
        flowSessionService.persistCurrentState();
        return new StepResponse(flow.getSteps().get(request.getFrom()), null, buildPreview(flow));
    }

    @DeleteMapping("/try-block/{id}")
    public StepResponse removeTryBlock(@PathVariable String id) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.unwrap(id);
        flowSessionService.persistCurrentState();
        return new StepResponse(null, null, buildPreview(flow));
    }

    @Data
    public static class TryBlockRequest {
        /** 0-based, inclusive on both ends. */
        private int from;
        private int to;
        private TryBlock.OnFailure onFailure;
        private String note;
        /** Optional: the steps to run instead when the try fails — the backup route. */
        private Integer catchFrom;
        private Integer catchTo;
    }

    /**
     * Moves a step to another position — what dragging it in the list does.
     *
     * Nothing runs on the device. Reordering a recorded flow is an edit, and whether the
     * steps still work in the new order is a question only Replay can answer.
     */
    @PostMapping("/step/{index}/move")
    public StepResponse moveStep(@PathVariable int index, @RequestBody MoveStepRequest request) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.moveStep(index, request.getTo());
        flowSessionService.persistCurrentState();
        return new StepResponse(flow.getSteps().get(request.getTo()), null, buildPreview(flow));
    }

    @Data
    public static class MoveStepRequest {
        /** 0-based position the step should end up at. */
        private int to;
    }

    /** The methods offered for insertion between steps: pure timing, nothing that moves the app. */
    @GetMapping("/insertable-waits")
    public List<ReusableMethod> insertableWaits() {
        return methodCatalogService.insertableWaits();
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
        ReplayReport report = flowReplayService.replay(flow, restart);
        // A manual replay counts towards the finish gate: having just watched the flow run
        // clean, being made to sit through it again before generating would be absurd.
        replayGate.record(flow, report);
        return report;
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
            default -> candidates.addAll(textCandidates(value));
        }

        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        return new ResolveElementResponse(candidates,
                locatorIndexService.findExisting(candidates, generating).orElse(null));
    }

    /**
     * Every plausible way a typed label identifies an element, best-first.
     *
     * QA types what they can SEE, which is not necessarily what the element exposes. On this
     * app in particular the visible label is often a Compose content-desc rather than a text
     * attribute, exact matches miss when the label wraps or carries a suffix ("Offers" inside
     * "View all offers"), and the capitalisation on screen is a guess. Each of these is a
     * separate reason a correct-looking description finds nothing, so all of them get a
     * candidate — and the one that actually matches on the device is promoted to the front
     * before codegen, so the generated locator is the proven one rather than the first guess.
     */
    private List<CandidateLocator> textCandidates(String value) {
        String escaped = value.replace("\"", "\\\"");
        List<CandidateLocator> candidates = new ArrayList<>();
        candidates.add(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH,
                "//*[@text=\"" + escaped + "\"]", null));
        candidates.add(new CandidateLocator(CandidateLocator.Strategy.CONTENT_DESC, value, null));
        candidates.add(new CandidateLocator(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR,
                "new UiSelector().textContains(\"" + escaped + "\")", null));
        candidates.add(new CandidateLocator(CandidateLocator.Strategy.EXACT_TEXT_XPATH,
                "//*[contains(@content-desc,\"" + escaped + "\")]", null));
        // Last resort: same substring, ignoring case. Quoted so a label containing regex
        // metacharacters ("Save ₹500+", "24K gold (live)") matches literally.
        candidates.add(new CandidateLocator(CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR,
                "new UiSelector().textMatches(\"(?i).*\\\\Q" + escaped + "\\\\E.*\")", null));
        return candidates;
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

    /**
     * QA picked a locator in the inspector for an element argument. Wraps it the same way a
     * tap-resolved element is wrapped, so the param modal treats both identically, and still
     * checks whether the repo already declares it — a hand-picked locator is just as worth
     * reusing as a captured one.
     */
    @PostMapping("/adopt-locator")
    public ResolveElementResponse adoptLocator(@RequestBody CandidateLocator chosen) {
        if (chosen == null || chosen.getValue() == null || chosen.getValue().isBlank()) {
            throw new IllegalArgumentException("Pick a locator first");
        }
        List<CandidateLocator> candidates = List.of(chosen);
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        String generating = NameUtils.toClassName(flow.getFlowName()) + "Locators";
        return new ResolveElementResponse(candidates,
                locatorIndexService.findExisting(candidates, generating).orElse(null));
    }

    /**
     * Replaces a recorded step's locator with one QA chose in the inspector.
     *
     * The chosen locator becomes the only candidate rather than being pushed to the front of
     * the ranked list: a deliberate choice should not be silently fallen back from. Any
     * earlier "reuse the repo's declaration" decision is dropped, because it referred to the
     * locator being replaced.
     */
    @PostMapping("/step/{index}/locator")
    public StepResponse setStepLocator(@PathVariable int index, @RequestBody CandidateLocator chosen) {
        if (chosen == null || chosen.getValue() == null || chosen.getValue().isBlank()) {
            throw new IllegalArgumentException("Pick a locator first");
        }
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        List<RecordedStep> steps = flow.getSteps();
        if (index < 0 || index >= steps.size()) {
            throw new IllegalArgumentException("No step at position " + (index + 1)
                    + " — this flow has " + steps.size() + " step(s)");
        }
        RecordedStep step = steps.get(index);
        if (step.getCandidateLocators() == null || step.getCandidateLocators().isEmpty()) {
            throw new IllegalArgumentException("Step " + (index + 1) + " has no element of its own to re-point");
        }
        step.setCandidateLocators(new ArrayList<>(List.of(chosen)));
        step.setReusedLocator(null);
        step.setVerifiedBeforeWrite(null);
        flowSessionService.persistCurrentState();
        // Warn here too: the Inspector shows the match count next to every candidate, but a
        // deliberate choice of an ambiguous one should still be said out loud afterwards.
        return new StepResponse(step, null, buildPreview(flow), existingLocatorFor(flow, step),
                locatorWarningFor(step.getCandidateLocators()));
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

    /**
     * Removes one step from anywhere in the flow, not just the end.
     *
     * Deliberately does not touch the device: the app is wherever the recording left it, and
     * silently trying to "undo" a tap would be guesswork. Deleting a step in the middle can
     * leave the remaining ones depending on state that is no longer produced — Replay is what
     * tells you whether it did, so the dashboard points there rather than refusing the delete.
     */
    @DeleteMapping("/step/{index}")
    public StepResponse deleteStep(@PathVariable int index) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        RecordedStep removed = flow.removeStep(index);
        // Removing the last step of a try block would otherwise leave the block behind,
        // generating an empty try/catch around nothing.
        flow.pruneEmptyTryBlocks();
        flowSessionService.persistCurrentState();
        return new StepResponse(removed, null, buildPreview(flow));
    }

    /**
     * Toggles assertions mid-recording. It is a live setting rather than a finish-time one so
     * the code preview shows the real output as QA records — deciding whether to keep
     * assertions is much easier when you can see the assertion.
     */
    @PostMapping("/assertions")
    public StepResponse setAssertions(@RequestBody AssertionsRequest request) {
        RecordedFlow flow = flowSessionService.requireCurrentFlow();
        flow.setGenerateAssertions(request.isEnabled());
        flowSessionService.persistCurrentState();
        return new StepResponse(null, null, buildPreview(flow));
    }

    @Data
    public static class AssertionsRequest {
        private boolean enabled;
    }

    /**
     * Where generated files land. Exposed because QA works across more than one checkout, and
     * the old launch-time-only setting meant a recording could go into the wrong clone with
     * nothing on screen to suggest it would.
     */
    @GetMapping("/output")
    public Map<String, Object> outputPath(@RequestParam(required = false) TargetArea area) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        String path = outputPathService.currentPath();
        state.put("repoPath", path);
        String warning = outputPathService.warningFor(path);
        if (warning != null) state.put("warning", warning);
        state.put("destinations", outputPathService.destinationsFor(area == null ? TargetArea.GOLD : area));
        return state;
    }

    @PostMapping("/output")
    public Map<String, Object> setOutputPath(@RequestBody OutputPathRequest request) {
        outputPathService.setPath(request.getRepoPath());
        // The reuse index is built from the target repo, so a different repo means a different
        // set of existing locators — leaving the old index loaded would offer reuse of
        // locators that do not exist in the repo now being written to.
        locatorIndexService.refresh();
        return outputPath(request.getArea());
    }

    @Data
    public static class OutputPathRequest {
        private String repoPath;
        private TargetArea area;
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

        // The gate. Generating a test that has never run end to end is how a flaky test reaches
        // the repo, so nothing is written until the whole sequence has passed on the device.
        // The flow stays open on failure — QA fixes a step and finishes again, rather than
        // losing the recording.
        ReplayReport gateReport = runFinishGate(flow, request);
        if (gateReport != null && !gateReport.isSuccess()) {
            return FinishResponse.blockedByReplay(gateReport);
        }

        List<String> verificationWarnings = verifyLocatorsIfPossible(flow);

        var locatorsResult = locatorsClassGenerator.generate(flow, area.getLocatorsPackage());
        var pageResult = pageClassGenerator.generate(flow, locatorsResult.className(), area.getLocatorsPackage(), area.getPagePackage());
        var testResult = testClassGenerator.generate(flow, pageResult.className(), area.getPagePackage(), area.getTestPackage());

        // Hand edits replace the generated body but keep the generated class name, because the
        // name decides the filename and the three files reference each other by it. Renaming a
        // class in the editor would otherwise write it to a path that does not match.
        String locatorsContent = contentOrEdited(locatorsResult.fileContent(), request.getLocatorsFileContent());
        String pageContent = contentOrEdited(pageResult.fileContent(), request.getPageFileContent());
        String testContent = contentOrEdited(testResult.fileContent(), request.getTestFileContent());

        // The style gate runs on what will actually be written, edits included — an edited
        // file that breaks repo convention must be caught just as a generated one would be.
        List<StyleConformanceChecker.Finding> locatorFindings =
                styleConformanceChecker.checkLocatorsFile(locatorsContent);
        List<StyleConformanceChecker.Finding> pageFindings =
                styleConformanceChecker.checkPageFile(pageContent);
        List<StyleConformanceChecker.Finding> testFindings =
                styleConformanceChecker.checkTestFile(testContent);

        if (styleConformanceChecker.hasBlockingErrors(locatorFindings)
                || styleConformanceChecker.hasBlockingErrors(pageFindings)
                || styleConformanceChecker.hasBlockingErrors(testFindings)) {
            flowSessionService.endFlow();
            replayGate.clear();
            return new FinishResponse(null, null, null, locatorFindings, pageFindings, testFindings, verificationWarnings,
                    "Blocking style errors found — files NOT written. Fix generator output and retry.", gateReport);
        }

        // What this tool wrote to these three files last time it generated THIS flow, if it
        // ever did. Without it, regenerating a re-opened flow could only ever produce another
        // *GeneratedDraft.java — the file you actually run would never change.
        var previous = flowLibraryService.load(flow.getId()).orElse(null);

        var locatorsWrite = fileWriterService.writeLocatorsClass(
                area.getLocatorsRelativePath(), locatorsResult.className(), locatorsContent,
                request.isAllowOverwrite(), hashOf(previous == null ? null : previous.getLocatorsFile()));
        var pageWrite = fileWriterService.writePageClass(
                area.getPageRelativePath(), pageResult.className(), pageContent,
                request.isAllowOverwrite(), hashOf(previous == null ? null : previous.getPageFile()));
        var testWrite = fileWriterService.writeTestClass(
                area.getTestRelativePath(), testResult.className(), testContent,
                request.isAllowOverwrite(), hashOf(previous == null ? null : previous.getTestFile()));

        archiveFinishedFlow(flow, locatorsWrite, locatorsContent, pageWrite, pageContent, testWrite, testContent);

        flowSessionService.endFlow();
        replayGate.clear();

        String message = "Files written. Review the diff in Jar_Frontend_Automation before committing — nothing here runs git.";
        if (locatorsWrite.isCollisionAvoided() || pageWrite.isCollisionAvoided() || testWrite.isCollisionAvoided()) {
            message = "A file with that name already existed, so generated output was written to a "
                    + "*GeneratedDraft.java file instead of overwriting it — review and merge manually, "
                    + "or re-run finish with allowOverwrite=true if you're sure.";
        }

        return new FinishResponse(locatorsWrite.getPath().toString(), pageWrite.getPath().toString(), testWrite.getPath().toString(),
                locatorFindings, pageFindings, testFindings, verificationWarnings, message, gateReport);
    }

    /**
     * Returns the replay that satisfied (or failed) the gate, or null when the gate did not run.
     *
     * Skipped in three cases, each for a real reason: QA explicitly overrode it, this exact
     * sequence already replayed clean, or there is no device to replay against — in which case
     * blocking would strand a recording that QA may well want written anyway. "Not run" and
     * "ran and passed" are deliberately both allowed through; only an actual failure blocks.
     */
    private ReplayReport runFinishGate(RecordedFlow flow, FinishRequest request) {
        if (request.isSkipReplay()) {
            log.info("Replay gate skipped by request for '{}'", flow.getFlowName());
            return null;
        }
        if (replayGate.hasPassed(flow)) {
            log.info("Replay gate already satisfied for '{}' — not re-running", flow.getFlowName());
            return null;
        }
        if (!appiumSessionService.isActive() || flow.getSteps().isEmpty()) {
            return null;
        }
        // Relaunch first, so the replay starts from the app's own startup path rather than
        // from whatever exploring left behind. Worth knowing: terminate+activate does NOT
        // guarantee a clean home screen — Android restores the task, so the app usually comes
        // back on the screen it was on, scroll position included. Verified on a real device.
        // The gate therefore checks that the flow runs from where the phone IS, which is the
        // honest thing to check; it is not a substitute for a fresh-install run in CI.
        ReplayReport report = flowReplayService.replay(flow, true);
        replayGate.record(flow, report);
        return report;
    }

    private String hashOf(FlowLibraryService.WrittenFile file) {
        return file == null ? null : file.getSha256();
    }

    /**
     * Keeps the recording after the files are written, with a fingerprint of each file.
     *
     * Finishing used to be the end of a flow: the Java landed in the repo and the steps that
     * produced it were dropped, so changing one step later meant recording the whole journey
     * again. Saving it here is what makes a generated test something you can re-open, adjust
     * and regenerate — and the fingerprints are what let that regeneration update its own
     * output without ever overwriting something a person has edited since.
     */
    private void archiveFinishedFlow(RecordedFlow flow,
                                     FileWriterService.WriteResult locatorsWrite, String locatorsContent,
                                     FileWriterService.WriteResult pageWrite, String pageContent,
                                     FileWriterService.WriteResult testWrite, String testContent) {
        var saved = new FlowLibraryService.SavedFlow();
        saved.setFlow(flow);
        saved.setFinishedAt(java.time.Instant.now());
        saved.setLocatorsFile(new FlowLibraryService.WrittenFile(
                locatorsWrite.getPath().toString(), locatorsContent, locatorsWrite.isCollisionAvoided()));
        saved.setPageFile(new FlowLibraryService.WrittenFile(
                pageWrite.getPath().toString(), pageContent, pageWrite.isCollisionAvoided()));
        saved.setTestFile(new FlowLibraryService.WrittenFile(
                testWrite.getPath().toString(), testContent, testWrite.isCollisionAvoided()));
        flowLibraryService.save(saved);
    }

    /** Every finished flow, newest first — the dashboard's "open a past flow" list. */
    @GetMapping("/library")
    public List<FlowLibraryService.Summary> library() {
        return flowLibraryService.list();
    }

    /**
     * Re-opens a finished flow for editing. Refuses while another recording is in progress
     * rather than silently replacing it — losing an unfinished recording to a mis-click is
     * exactly the kind of thing this tool should not do.
     */
    @PostMapping("/library/{id}/open")
    public Map<String, Object> openSavedFlow(@PathVariable String id) {
        if (flowSessionService.hasActiveFlow()) {
            throw new IllegalArgumentException(
                    "Finish or discard the flow you have open before opening another one");
        }
        var saved = flowLibraryService.load(id)
                .orElseThrow(() -> new IllegalArgumentException("No saved flow with id " + id));
        RecordedFlow flow = flowSessionService.adoptFlow(saved.getFlow());
        // The gate belongs to the previous generation of this flow, not to whatever is about
        // to be edited into it.
        replayGate.clear();
        // All three, not just the test: a hand edit to the Page class is the likeliest one of
        // the three to exist, and reporting on the test file alone would promise a clean
        // regeneration that the writer is then going to refuse.
        boolean untouched = flowLibraryService.isUnchangedSinceWrite(saved.getLocatorsFile())
                && flowLibraryService.isUnchangedSinceWrite(saved.getPageFile())
                && flowLibraryService.isUnchangedSinceWrite(saved.getTestFile());
        return Map.of(
                "flow", flow,
                "preview", buildPreview(flow),
                "filesStillUntouched", untouched);
    }

    @DeleteMapping("/library/{id}")
    public Map<String, Object> forgetSavedFlow(@PathVariable String id) {
        flowLibraryService.delete(id);
        return Map.of("deleted", true);
    }

    /** Blank counts as absent: an accidentally cleared editor must not write an empty file. */
    private String contentOrEdited(String generated, String edited) {
        return (edited == null || edited.isBlank()) ? generated : edited;
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

        /**
         * Generate without requiring a passing replay. The escape hatch exists because some
         * flows genuinely cannot replay unattended — anything built around the PhonePe
         * simulator or the Dev Tools payment screen has steps only a human can perform.
         */
        private boolean skipReplay;

        /**
         * Hand-edited file contents from the dashboard's code panel, used verbatim in place
         * of the generated text. Null (the normal case) means "use what the generator
         * produced".
         *
         * Generation gets the boilerplate right but cannot know everything — a better field
         * name, an assertion, a locator the QA engineer knows is more stable than the one
         * captured. Without this the only way to apply such a change is to let the tool write
         * a file and then edit it in the IDE, which loses the style check and the
         * overwrite protection that run here.
         */
        private String locatorsFileContent;
        private String pageFileContent;
        private String testFileContent;
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
        /** The replay that ran as part of this finish, or null when the gate did not run. */
        private final ReplayReport replayReport;

        /**
         * Nothing written and the flow left open on purpose: QA deletes or re-records the
         * failing step and finishes again, instead of losing the whole recording to a gate.
         */
        static FinishResponse blockedByReplay(ReplayReport report) {
            return new FinishResponse(null, null, null, List.of(), List.of(), List.of(), List.of(),
                    "Replay failed — nothing was written. " + report.getSummary()
                            + ". Fix the step and finish again, or generate anyway if this flow "
                            + "needs a human to drive part of it.",
                    report);
        }
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
