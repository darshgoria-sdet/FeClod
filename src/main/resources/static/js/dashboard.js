// Flow Capture Studio dashboard client.
// Handles: SSE screen mirror, click -> device-pixel mapping, actually performing the
// tap/input/scroll on the real device (this is a remote control, not just an observer),
// step recording with thumbnails, the "use existing method?" prompt, undo, live
// generated-code preview, resuming a persisted session, and triggering codegen on finish.

const state = {
    connected: false,
    recording: false,
    eventSource: null,
    pendingClick: null, // {x, y} in device pixel space, awaiting a label before posting
    pendingAction: null, // "tap" | "input"
    activePreviewTab: 'locators',
    lastPreview: null,
    // Real device resolution, from the server. Never derived from the mirrored image: the
    // MJPEG broadcaster downscales frames (600x1335 for a 1200x2670 screen), so using the
    // image's own size would halve every tap coordinate.
    deviceWidth: 0,
    deviceHeight: 0,
    // The flow's try/catch blocks, so a step row can show the block it belongs to.
    tryBlocks: [],
    // Hand edits per file, keyed by tab. A file with an entry here is no longer refreshed
    // from the generator — otherwise the next recorded step would silently discard the edit.
    edits: {},
};

const el = (id) => document.getElementById(id);

const screenImg = el('screenImg');
const connectBtn = el('connectBtn');
const disconnectBtn = el('disconnectBtn');
const connectionStatus = el('connectionStatus');
const startFlowBtn = el('startFlowBtn');
const finishFlowBtn = el('finishFlowBtn');
const flowNameInput = el('flowNameInput');
const targetAreaSelect = el('targetAreaSelect');
const stepsList = el('stepsList');
const scrollDownBtn = el('scrollDownBtn');
const scrollUpBtn = el('scrollUpBtn');
const newScreenBtn = el('newScreenBtn');
const undoBtn = el('undoBtn');
const suggestionBox = el('suggestionBox');
const suggestionText = el('suggestionText');
const acceptSuggestionBtn = el('acceptSuggestionBtn');
const declineSuggestionBtn = el('declineSuggestionBtn');
const resultBox = el('resultBox');
const resultText = el('resultText');
const labelModal = el('labelModal');
const modalPrompt = el('modalPrompt');
const labelInput = el('labelInput');
const inputValueField = el('inputValueField');
const labelConfirmBtn = el('labelConfirmBtn');
const labelCancelBtn = el('labelCancelBtn');
const previewCode = el('previewCode');
const previewTabs = document.querySelectorAll('.preview-tab');
const editedFlag = el('editedFlag');
const revertPreviewBtn = el('revertPreviewBtn');
const resumeBanner = el('resumeBanner');
const resumeText = el('resumeText');
const resumeBtn = el('resumeBtn');
const discardResumeBtn = el('discardResumeBtn');
const actionGroups = el('actionGroups');
const actionSearch = el('actionSearch');
const actionsEmpty = el('actionsEmpty');
const actionCount = el('actionCount');
const stepCount = el('stepCount');
const stepsEmpty = el('stepsEmpty');
// Declared up here, not with the other insert-modal refs at the foot of the file:
// updateStepCount() touches it during init, before those would have initialised.
const appendWaitBtn = el('appendWaitBtn');
const pickOverlay = el('pickOverlay');
const pickOverlayText = el('pickOverlayText');
const paneTabs = document.querySelectorAll('.pane-tab');
const locatorIndexStatus = el('locatorIndexStatus');
const paramModal = el('paramModal');
const paramModalTitle = el('paramModalTitle');
const paramModalDesc = el('paramModalDesc');
const paramFields = el('paramFields');
const paramConfirmBtn = el('paramConfirmBtn');
const paramCancelBtn = el('paramCancelBtn');
const reuseModal = el('reuseModal');
const reuseDetail = el('reuseDetail');
const reuseAcceptBtn = el('reuseAcceptBtn');
const reuseDeclineBtn = el('reuseDeclineBtn');
const replayBtn = el('replayBtn');
const replayModal = el('replayModal');
const replayRestartBtn = el('replayRestartBtn');
const replayHereBtn = el('replayHereBtn');
const replayCancelBtn = el('replayCancelBtn');
const replayBox = el('replayBox');
const replaySummary = el('replaySummary');
const replayResults = el('replayResults');
const replayCloseBtn = el('replayCloseBtn');
const finishModal = el('finishModal');
const finishWithReplayBtn = el('finishWithReplayBtn');
const finishSkipReplayBtn = el('finishSkipReplayBtn');
const finishCancelBtn = el('finishCancelBtn');
const assertionsToggle = el('assertionsToggle');
const outputPathText = el('outputPathText');
const changeOutputBtn = el('changeOutputBtn');
const outputModal = el('outputModal');
const outputPathInput = el('outputPathInput');
const outputDestinations = el('outputDestinations');
const outputSaveBtn = el('outputSaveBtn');
const outputCancelBtn = el('outputCancelBtn');

async function api(path, method = 'GET', body) {
    const res = await fetch(path, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
        // GlobalExceptionHandler on the backend returns {"error": "<real message>"} for
        // every /api/** failure — surface that instead of a generic HTTP status line.
        let message = `${method} ${path} failed: ${res.status}`;
        try {
            const errorBody = await res.json();
            if (errorBody && errorBody.error) message = errorBody.error;
        } catch {
            // response wasn't JSON (e.g. the servlet container's own error page) — keep the generic message
        }
        throw new Error(message);
    }
    const contentType = res.headers.get('content-type') || '';
    return contentType.includes('application/json') ? res.json() : null;
}

const errorBanner = el('errorBanner');
const warningBanner = el('warningBanner');
const warningText = el('warningText');
const dismissWarningBtn = el('dismissWarningBtn');

function showError(message) {
    errorBanner.textContent = message;
    errorBanner.classList.remove('hidden');
}

function clearError() {
    errorBanner.classList.add('hidden');
}

/**
 * Advisory warnings live in their own banner: clearError() runs before every action, and a
 * package mismatch must survive that — it stays true until the app in the foreground changes.
 */
function showWarning(message) {
    if (!message) return;
    warningText.textContent = message;
    warningBanner.classList.remove('hidden');
}

function clearWarning() {
    warningBanner.classList.add('hidden');
}

dismissWarningBtn.addEventListener('click', clearWarning);

/** Wraps a click handler so any failure (backend error, bad click target, etc.) shows in the error banner instead of vanishing into an unhandled promise rejection. */
function guarded(handler) {
    return async (...args) => {
        try {
            clearError();
            await handler(...args);
        } catch (e) {
            showError(e.message || String(e));
        }
    };
}

// --- Startup: populate target areas, check for a resumable session ---

(async function init() {
    try {
        const areas = await api('/api/flow/target-areas');
        targetAreaSelect.innerHTML = areas.map(a => `<option value="${a}">${a}</option>`).join('');
    } catch (e) {
        showError('Could not load target areas: ' + e.message);
    }

    // The backend may already hold a live device session (e.g. this is a page reload, or a
    // previous tab connected). Sync to it rather than showing a misleading "not connected".
    try {
        const status = await api('/api/flow/device/status');
        if (status.connected) setConnected(true, status);
        showWarning(status.packageWarning);
    } catch (e) {
        // non-fatal — worst case the user clicks Connect, which is idempotent server-side
    }

    try {
        await refreshOutputPath();
    } catch (e) {
        showError('Could not read the output path: ' + e.message);
    }

    try {
        await loadMethodCatalogue();
    } catch (e) {
        showError('Could not load the method catalogue: ' + e.message);
    }

    try {
        const resumable = await api('/api/flow/resumable');
        if (resumable.hasActiveFlow) {
            resumeText.textContent = `A previous recording ("${resumable.flow.flowName}", ${resumable.flow.steps.length} step(s)) is still in progress.`;
            resumeBanner.classList.remove('hidden');
        }
    } catch (e) {
        // non-fatal — resuming is a convenience, not required to use the tool
    }
})();

resumeBtn.addEventListener('click', guarded(async () => {
    const current = await api('/api/flow/current');
    resumeBanner.classList.add('hidden');
    enterRecordingUi(current.flowName);
    assertionsToggle.checked = current.generateAssertions !== false;
    renderSteps(current);
    await refreshPreview();
}));

discardResumeBtn.addEventListener('click', guarded(async () => {
    await api('/api/flow/discard', 'POST');
    resumeBanner.classList.add('hidden');
}));

function setConnected(connected, deviceInfo) {
    state.connected = connected;
    connectBtn.disabled = connected;
    disconnectBtn.disabled = !connected;
    connectionStatus.textContent = connected ? 'connected' : 'not connected';
    connectionStatus.classList.toggle('live', connected);

    if (deviceInfo && deviceInfo.screenWidth) {
        state.deviceWidth = deviceInfo.screenWidth;
        state.deviceHeight = deviceInfo.screenHeight;
    }
    if (connected) startScreenStream(deviceInfo); else stopScreenStream();
}

/**
 * Two mirrors, and the server says which is live.
 *
 * MJPEG is the device's own broadcaster, piped through as multipart/x-mixed-replace and
 * rendered natively by the <img> — measured at ~10 fps. The screenshot-polling fallback
 * manages ~0.26 fps and, because every frame is a WebDriver command, it also competes with
 * element lookups for the driver.
 */
function startScreenStream(deviceInfo) {
    stopScreenStream();
    const mode = deviceInfo && deviceInfo.mirror;
    if (mode === 'MJPEG') {
        // Cache-buster: without it a reconnect reuses the dead stream from the previous session.
        screenImg.src = '/api/screen-mjpeg?t=' + Date.now();
        state.mirrorMode = 'MJPEG';
        return;
    }
    state.mirrorMode = 'SCREENSHOT_POLLING';
    state.eventSource = new EventSource('/api/screen-stream');
    state.eventSource.addEventListener('frame', (ev) => {
        screenImg.src = 'data:image/png;base64,' + ev.data;
    });
    state.eventSource.onerror = () => {
        // SSE auto-reconnects by default; nothing to do unless the session itself was stopped server-side.
    };
}

function stopScreenStream() {
    if (state.eventSource) {
        state.eventSource.close();
        state.eventSource = null;
    }
    // Pointing src at nothing is what actually closes an in-flight MJPEG connection.
    screenImg.removeAttribute('src');
}

connectBtn.addEventListener('click', guarded(async () => {
    connectBtn.disabled = true;
    connectionStatus.textContent = 'connecting...';
    try {
        const result = await api('/api/flow/device/connect', 'POST');
        setConnected(true, result);
        if (result.packageWarning) showWarning(result.packageWarning); else clearWarning();
    } catch (e) {
        connectionStatus.textContent = 'not connected';
        connectBtn.disabled = false;
        throw e;
    }
}));

disconnectBtn.addEventListener('click', guarded(async () => {
    await api('/api/flow/device/disconnect', 'POST');
    setConnected(false);
}));

function enterRecordingUi(flowName) {
    state.recording = true;
    state.edits = {};
    // The override button renames itself to "Generate anyway" after a blocked finish; a new
    // flow has not been blocked by anything, so it goes back to describing what it does.
    finishSkipReplayBtn.textContent = 'Generate without replaying';
    replayBox.classList.add('hidden');
    flowNameInput.value = flowName;
    stepsList.innerHTML = '';
    resultBox.classList.add('hidden');
    startFlowBtn.disabled = true;
    finishFlowBtn.disabled = false;
    replayBtn.disabled = false;
    scrollDownBtn.disabled = false;
    scrollUpBtn.disabled = false;
    newScreenBtn.disabled = false;
    undoBtn.disabled = false;
    setQuickActionsEnabled(true);
    showPane('stepsPane');
    updateStepCount();
}

function exitRecordingUi() {
    state.recording = false;
    startFlowBtn.disabled = false;
    finishFlowBtn.disabled = true;
    replayBtn.disabled = true;
    scrollDownBtn.disabled = true;
    scrollUpBtn.disabled = true;
    newScreenBtn.disabled = true;
    undoBtn.disabled = true;
    setQuickActionsEnabled(false);
    updateStepCount();
}

startFlowBtn.addEventListener('click', guarded(async () => {
    const flowName = flowNameInput.value.trim();
    if (!flowName) { showError('Enter a flow name first'); return; }
    await api('/api/flow/start', 'POST', {
        flowName, targetArea: targetAreaSelect.value, generateAssertions: assertionsToggle.checked,
    });
    enterRecordingUi(flowName);
    try {
        const status = await api('/api/flow/device/status');
        if (status.packageWarning) showWarning(status.packageWarning); else clearWarning();
    } catch (e) {
        // advisory only — never block starting a recording over it
    }
    await refreshLocatorIndexStatus();
    await refreshPreview();
}));

finishFlowBtn.addEventListener('click', () => {
    if (!state.recording) return;
    finishModal.classList.remove('hidden');
});

finishCancelBtn.addEventListener('click', () => finishModal.classList.add('hidden'));
finishWithReplayBtn.addEventListener('click', guarded(() => runFinish(false)));
finishSkipReplayBtn.addEventListener('click', guarded(() => runFinish(true)));

/**
 * Generating a test that has never run end to end is how a flaky test reaches the repo, so
 * finish replays first and writes nothing on a failure. The flow stays open in that case —
 * QA fixes the step and finishes again rather than losing the recording.
 */
async function runFinish(skipReplay) {
    finishModal.classList.add('hidden');
    showPane('stepsPane');

    if (!skipReplay) {
        replayBox.classList.remove('hidden');
        replayResults.innerHTML = '';
        replaySummary.textContent = 'Replaying before writing anything\u2026 watch the phone.';
        replaySummary.className = 'replay-summary running';
    }

    setDeviceControlsEnabled(false);
    let response;
    try {
        response = await api('/api/flow/finish', 'POST', {
            allowOverwrite: false,
            skipReplay,
            locatorsFileContent: state.edits.locators,
            pageFileContent: state.edits.page,
            testFileContent: state.edits.test,
        });
    } finally {
        setDeviceControlsEnabled(true);
    }

    if (response.replayReport) {
        renderReplayReport(response.replayReport);
    } else if (!skipReplay) {
        replayBox.classList.add('hidden');
    }

    // Blocked by the gate: nothing written, recording still live, so leave the UI in
    // recording mode and offer the override rather than making QA start over.
    if (response.replayReport && !response.replayReport.success) {
        showResult(response);
        finishSkipReplayBtn.textContent = 'Generate anyway';
        finishModal.classList.remove('hidden');
        return;
    }

    state.edits = {};
    exitRecordingUi();
    showResult(response);
}

/** Renders whatever finish came back with — written paths, or why nothing was written. */
function showResult(response) {
    resultBox.classList.remove('hidden');
    const lines = [response.message, ''];
    if (response.locatorsFilePath) lines.push('Locators: ' + response.locatorsFilePath);
    if (response.pageFilePath) lines.push('Page: ' + response.pageFilePath);
    if (response.testFilePath) lines.push('Test: ' + response.testFilePath);

    if (response.verificationWarnings && response.verificationWarnings.length) {
        lines.push('', 'Locator verification:');
        response.verificationWarnings.forEach(w => lines.push('  ' + w));
    }

    const allFindings = [
        ...(response.locatorFindings || []),
        ...(response.pageFindings || []),
        ...(response.testFindings || []),
    ];
    if (allFindings.length) {
        lines.push('', 'Style findings:');
        allFindings.forEach(f => lines.push(`  [${f.severity}] ${f.message}`));
    }
    resultText.textContent = lines.join('\n');
}


// --- Quick actions: existing utilsPackage methods, one click each ---
// The backend is the single source of truth for which methods are offered (only ones it can
// actually execute on the device with no target element), so this list is never hardcoded here.

/**
 * Seven groups covering all 24 catalogued methods. A flat list of pills was impossible to
 * scan once the catalogue passed about ten entries, and the method name alone does not say
 * what it does — so each row carries its description and methods sit under a heading that
 * matches how QA thinks about them ("I need to scroll to something").
 *
 * Any kind not listed falls into "Other", so a new catalogue entry still appears without a
 * change here; it just lands in the catch-all until it is categorised.
 */
const ACTION_GROUPS = [
    { title: 'Navigation', kinds: ['RETURN_TO_HOME', 'NAVIGATE_BACK', 'NAVIGATE_BACK_UNTIL_VISIBLE', 'GO_TO_HOME'] },
    { title: 'Scrolling', kinds: ['SCROLL_DOWN_UNTIL_VISIBLE', 'SCROLL_UP_UNTIL_VISIBLE', 'SCROLL_TILL_END_OF_SECTION'] },
    { title: 'Waiting', kinds: ['WAIT_FOR_VISIBILITY', 'WAIT_FOR_PRESENCE', 'WAIT_FOR_ANY_VISIBLE', 'WAIT_FOR_SECONDS', 'IMPLICIT_WAIT'] },
    { title: 'Gestures', kinds: ['TAP_ELEMENT', 'HOLD_AND_DRAG', 'DRAG_LEFT_TO_RIGHT', 'DRAG_COIN_TO_END'] },
    { title: 'App & context', kinds: ['OPEN_APP', 'CLOSE_APP', 'CLOSE_KEYBOARD', 'SWITCH_TO_WEBVIEW', 'SWITCH_TO_NATIVE'] },
    { title: 'Checks', kinds: ['ASSERT_DOM_LOADED', 'CLICK_AND_VERIFY'] },
    { title: 'Payments & test setup', kinds: ['COMPLETE_PHONEPE_PAYMENT', 'CHANGE_PAYMENT_STATUS'] },
];

let allMethods = [];

/** "ActionUtils.scrollUntilElementFound" -> "scrollUntilElementFound" — the method name is what QA recognises. */
function shortCallName(call) {
    if (!call) return '(unnamed)';
    const dot = call.lastIndexOf('.');
    return dot === -1 ? call : call.slice(dot + 1);
}

async function loadMethodCatalogue() {
    // Both endpoints feed one list; whether a method needs a modal is derived from its own
    // params, not from which endpoint it came from.
    const [quick, parameterised] = await Promise.all([
        api('/api/flow/quick-actions'),
        api('/api/flow/parameterised-actions'),
    ]);
    allMethods = [...quick, ...parameterised];
    actionCount.textContent = String(allMethods.length);
    renderMethods('');
}

function renderMethods(filterText) {
    const needle = (filterText || '').trim().toLowerCase();
    const matches = (m) => !needle
        || displayName(m).toLowerCase().includes(needle)
        || (m.humanDescription || '').toLowerCase().includes(needle)
        || m.kind.toLowerCase().includes(needle);

    const categorised = new Set(ACTION_GROUPS.flatMap(g => g.kinds));
    const groups = ACTION_GROUPS.map(g => ({
        title: g.title,
        methods: g.kinds.map(k => allMethods.find(m => m.kind === k)).filter(Boolean).filter(matches),
    }));
    const leftovers = allMethods.filter(m => !categorised.has(m.kind)).filter(matches);
    if (leftovers.length) groups.push({ title: 'Other', methods: leftovers });

    actionGroups.innerHTML = '';
    let shown = 0;
    groups.filter(g => g.methods.length).forEach(group => {
        shown += group.methods.length;
        const section = document.createElement('div');
        const title = document.createElement('p');
        title.className = 'action-group-title';
        title.textContent = group.title;
        section.appendChild(title);
        group.methods.forEach(method => section.appendChild(buildActionRow(method)));
        actionGroups.appendChild(section);
    });

    actionsEmpty.classList.toggle('hidden', shown > 0);
    setQuickActionsEnabled(state.recording);
}

function buildActionRow(method) {
    const row = document.createElement('button');
    row.className = 'action-row quick-action-btn';
    row.disabled = true;

    const name = document.createElement('span');
    name.className = 'action-name';
    name.textContent = displayName(method);
    row.appendChild(name);

    const desc = document.createElement('span');
    desc.className = 'action-desc';
    desc.textContent = method.humanDescription || '';
    desc.title = method.humanDescription || '';
    row.appendChild(desc);

    // Two things QA needs to know before clicking: will this open a form, and will the tool
    // actually drive the phone or is it only recording what they do by hand.
    if (method.manualOnDevice) {
        row.appendChild(badge('manual', 'manual'));
    }
    if (method.autoResolvedArgs) {
        // Takes an element, but the tool already knows which one — so it is still one click.
        row.appendChild(badge('auto', 'auto'));
    } else if (method.params && method.params.length) {
        row.appendChild(badge('args', method.params.length + ' arg' + (method.params.length > 1 ? 's' : '')));
    }

    row.addEventListener('click', needsModal(method)
        ? () => openParamModal(method)
        : guarded(() => runQuickAction(method)));
    return row;
}

/**
 * A method whose argument is pre-bound shows what it is bound to, e.g.
 * "navigateBackUntilElementFound(profileIcon)". Without it two rows in Navigation read
 * identically, since it genuinely is the same method — only the argument differs.
 */
function displayName(method) {
    const base = shortCallName(method.fullyQualifiedCall);
    return method.presetLocatorField ? base + '(' + method.presetLocatorField + ')' : base;
}

/** A method opens the argument modal only when QA has to supply something themselves. */
function needsModal(method) {
    return !!(method.params && method.params.length) && !method.autoResolvedArgs;
}

function badge(kind, text) {
    const b = document.createElement('span');
    b.className = 'badge ' + kind;
    b.textContent = text;
    return b;
}

/** CLOSE_APP kills the app under test mid-recording — worth a confirm, unlike the rest which are harmless. */
const QUICK_ACTIONS_NEEDING_CONFIRM = {
    CLOSE_APP: 'Close the app? This terminates it on the device — later steps will record against whatever is on screen afterwards.',
};

async function runQuickAction(method) {
    const confirmMessage = QUICK_ACTIONS_NEEDING_CONFIRM[method.kind];
    if (confirmMessage && !window.confirm(confirmMessage)) return;

    const resp = await api('/api/flow/step/utility', 'POST', { methodKind: method.kind });
    addStepToLog(resp.step, displayName(method));
    applyPreview(resp.preview);
}

/* ---------------------------------------------------------------------------
 * Assertions and output path
 * ------------------------------------------------------------------------- */

// Live rather than finish-time, so the code preview shows the real output while recording —
// deciding whether to keep an assertion is much easier when you can see the assertion.
assertionsToggle.addEventListener('change', guarded(async () => {
    if (!state.recording) return;
    const resp = await api('/api/flow/assertions', 'POST', { enabled: assertionsToggle.checked });
    applyPreview(resp.preview);
}));

async function refreshOutputPath() {
    const info = await api('/api/flow/output?area=' + encodeURIComponent(targetAreaSelect.value || 'GOLD'));
    setOutputPathText(info.repoPath);
    if (info.warning) showWarning(info.warning);
    return info;
}

changeOutputBtn.addEventListener('click', guarded(async () => {
    const info = await refreshOutputPath();
    outputPathInput.value = info.repoPath || '';
    renderDestinations(info.destinations);
    outputModal.classList.remove('hidden');
}));

outputCancelBtn.addEventListener('click', () => outputModal.classList.add('hidden'));

outputSaveBtn.addEventListener('click', guarded(async () => {
    const info = await api('/api/flow/output', 'POST', {
        repoPath: outputPathInput.value,
        area: targetAreaSelect.value,
    });
    outputModal.classList.add('hidden');
    setOutputPathText(info.repoPath);
    // Advisory only — a brand-new checkout legitimately has none of the folders yet.
    if (info.warning) showWarning(info.warning); else clearWarning();
    await refreshLocatorIndexStatus();
}));

/**
 * Keeps the tail of the path, which is the part that identifies the checkout — two clones
 * differ at the end, not the beginning. Full path stays in the tooltip.
 */
function setOutputPathText(path) {
    const full = path || '(not set)';
    outputPathText.textContent = full.length > 44 ? '\u2026' + full.slice(-43) : full;
    outputPathText.title = full;
}

/** Shows the three exact destinations, so the choice is unambiguous before anything is written. */
function renderDestinations(destinations) {
    if (!destinations) { outputDestinations.textContent = ''; return; }
    outputDestinations.textContent =
        'Locators \u2192 ' + destinations.locators + '\n'
        + 'Page \u2192 ' + destinations.page + '\n'
        + 'Test \u2192 ' + destinations.test;
}

// Changing area changes the destination folders, so keep the preview honest.
targetAreaSelect.addEventListener('change', guarded(async () => {
    if (!outputModal.classList.contains('hidden')) {
        renderDestinations((await refreshOutputPath()).destinations);
    }
}));

/* ---------------------------------------------------------------------------
 * Replay
 *
 * Recording proves each step worked once, with a human pausing in between. Replay is
 * what proves the flow runs as a flow — which is what the generated test will do. It is
 * offered before "Finish & Generate" on purpose: a flow that cannot replay is a flaky
 * test waiting to be committed.
 * ------------------------------------------------------------------------- */

const STATUS_MARK = { PASSED: '\u2713', FAILED: '\u2717', FAILED_TOLERATED: '!',
    SKIPPED: '\u2013', NOT_REACHED: '\u00b7' };

replayBtn.addEventListener('click', () => {
    if (!state.recording) return;
    replayModal.classList.remove('hidden');
});

replayCancelBtn.addEventListener('click', () => replayModal.classList.add('hidden'));
replayCloseBtn.addEventListener('click', () => replayBox.classList.add('hidden'));
replayRestartBtn.addEventListener('click', guarded(() => runReplay(true)));
replayHereBtn.addEventListener('click', guarded(() => runReplay(false)));

async function runReplay(restartApp) {
    replayModal.classList.add('hidden');
    showPane('stepsPane');
    replayBox.classList.remove('hidden');
    replayResults.innerHTML = '';
    replaySummary.textContent = 'Running\u2026 watch the phone.';
    replaySummary.className = 'replay-summary running';

    // A long flow can take minutes, and every button that drives the device would race it.
    setDeviceControlsEnabled(false);
    try {
        const report = await api('/api/flow/replay', 'POST', { restartApp });
        renderReplayReport(report);
    } finally {
        setDeviceControlsEnabled(true);
    }
}

/** Replay drives the device for as long as it runs; letting QA tap at the same time would corrupt both. */
function setDeviceControlsEnabled(enabled) {
    replayBtn.disabled = !enabled;
    finishFlowBtn.disabled = !enabled;
    scrollDownBtn.disabled = !enabled;
    scrollUpBtn.disabled = !enabled;
    newScreenBtn.disabled = !enabled;
    undoBtn.disabled = !enabled;
    setQuickActionsEnabled(enabled && state.recording);
}

function renderReplayReport(report) {
    replaySummary.textContent = report.summary;
    replaySummary.className = 'replay-summary ' + (report.success ? 'ok' : 'bad');

    replayResults.innerHTML = '';
    report.results.forEach(result => replayResults.appendChild(buildReplayRow(result)));
}

function buildReplayRow(result) {
    const li = document.createElement('li');
    li.className = 'replay-row ' + result.status.toLowerCase();

    const mark = document.createElement('span');
    mark.className = 'replay-mark';
    mark.textContent = STATUS_MARK[result.status] || '?';
    li.appendChild(mark);

    const body = document.createElement('div');
    body.className = 'replay-body';

    const title = document.createElement('span');
    title.className = 'replay-label';
    title.textContent = result.stepNumber + '. ' + result.label;
    body.appendChild(title);

    if (result.message) {
        const msg = document.createElement('span');
        msg.className = 'replay-message';
        msg.textContent = result.message;
        body.appendChild(msg);
    }
    if (result.elapsedMs > 0) {
        const time = document.createElement('span');
        time.className = 'replay-time';
        time.textContent = (result.elapsedMs / 1000).toFixed(1) + 's';
        body.appendChild(time);
    }
    li.appendChild(body);

    // The screenshot is the point of the whole report when a step fails: it shows the screen
    // the step actually landed on, which is usually enough to explain the failure outright.
    if (result.screenshotBase64) {
        const shot = document.createElement('img');
        shot.className = 'replay-shot';
        shot.src = 'data:image/png;base64,' + result.screenshotBase64;
        shot.alt = 'screen after step ' + result.stepNumber;
        li.appendChild(shot);
    }
    return li;
}

function setQuickActionsEnabled(enabled) {
    document.querySelectorAll('.quick-action-btn').forEach(btn => { btn.disabled = !enabled; });
}

actionSearch.addEventListener('input', () => renderMethods(actionSearch.value));

// --- Pane tabs ---

paneTabs.forEach(tab => {
    tab.addEventListener('click', () => {
        paneTabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        document.querySelectorAll('.pane').forEach(p => p.classList.add('hidden'));
        el(tab.dataset.pane).classList.remove('hidden');
    });
});

function showPane(paneId) {
    paneTabs.forEach(t => t.classList.toggle('active', t.dataset.pane === paneId));
    document.querySelectorAll('.pane').forEach(p => p.classList.toggle('hidden', p.id !== paneId));
}

async function refreshLocatorIndexStatus() {
    try {
        const stats = await api('/api/flow/locator-index');
        locatorIndexStatus.textContent = stats.locators > 0
            ? `Reuse index: ${stats.locators} locators across ${stats.files} files.`
            : 'Reuse index empty — check target-repo-path.';
    } catch (e) {
        locatorIndexStatus.textContent = '';
    }
}

// --- Parameterised actions: methods that need a target element / timeout / text ---

// Element arguments are collected by tapping the mirror (or describing an off-screen
// element). pendingParam routes the next mirror tap into the modal instead of recording
// a step of its own.
let activeParamMethod = null;
let collected = null;        // {elements: [...], scalars: [...]}
let pendingParam = null;     // the MethodParam currently awaiting a mirror tap

function openParamModal(method) {
    activeParamMethod = method;
    collected = { elements: [], scalars: [] };
    pendingParam = null;

    paramModalTitle.textContent = shortCallName(method.fullyQualifiedCall);
    paramModalDesc.textContent = method.manualOnDevice
        ? method.humanDescription + ' — recorded only; perform it on the phone yourself.'
        : method.humanDescription;

    paramFields.innerHTML = '';
    method.params.forEach(param => paramFields.appendChild(buildParamField(param)));
    paramModal.classList.remove('hidden');
}

function buildParamField(param) {
    const wrap = document.createElement('div');
    wrap.className = 'param-field';

    const label = document.createElement('label');
    label.textContent = param.prompt;
    wrap.appendChild(label);

    if (param.kind === 'ELEMENT' || param.kind === 'ELEMENT_LIST') {
        wrap.appendChild(buildElementPicker(param));
    } else if (param.kind === 'PAYMENT_STATUS') {
        const select = document.createElement('select');
        select.innerHTML = (param.options || []).map(o => `<option value="${o}">${o}</option>`).join('');
        select.value = param.defaultValue || (param.options || [])[0];
        select.addEventListener('change', () => setScalar(param.name, select.value));
        setScalar(param.name, select.value);
        wrap.appendChild(select);
    } else {
        const input = document.createElement('input');
        input.type = param.kind === 'INT' ? 'number' : 'text';
        if (param.defaultValue) {
            input.value = param.defaultValue;
            setScalar(param.name, param.defaultValue);
        }
        input.addEventListener('input', () => setScalar(param.name, input.value));
        wrap.appendChild(input);
    }
    return wrap;
}

/**
 * An off-screen param cannot be tapped on the mirror — that is the whole reason
 * scrollUntilElementFound exists — so it gets a describe-by-text field instead. On-screen
 * params get a "tap it on the mirror" button.
 */
function buildElementPicker(param) {
    const box = document.createElement('div');
    box.className = 'element-picker';

    const chosen = document.createElement('div');
    chosen.className = 'chosen-elements empty';
    chosen.textContent = 'none chosen yet';

    if (param.offScreen) {
        const kindSelect = document.createElement('select');
        kindSelect.innerHTML = '<option value="TEXT">visible text</option>'
            + '<option value="RESOURCE_ID">resource-id</option>'
            + '<option value="CONTENT_DESC">content-desc</option>';
        const input = document.createElement('input');
        input.placeholder = 'e.g. Rewards';
        const addBtn = document.createElement('button');
        addBtn.textContent = 'Set';
        addBtn.addEventListener('click', guarded(async () => {
            if (!input.value.trim()) { showError('Type something to describe the element'); return; }
            const resp = await api('/api/flow/describe-element', 'POST',
                { kind: kindSelect.value, value: input.value.trim() });
            addElement(param, resp, input.value.trim(), chosen);
        }));
        box.appendChild(kindSelect);
        box.appendChild(input);
        box.appendChild(addBtn);
    } else {
        const tapBtn = document.createElement('button');
        tapBtn.textContent = 'Pick on screen';
        tapBtn.addEventListener('click', () => {
            pendingParam = { param, chosenEl: chosen };
            paramModal.classList.add('hidden');
            // The mirror is the target now, so say so ON the mirror rather than in a banner
            // the user has to look away to read.
            pickOverlayText.textContent = param.prompt;
            pickOverlay.classList.remove('hidden');
        });
        box.appendChild(tapBtn);

        // The same choice, made deliberately: the tree shows every attribute and every
        // candidate's match count, which is what you need when the obvious locator is the
        // one that keeps breaking.
        const inspectBtn = document.createElement('button');
        inspectBtn.textContent = 'Pick in inspector';
        inspectBtn.addEventListener('click', guarded(async () => {
            paramModal.classList.add('hidden');
            await openInspectorFor({ type: 'param', param, chosenEl: chosen },
                'Choosing a locator for: ' + param.prompt);
        }));
        box.appendChild(inspectBtn);
    }
    box.appendChild(chosen);
    return box;
}

function addElement(param, resolved, label, chosenEl) {
    if (param.kind === 'ELEMENT') {
        collected.elements = collected.elements.filter(e => e.paramName !== param.name);
    }
    collected.elements.push({
        paramName: param.name,
        fieldName: toFieldName(label),
        candidateLocators: resolved.candidates,
        reusedFrom: resolved.existingLocator || null,
    });
    const mine = collected.elements.filter(e => e.paramName === param.name);
    chosenEl.classList.remove('empty');
    chosenEl.textContent = mine.map(e => e.fieldName).join(', ');
    if (resolved.existingLocator) {
        chosenEl.textContent += ` (reusing ${resolved.existingLocator.locatorsClassName}.${resolved.existingLocator.fieldName})`;
    }
    clearError();
}

/**
 * Mirrors NameUtils.toFieldName, including the camelCase-hump split and acronym handling.
 * This name is sent to the backend and kept, so a mismatch here is not cosmetic: typing
 * "TotalSavings" produced the field "totalsavings" before the split was added.
 */
function toFieldName(label) {
    const spaced = (label || '')
        .replace(/[^a-zA-Z0-9 ]/g, ' ')
        .replace(/(?<=[a-z0-9])(?=[A-Z])/g, ' ')
        .replace(/(?<=[A-Z])(?=[A-Z][a-z])/g, ' ');
    const words = spaced.trim().split(/\s+/).filter(Boolean);
    if (!words.length) return 'element';
    const cap = (w) => (w.length > 1 && w === w.toUpperCase() && /[A-Z]/.test(w))
        ? w
        : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase();
    const head = words[0].toLowerCase();
    const rest = words.slice(1).map(cap).join('');
    return /^[0-9]/.test(head) ? 'el' + head + rest : head + rest;
}

function setScalar(name, value) {
    collected.scalars = collected.scalars.filter(sc => sc.paramName !== name);
    collected.scalars.push({ paramName: name, value: String(value) });
}

paramCancelBtn.addEventListener('click', () => {
    paramModal.classList.add('hidden');
    pickOverlay.classList.add('hidden');
    activeParamMethod = null;
    pendingParam = null;
});

paramConfirmBtn.addEventListener('click', guarded(async () => {
    const method = activeParamMethod;
    const missing = method.params
        .filter(p => (p.kind === 'ELEMENT' || p.kind === 'ELEMENT_LIST')
            && !collected.elements.some(e => e.paramName === p.name))
        .map(p => p.name);
    if (missing.length) { showError('Still need: ' + missing.join(', ')); return; }

    const resp = await api('/api/flow/step/utility', 'POST',
        { methodKind: method.kind, args: collected });
    paramModal.classList.add('hidden');
    addStepToLog(resp.step, displayName(method));
    applyPreview(resp.preview);
    activeParamMethod = null;
}));

// --- Locator reuse prompt ---

let pendingReuse = null; // {stepIndex, ref}

/** Locator values come back in their Java-source form, so a repo xpath reads as //x[@text=\"Go To Home\"] — show QA the real string instead. */
function unescapeJavaLiteral(value) {
    if (!value) return '';
    return value.replace(/\\"/g, '"').replace(/\\n/g, ' ').replace(/\\\\/g, '\\');
}

function maybeShowReuse(existingLocator) {
    if (!existingLocator) return;
    pendingReuse = { stepIndex: stepsList.children.length - 1, ref: existingLocator };
    const also = (existingLocator.alsoDeclaredIn || []);
    const alsoText = also.length ? ` It is also declared in ${also.join(', ')}.` : '';
    const how = existingLocator.equivalentNotExact
        ? ' (declared there with a different strategy that targets the same element)'
        : '';
    reuseDetail.textContent =
        `${unescapeJavaLiteral(existingLocator.locatorValue)} is already declared as `
        + `${existingLocator.locatorsClassName}.${existingLocator.fieldName}${how}.${alsoText} `
        + 'Reuse it instead of declaring this element again?';
    reuseModal.classList.remove('hidden');
}

reuseAcceptBtn.addEventListener('click', guarded(async () => {
    const resp = await api(`/api/flow/step/${pendingReuse.stepIndex}/reuse-locator`, 'POST', pendingReuse.ref);
    reuseModal.classList.add('hidden');
    applyPreview(resp.preview);
    pendingReuse = null;
}));

reuseDeclineBtn.addEventListener('click', () => {
    reuseModal.classList.add('hidden');
    pendingReuse = null;
});

// --- Click-to-device-pixel mapping ---
// screenImg is rendered with object-fit: contain inside a fixed-aspect wrapper, so the
// rendered image may be letterboxed. We need the actual device resolution to scale a
// click correctly; fetched once per connection via the img's naturalWidth/Height, which
// reflect the real screenshot pixel dimensions since no CSS scaling touches them.
/**
 * Where the mirrored frame is actually drawn inside its box, and what one of its pixels is
 * worth in device pixels. Shared by click mapping and by the inspector's highlight box, so a
 * tap and the rectangle drawn around the element it hits can never disagree.
 */
function imageGeometry() {
    const rect = screenImg.getBoundingClientRect();
    // Aspect ratio comes from the rendered frame (it is correct whatever the scale), but the
    // coordinates themselves are scaled to the REAL device resolution reported by the server.
    const naturalW = screenImg.naturalWidth;
    const naturalH = screenImg.naturalHeight;
    if (!naturalW || !naturalH) return null;

    const renderedAspect = rect.width / rect.height;
    const naturalAspect = naturalW / naturalH;

    let drawnWidth, drawnHeight, offsetX, offsetY;
    if (naturalAspect > renderedAspect) {
        drawnWidth = rect.width;
        drawnHeight = rect.width / naturalAspect;
        offsetX = 0;
        offsetY = (rect.height - drawnHeight) / 2;
    } else {
        drawnHeight = rect.height;
        drawnWidth = rect.height * naturalAspect;
        offsetY = 0;
        offsetX = (rect.width - drawnWidth) / 2;
    }
    return {
        rect, drawnWidth, drawnHeight, offsetX, offsetY,
        deviceW: state.deviceWidth || naturalW,
        deviceH: state.deviceHeight || naturalH,
    };
}

screenImg.addEventListener('click', guarded(async (ev) => {
    if (!state.connected) return;

    const geo = imageGeometry();
    if (!geo) return;
    const { rect, drawnWidth, drawnHeight, offsetX, offsetY, deviceW, deviceH } = geo;

    const clickXInImage = ev.clientX - rect.left - offsetX;
    const clickYInImage = ev.clientY - rect.top - offsetY;
    if (clickXInImage < 0 || clickYInImage < 0 || clickXInImage > drawnWidth || clickYInImage > drawnHeight) {
        return; // clicked in the letterbox padding, not on the actual screen
    }

    const deviceX = Math.round((clickXInImage / drawnWidth) * deviceW);
    const deviceY = Math.round((clickYInImage / drawnHeight) * deviceH);

    // The inspector is a read-only view of the phone. A click there selects the element under
    // the finger and nothing else: it must not drive the device, because the screen being
    // inspected is the screen the answer is on.
    if (inspectorIsOpen() && !pendingParam) {
        await captureTree(deviceX, deviceY);
        return;
    }

    if (!state.recording) return;

    // When a parameterised action is waiting for an element, this tap only identifies that
    // element — it must NOT become a step of its own, and must not drive the device, or the
    // app would navigate away from the very screen the argument lives on.
    if (pendingParam) {
        const resolved = await api('/api/flow/resolve-element', 'POST', { x: deviceX, y: deviceY });
        const label = window.prompt('Name this element (e.g. proceed CTA):');
        if (label) {
            addElement(pendingParam.param, resolved, label, pendingParam.chosenEl);
        }
        pendingParam = null;
        pickOverlay.classList.add('hidden');
        paramModal.classList.remove('hidden');
        return;
    }

    state.pendingClick = { x: deviceX, y: deviceY };
    // Shift+click records a text-input step at this point instead of a tap.
    openLabelModal(ev.shiftKey ? 'input' : 'tap');
}));

function openLabelModal(actionType) {
    state.pendingAction = actionType;
    labelInput.value = '';
    inputValueField.value = '';
    inputValueField.classList.toggle('hidden', actionType !== 'input');
    modalPrompt.textContent = actionType === 'input'
        ? 'Describe this field, and the value to type'
        : 'Describe this action (e.g. "tap abc button")';
    labelModal.classList.remove('hidden');
    labelInput.focus();
}

labelCancelBtn.addEventListener('click', () => {
    labelModal.classList.add('hidden');
    state.pendingClick = null;
});

labelConfirmBtn.addEventListener('click', guarded(async () => {
    const label = labelInput.value.trim();
    if (!label) { showError('Enter a description'); return; }
    const pendingClick = state.pendingClick;
    labelModal.classList.add('hidden');

    try {
        let resp;
        if (state.pendingAction === 'tap') {
            resp = await api('/api/flow/step/tap', 'POST', {
                x: pendingClick.x, y: pendingClick.y, actionLabel: label,
            });
            addStepToLog(resp.step, `Tap: ${label}`);
        } else {
            const value = inputValueField.value;
            resp = await api('/api/flow/step/input', 'POST', {
                x: pendingClick.x, y: pendingClick.y, actionLabel: label, value,
            });
            addStepToLog(resp.step, `Input "${value}" into: ${label}`);
        }
        maybeShowSuggestion(resp.suggestedExistingMethod);
        maybeShowReuse(resp.existingLocator);
        maybeWarnAboutLocator(resp.locatorWarning);
        applyPreview(resp.preview);
    } finally {
        state.pendingClick = null;
    }
}));

scrollDownBtn.addEventListener('click', guarded(async () => {
    const resp = await api('/api/flow/step/scroll', 'POST', { down: true });
    addStepToLog(resp.step, 'Scrolled down');
    maybeShowSuggestion(resp.suggestedExistingMethod);
    applyPreview(resp.preview);
}));

scrollUpBtn.addEventListener('click', guarded(async () => {
    const resp = await api('/api/flow/step/scroll', 'POST', { down: false });
    addStepToLog(resp.step, 'Scrolled up');
    maybeShowSuggestion(resp.suggestedExistingMethod);
    applyPreview(resp.preview);
}));

newScreenBtn.addEventListener('click', guarded(async () => {
    const screenName = prompt('Name this screen (e.g. InstantSaveNewScreen):');
    if (!screenName) return;
    const resp = await api('/api/flow/step/screen-boundary', 'POST', { screenName });
    addStepToLog(resp.step, `--- Screen: ${screenName} ---`);
    applyPreview(resp.preview);
}));

undoBtn.addEventListener('click', guarded(async () => {
    const resp = await api('/api/flow/step/undo', 'POST');
    if (stepsList.lastElementChild) stepsList.removeChild(stepsList.lastElementChild);
    updateStepCount();
    applyPreview(resp.preview);
}));

let pendingSuggestionStepIndex = null;

function maybeShowSuggestion(suggestion) {
    if (!suggestion) return;
    pendingSuggestionStepIndex = stepsList.children.length - 1;
    suggestionText.textContent = `We already have a method for this: ${suggestion.humanDescription}. Use it instead of writing new logic?`;
    suggestionBox.dataset.methodKind = suggestion.kind;
    suggestionBox.classList.remove('hidden');
}

acceptSuggestionBtn.addEventListener('click', guarded(async () => {
    const kind = suggestionBox.dataset.methodKind;
    await api(`/api/flow/step/${pendingSuggestionStepIndex}/accept-reuse`, 'POST', { methodKind: kind });
    suggestionBox.classList.add('hidden');
    await refreshPreview();
}));

declineSuggestionBtn.addEventListener('click', () => {
    suggestionBox.classList.add('hidden');
});

function addStepToLog(step, text) {
    const li = document.createElement('li');
    li.className = 'step-item';

    // Dragging reorders the flow. The handle is explicit rather than making the whole row
    // draggable, because the row also carries four buttons and a text selection people use.
    li.draggable = false;
    const grip = document.createElement('span');
    grip.className = 'step-grip';
    grip.textContent = '\u2630';
    grip.title = 'Drag to move this step';
    grip.addEventListener('mousedown', () => { li.draggable = true; });
    grip.addEventListener('mouseup', () => { li.draggable = false; });
    li.appendChild(grip);
    attachDragHandlers(li);

    // "Insert before this step". It lives inside the row rather than as a divider between
    // rows because deleting a step finds its position with indexOf on this list's children —
    // a divider element would silently shift every index by one.
    const insertBefore = document.createElement('button');
    insertBefore.className = 'step-insert';
    insertBefore.textContent = '+';
    insertBefore.title = 'Insert a wait before this step';
    insertBefore.addEventListener('click', (ev) => {
        ev.stopPropagation();
        openInsertModal([...stepsList.children].indexOf(li));
    });
    li.appendChild(insertBefore);

    const index = document.createElement('span');
    index.className = 'step-index';
    index.textContent = String(stepsList.children.length + 1);
    li.appendChild(index);

    if (step && step.screenshotThumbnailBase64) {
        const img = document.createElement('img');
        img.className = 'step-thumb';
        img.src = 'data:image/png;base64,' + step.screenshotThumbnailBase64;
        li.appendChild(img);
    }

    const span = document.createElement('span');
    span.className = 'step-text';
    span.textContent = text;
    li.appendChild(span);

    // Derived from the step's own data rather than from the response that created it, so the
    // mark survives every re-render — deleting another step must not quietly un-flag this one.
    const risk = locatorRisk(step);
    if (risk) {
        li.classList.add('step-risky');
        const mark = document.createElement('span');
        mark.className = 'step-warn';
        mark.textContent = '\u26a0';
        mark.title = risk;
        li.appendChild(mark);
    }

    // Only steps that own an element can be re-pointed. A scroll or a Back press has no
    // locator of its own, so offering the button there would be a dead end.
    if (step && step.candidateLocators && step.candidateLocators.length) {
        const relocate = document.createElement('button');
        relocate.className = 'step-locator';
        relocate.textContent = 'locator';
        relocate.title = 'Pick a different locator for this element';
        relocate.addEventListener('click', guarded(async () => {
            const index = [...stepsList.children].indexOf(li);
            await retargetStep(index, text);
        }));
        li.appendChild(relocate);
    }

    if (step && step.catchGroup) {
        li.classList.add('step-backup');
        li.title = 'A backup step — runs only if its try block fails';
    }

    const block = blockFor(step);
    if (block) {
        li.classList.add('step-wrapped');
        const tryBtn = document.createElement('button');
        tryBtn.className = 'step-try';
        tryBtn.textContent = 'try';
        tryBtn.title = block.onFailure === 'CONTINUE'
            ? 'In a try/catch that logs and carries on — click to remove the wrapper'
            : 'In a try/catch that fails with your message — click to remove the wrapper';
        tryBtn.addEventListener('click', guarded(async () => {
            const resp = await api('/api/flow/try-block/' + block.id, 'DELETE');
            const current = await api('/api/flow/current');
            renderSteps(current);
            applyPreview(resp.preview);
        }));
        li.appendChild(tryBtn);
    } else {
        const tryBtn = document.createElement('button');
        tryBtn.className = 'step-try';
        tryBtn.textContent = 'try';
        tryBtn.title = 'Wrap this step (and optionally the ones after it) in try/catch';
        tryBtn.addEventListener('click', (ev) => {
            ev.stopPropagation();
            openTryModal([...stepsList.children].indexOf(li));
        });
        li.appendChild(tryBtn);
    }

    const remove = document.createElement('button');
    remove.className = 'step-remove';
    remove.textContent = '\u00d7';
    remove.title = 'Remove this step';
    // Captured at click time, not at render time: every row re-renders after a delete, so an
    // index baked in now would be stale the moment any other row is removed.
    remove.addEventListener('click', guarded(async () => {
        const index = [...stepsList.children].indexOf(li);
        await deleteStep(index, stepsList.children.length);
    }));
    li.appendChild(remove);

    stepsList.appendChild(li);
    updateStepCount();
}

/**
 * Whether this step's element can be found again reliably.
 *
 * A locator that matches many elements resolves to whichever the driver returns first, which
 * is how a tap can "succeed" without touching the thing it was named after. A step reusing a
 * repo locator is not flagged: that declaration is what the generated test will use, and it
 * is the repo's call, not this tool's.
 */
function locatorRisk(step) {
    if (!step || step.reusedLocator) return null;
    const candidates = step.candidateLocators || [];
    if (!candidates.length) return null;
    const best = candidates.find(c => c.strategy !== 'INDEX_BASED');
    if (!best) {
        return 'No usable locator — only an index placeholder. Press "locator" to pick one in the Inspector.';
    }
    if (best.matchCount > 1) {
        return `This element's locator matches ${best.matchCount} elements on the screen it was `
            + 'captured from, so a click can land on the wrong one. Press "locator" to pick a unique one.';
    }
    return null;
}

/** Said once, loudly, at the moment of capture — the screen is still there to fix it on. */
function maybeWarnAboutLocator(warning) {
    if (warning) showWarning(warning);
}

function updateStepCount() {
    const n = stepsList.children.length;
    stepCount.textContent = String(n);
    appendWaitBtn.classList.toggle('hidden', n === 0);
    stepsEmpty.classList.toggle('hidden', n > 0 || !state.recording === false && n > 0);
    stepsEmpty.classList.toggle('hidden', n > 0);
}

function stepLabel(step) {
    if (step.actionType === 'SCREEN_BOUNDARY') {
        return `--- Screen: ${step.screenName || '(unnamed)'} ---`;
    }
    if (step.actionType === 'UTILITY_CALL') {
        // actionLabel here is the catalogue's full humanDescription, far too long for the log
        // line — resolve the method name from the loaded catalogue instead.
        const method = allMethods.find(m => m.kind === step.utilityMethodKind);
        return method ? displayName(method) : 'Utility call';
    }
    return `${step.actionType}: ${step.actionLabel || ''}`;
}

/**
 * Removing a step in the middle can leave the ones after it depending on state that no
 * longer gets produced, so this warns once and then points at Replay — which is the only
 * thing that can actually tell you whether the flow still works. Removing the last step is
 * just undo, so it needs no warning.
 */
async function deleteStep(index, total) {
    if (index < total - 1) {
        const ok = window.confirm(
            `Remove step ${index + 1} of ${total}?\n\n`
            + 'Later steps may depend on what this one did. Nothing is changed on the phone — '
            + 'run Replay afterwards to check the flow still works.');
        if (!ok) return;
    }
    const resp = await api('/api/flow/step/' + index, 'DELETE');
    const current = await api('/api/flow/current');
    renderSteps(current);
    applyPreview(resp.preview);
}

/**
 * @param flow the whole flow, so the step rows can show which try/catch block each step is
 *             in. Passing only the steps would leave the rows unable to say anything about
 *             blocks, and a wrapped step that looks unwrapped is a misleading step list.
 */
function renderSteps(flow) {
    const steps = Array.isArray(flow) ? flow : (flow.steps || []);
    state.tryBlocks = Array.isArray(flow) ? state.tryBlocks : (flow.tryBlocks || []);
    stepsList.innerHTML = '';
    updateStepCount();
    steps.forEach(step => {
        addStepToLog(step, stepLabel(step));
    });
}

// --- Live preview ---

previewTabs.forEach(tab => {
    tab.addEventListener('click', () => {
        previewTabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        state.activePreviewTab = tab.dataset.file;
        renderActivePreviewTab();
    });
});

function applyPreview(preview) {
    if (!preview) return;
    state.lastPreview = preview;
    renderActivePreviewTab();
}

function generatedFor(tab) {
    if (!state.lastPreview) return '';
    return {
        locators: state.lastPreview.locatorsFileContent,
        page: state.lastPreview.pageFileContent,
        test: state.lastPreview.testFileContent,
    }[tab] || '';
}

function renderActivePreviewTab() {
    const tab = state.activePreviewTab;
    const edited = Object.prototype.hasOwnProperty.call(state.edits, tab);
    previewCode.value = edited ? state.edits[tab] : generatedFor(tab);
    editedFlag.classList.toggle('hidden', !edited);
    revertPreviewBtn.classList.toggle('hidden', !edited);
}

// Typing in the panel takes ownership of that file: it stops tracking the generator and is
// what gets written on finish. Recording can continue — later steps still update the other
// files, and the generated version is one click away.
previewCode.addEventListener('input', () => {
    state.edits[state.activePreviewTab] = previewCode.value;
    editedFlag.classList.remove('hidden');
    revertPreviewBtn.classList.remove('hidden');
});

revertPreviewBtn.addEventListener('click', () => {
    delete state.edits[state.activePreviewTab];
    renderActivePreviewTab();
});

async function refreshPreview() {
    try {
        const preview = await api('/api/flow/preview');
        applyPreview(preview);
    } catch (e) {
        // no active flow yet — fine, preview stays at its placeholder text
    }
}

// --- Inspector -------------------------------------------------------------
//
// A read-only view of the device's element tree, with every attribute and — the part the
// ranker cannot decide for you — how many elements each candidate locator actually matches
// on this screen. The generator's first choice is a guess about which attribute survives the
// next release; this is where the person who knows the app overrules it.

const inspectorPane = el('inspectorPane');
const inspectRefreshBtn = el('inspectRefreshBtn');
const inspectSearch = el('inspectSearch');
const inspectRawBtn = el('inspectRawBtn');
const inspectTarget = el('inspectTarget');
const inspectTree = el('inspectTree');
const inspectDetail = el('inspectDetail');
const highlightBox = el('highlightBox');
const benchStrategy = el('benchStrategy');
const benchValue = el('benchValue');
const benchRunBtn = el('benchRunBtn');
const benchResult = el('benchResult');
const rawModal = el('rawModal');
const rawXml = el('rawXml');
const rawCloseBtn = el('rawCloseBtn');

const inspect = {
    nodes: new Map(),   // id -> node
    selectedId: null,
    collapsed: new Set(),
    // What a chosen locator should be applied to: a step being re-pointed, or an element
    // argument a parameterised method is waiting for. Null means "just looking".
    target: null,
};

function inspectorIsOpen() {
    return !inspectorPane.classList.contains('hidden');
}

async function openInspectorFor(target, message) {
    inspect.target = target;
    inspectTarget.textContent = message;
    inspectTarget.classList.remove('hidden');
    showPane('inspectorPane');
    await captureTree();
}

/** Opens the inspector aimed at one recorded step, so picking a locator re-points that step. */
async function retargetStep(index, label) {
    await openInspectorFor({ type: 'step', index },
        `Choosing a locator for step ${index + 1} — ${label}`);
}

function clearInspectorTarget() {
    inspect.target = null;
    inspectTarget.classList.add('hidden');
    inspectTarget.textContent = '';
}

async function captureTree(x, y) {
    if (!state.connected) { showError('Connect the device first'); return; }
    const query = (x === undefined || y === undefined) ? '' : `?x=${x}&y=${y}`;
    const tree = await api('/api/flow/inspect/tree' + query);
    inspect.nodes.clear();
    indexNode(tree.root);
    inspect.selectedId = tree.selectedId !== null && tree.selectedId !== undefined
        ? tree.selectedId
        : (inspect.nodes.has(inspect.selectedId) ? inspect.selectedId : null);
    renderTree();
    if (inspect.selectedId !== null) selectNode(inspect.selectedId, { scroll: true });
    if (!tree.candidatesComputed) {
        showWarning(`This screen has ${tree.nodeCount} elements — too many to pre-count locators for `
            + 'every one. Use the locator bench at the bottom to test a selector instead.');
    }
}

function indexNode(node) {
    inspect.nodes.set(node.id, node);
    node.children.forEach(indexNode);
}

function renderTree() {
    const filter = inspectSearch.value.trim().toLowerCase();
    inspectTree.innerHTML = '';
    const root = inspect.nodes.get(0);
    if (!root) {
        inspectTree.innerHTML = '<p class="empty-note">Press "Capture screen" to read the phone\'s element tree.</p>';
        return;
    }
    if (filter) {
        // A filtered tree is a list of hits, not a shape: hiding non-matching parents while
        // keeping the indentation would be a tree with holes in it, which reads worse than
        // the flat answer to "where is the thing I typed".
        [...inspect.nodes.values()]
            .filter(node => matchesFilter(node, filter))
            .slice(0, 300)
            .forEach(node => inspectTree.appendChild(treeRow(node, true)));
        if (!inspectTree.children.length) {
            inspectTree.innerHTML = '<p class="empty-note">Nothing on this screen matches that.</p>';
        }
        return;
    }
    appendRows(root);
}

function appendRows(node) {
    inspectTree.appendChild(treeRow(node, false));
    if (inspect.collapsed.has(node.id)) return;
    node.children.forEach(appendRows);
}

function matchesFilter(node, filter) {
    return [node.resourceId, node.contentDesc, node.text, node.className]
        .some(v => (v || '').toLowerCase().includes(filter));
}

function treeRow(node, flat) {
    const row = document.createElement('div');
    row.className = 'tree-row';
    if (node.id === inspect.selectedId) row.classList.add('selected');
    row.style.paddingLeft = (flat ? 6 : 6 + node.depth * 11) + 'px';

    const caret = document.createElement('span');
    caret.className = 'tree-caret';
    caret.textContent = node.children.length ? (inspect.collapsed.has(node.id) ? '▸' : '▾') : '';
    if (node.children.length && !flat) {
        caret.addEventListener('click', (ev) => {
            ev.stopPropagation();
            if (inspect.collapsed.has(node.id)) inspect.collapsed.delete(node.id);
            else inspect.collapsed.add(node.id);
            renderTree();
        });
    }
    row.appendChild(caret);

    row.appendChild(span('tree-tag', shortClass(node.className || node.tag)));
    if (node.resourceId) row.appendChild(span('tree-id', '#' + shortId(node.resourceId)));
    const label = node.text || node.contentDesc;
    if (label) row.appendChild(span('tree-text', '"' + truncate(label, 28) + '"'));
    if (!node.resourceId && !label && node.children.length) {
        row.appendChild(span('tree-count', '(' + node.children.length + ')'));
    }

    row.addEventListener('click', () => selectNode(node.id));
    return row;
}

function span(className, text) {
    const s = document.createElement('span');
    s.className = className;
    s.textContent = text;
    return s;
}

/** "android.widget.TextView" reads as "TextView" here; the full value is in the attribute table. */
function shortClass(value) {
    if (!value) return '?';
    const cut = value.lastIndexOf('.');
    return cut >= 0 ? value.slice(cut + 1) : value;
}

/** Drops the "com.app.pkg:id/" prefix so the part that identifies the element is what you read. */
function shortId(value) {
    const cut = value.indexOf(':id/');
    return cut >= 0 ? value.slice(cut + 4) : value;
}

function truncate(value, max) {
    return value.length > max ? value.slice(0, max - 1) + '…' : value;
}

function selectNode(id, options = {}) {
    inspect.selectedId = id;
    [...inspectTree.children].forEach(row => row.classList.remove('selected'));
    renderTree();
    if (options.scroll) {
        const selected = inspectTree.querySelector('.tree-row.selected');
        if (selected) selected.scrollIntoView({ block: 'center' });
    }
    const node = inspect.nodes.get(id);
    renderDetail(node);
    drawHighlight(node);
}

function renderDetail(node) {
    inspectDetail.innerHTML = '';
    if (!node) { inspectDetail.classList.add('hidden'); return; }
    inspectDetail.classList.remove('hidden');

    const title = document.createElement('p');
    title.className = 'detail-title';
    title.textContent = shortClass(node.className || node.tag)
        + (node.resourceId ? ' · ' + shortId(node.resourceId) : '');
    inspectDetail.appendChild(title);

    const attrs = document.createElement('div');
    attrs.className = 'attr-table';
    Object.entries(node.attributes)
        .filter(([, value]) => value !== '' && value !== 'false')
        .forEach(([name, value]) => {
            attrs.appendChild(span('attr-name', name));
            attrs.appendChild(span('attr-value', value));
        });
    attrs.appendChild(span('attr-name', 'xpath'));
    attrs.appendChild(span('attr-value', node.xpath));
    inspectDetail.appendChild(attrs);

    const list = document.createElement('div');
    list.className = 'cand-list';
    const candidates = [...(node.candidates || [])];
    // The absolute path always works right now and almost never survives a release, so it is
    // offered last and labelled for what it is rather than left out — sometimes it is the
    // only thing that identifies an unlabelled Compose node.
    candidates.push({
        strategy: 'EXACT_TEXT_XPATH', value: node.xpath,
        elementClass: node.className, matchCount: 1, absolutePath: true,
    });
    candidates.forEach(candidate => list.appendChild(candidateRow(candidate)));
    inspectDetail.appendChild(list);
}

function candidateRow(candidate) {
    const row = document.createElement('div');
    row.className = 'cand-row';
    const placeholder = candidate.strategy === 'INDEX_BASED';
    if (!placeholder && candidate.matchCount === 1) row.classList.add('unique');
    if (candidate.matchCount > 1) row.classList.add('ambiguous');

    const body = document.createElement('div');
    body.className = 'cand-body';
    body.appendChild(span('cand-value', candidate.value));
    body.appendChild(span('cand-meta ' + metaClass(candidate), candidateMeta(candidate)));
    row.appendChild(body);

    const actions = document.createElement('div');
    actions.className = 'cand-actions';

    const testBtn = document.createElement('button');
    testBtn.textContent = 'Test';
    testBtn.title = 'Run this locator against the device right now';
    testBtn.addEventListener('click', guarded(async () => {
        benchStrategy.value = candidate.strategy;
        benchValue.value = candidate.value;
        await runBench();
    }));
    actions.appendChild(testBtn);

    const useBtn = document.createElement('button');
    useBtn.className = 'primary';
    useBtn.textContent = 'Use';
    useBtn.disabled = placeholder;
    useBtn.title = placeholder
        ? 'Fill in the instance index first'
        : 'Use this locator for the step or argument being chosen';
    useBtn.addEventListener('click', guarded(() => applyLocator(candidate)));
    actions.appendChild(useBtn);

    row.appendChild(actions);
    return row;
}

function metaClass(candidate) {
    if (candidate.strategy === 'INDEX_BASED') return 'bad';
    if (candidate.absolutePath) return 'bad';
    if (candidate.matchCount === 1) return 'good';
    return 'bad';
}

function candidateMeta(candidate) {
    const strategy = candidate.strategy.toLowerCase().replace(/_/g, ' ');
    if (candidate.absolutePath) return strategy + ' · absolute path, breaks on any layout change';
    if (candidate.strategy === 'INDEX_BASED') return strategy + ' · needs an instance number, brittle';
    if (candidate.matchCount === 1) return strategy + ' · matches exactly 1 element';
    if (candidate.matchCount === 0) return strategy + ' · matches nothing on this screen';
    return strategy + ' · matches ' + candidate.matchCount + ' elements — resolves to an arbitrary one';
}

/**
 * Applies the chosen locator wherever the inspector was opened from. With no target it goes
 * into the bench instead of being silently dropped — the user still wanted that string.
 */
async function applyLocator(candidate) {
    const chosen = {
        strategy: candidate.strategy,
        value: candidate.value,
        elementClass: candidate.elementClass || null,
        matchCount: candidate.matchCount || 0,
    };
    const target = inspect.target;

    if (target && target.type === 'step') {
        const resp = await api(`/api/flow/step/${target.index}/locator`, 'POST', chosen);
        const current = await api('/api/flow/current');
        renderSteps(current);
        applyPreview(resp.preview);
        clearInspectorTarget();
        showPane('stepsPane');
        maybeShowReuse(resp.existingLocator);
        maybeWarnAboutLocator(resp.locatorWarning);
        return;
    }

    if (target && target.type === 'param') {
        const label = window.prompt('Name this element (e.g. proceed CTA):');
        if (!label) return;
        const resolved = await api('/api/flow/adopt-locator', 'POST', chosen);
        addElement(target.param, resolved, label, target.chosenEl);
        clearInspectorTarget();
        paramModal.classList.remove('hidden');
        return;
    }

    benchStrategy.value = chosen.strategy;
    benchValue.value = chosen.value;
    await runBench();
}

function drawHighlight(node) {
    const geo = imageGeometry();
    if (!node || !node.rect || !geo) { highlightBox.classList.add('hidden'); return; }
    const [left, top, right, bottom] = node.rect;
    const scaleX = geo.drawnWidth / geo.deviceW;
    const scaleY = geo.drawnHeight / geo.deviceH;
    highlightBox.style.left = (geo.offsetX + left * scaleX) + 'px';
    highlightBox.style.top = (geo.offsetY + top * scaleY) + 'px';
    highlightBox.style.width = Math.max(2, (right - left) * scaleX) + 'px';
    highlightBox.style.height = Math.max(2, (bottom - top) * scaleY) + 'px';
    highlightBox.classList.remove('hidden');
}

async function runBench() {
    const value = benchValue.value.trim();
    if (!value) { showError('Type a locator to test'); return; }
    benchResult.className = 'bench-result';
    benchResult.textContent = 'Testing on the device…';
    const result = await api('/api/flow/inspect/test-locator', 'POST',
        { strategy: benchStrategy.value, value, elementClass: null, matchCount: 0 });
    benchResult.className = 'bench-result ' + (result.matchCount === 1 ? 'good' : 'bad');
    benchResult.textContent = `${result.verdict} · ${result.elapsedMs} ms · ${result.resolvedAs}`;
}

inspectRefreshBtn.addEventListener('click', guarded(() => captureTree()));
inspectSearch.addEventListener('input', () => renderTree());
benchRunBtn.addEventListener('click', guarded(runBench));
benchValue.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') benchRunBtn.click(); });

inspectRawBtn.addEventListener('click', guarded(async () => {
    const resp = await api('/api/flow/inspect/source');
    rawXml.value = resp.xml;
    rawModal.classList.remove('hidden');
}));
rawCloseBtn.addEventListener('click', () => rawModal.classList.add('hidden'));

// The highlight is drawn in rendered-image pixels, so it has to be redrawn whenever the
// mirror changes size — otherwise it keeps pointing at where the element used to be.
window.addEventListener('resize', () => {
    if (inspect.selectedId !== null) drawHighlight(inspect.nodes.get(inspect.selectedId));
});

paneTabs.forEach(tab => {
    tab.addEventListener('click', async () => {
        if (tab.dataset.pane === 'inspectorPane') {
            if (!inspect.nodes.size && state.connected) await captureTree();
        } else {
            highlightBox.classList.add('hidden');
        }
    });
});

// --- Inserting a wait between steps ----------------------------------------
//
// Recording can only append, because it follows a person using a phone. Editing is the other
// half: the commonest repair after a replay is "this screen needs a moment", and that belongs
// between two steps that already exist. Nothing runs on the device when a step is inserted —
// the phone is not on that screen — so Replay is what says whether the wait actually helped.

const insertModal = el('insertModal');
const insertModalTitle = el('insertModalTitle');
const insertKind = el('insertKind');
const insertSecondsField = el('insertSecondsField');
const insertSeconds = el('insertSeconds');
const insertElementField = el('insertElementField');
const insertElementKind = el('insertElementKind');
const insertElementValue = el('insertElementValue');
const insertConfirmBtn = el('insertConfirmBtn');
const insertCancelBtn = el('insertCancelBtn');

let insertAt = null;

function openInsertModal(at) {
    if (!state.recording) { showError('Start or resume a recording first'); return; }
    insertAt = at;
    insertModalTitle.textContent = at >= stepsList.children.length
        ? 'Add a wait at the end'
        : `Insert a wait before step ${at + 1}`;
    insertElementValue.value = '';
    syncInsertFields();
    insertModal.classList.remove('hidden');
    insertElementValue.focus();
}

function syncTryFields() {
    const backup = tryOnFailure.value === 'RUN_RECOVERY_STEPS';
    tryCatchRange.classList.toggle('hidden', !backup);
}

function syncInsertFields() {
    const fixed = insertKind.value === 'WAIT_FOR_SECONDS';
    insertSecondsField.classList.toggle('hidden', !fixed);
    insertElementField.classList.toggle('hidden', fixed);
}

insertKind.addEventListener('change', syncInsertFields);
insertCancelBtn.addEventListener('click', () => {
    insertModal.classList.add('hidden');
    insertAt = null;
});
appendWaitBtn.addEventListener('click', () => openInsertModal(stepsList.children.length));

insertConfirmBtn.addEventListener('click', guarded(async () => {
    const kind = insertKind.value;
    const args = { elements: [], scalars: [] };

    if (kind === 'WAIT_FOR_SECONDS') {
        const seconds = parseInt(insertSeconds.value, 10);
        if (!seconds || seconds < 1) { showError('Enter how many seconds to wait'); return; }
        args.scalars.push({ paramName: 'seconds', value: String(seconds) });
    } else {
        const described = insertElementValue.value.trim();
        if (!described) { showError('Describe the element to wait for'); return; }
        // The same resolver the off-screen element picker uses: the element cannot be tapped
        // on the mirror, because the screen it belongs to is not the one in front of us.
        const resolved = await api('/api/flow/describe-element', 'POST',
            { kind: insertElementKind.value, value: described });
        args.elements.push({
            paramName: 'element',
            fieldName: toFieldName(described),
            candidateLocators: resolved.candidates,
            reusedFrom: resolved.existingLocator || null,
        });
    }

    const resp = await api('/api/flow/step/insert', 'POST',
        { at: insertAt, methodKind: kind, args });
    insertModal.classList.add('hidden');
    insertAt = null;

    const current = await api('/api/flow/current');
    renderSteps(current);
    applyPreview(resp.preview);
}));

// --- Past flows ------------------------------------------------------------
//
// Finishing used to be the end of a recording: the Java landed in the repo and the steps
// that produced it were dropped, so changing one step later meant walking the whole journey
// on the phone again. A finished flow is now kept, and re-opening it puts the steps back in
// front of you — edit, insert a wait, regenerate.

const openLibraryBtn = el('openLibraryBtn');
const libraryModal = el('libraryModal');
const libraryList = el('libraryList');
const libraryEmpty = el('libraryEmpty');
const libraryCloseBtn = el('libraryCloseBtn');

openLibraryBtn.addEventListener('click', guarded(async () => {
    const flows = await api('/api/flow/library');
    libraryList.innerHTML = '';
    libraryEmpty.classList.toggle('hidden', flows.length > 0);
    flows.forEach(flow => libraryList.appendChild(libraryRow(flow)));
    libraryModal.classList.remove('hidden');
}));

libraryCloseBtn.addEventListener('click', () => libraryModal.classList.add('hidden'));

function libraryRow(summary) {
    const row = document.createElement('div');
    row.className = 'library-row';

    const body = document.createElement('div');
    body.className = 'library-body';
    const name = document.createElement('span');
    name.className = 'library-name';
    name.textContent = summary.flowName;
    body.appendChild(name);
    const meta = document.createElement('span');
    meta.className = 'library-meta';
    meta.textContent = `${summary.stepCount} step${summary.stepCount === 1 ? '' : 's'}`
        + ` · ${summary.targetArea} · ${whenText(summary.finishedAt)}`;
    body.appendChild(meta);
    row.appendChild(body);

    const open = document.createElement('button');
    open.className = 'primary';
    open.textContent = 'Open';
    open.addEventListener('click', guarded(() => openSavedFlow(summary)));
    row.appendChild(open);

    const forget = document.createElement('button');
    forget.textContent = 'Forget';
    // Says what it does, because the obvious reading of a delete button next to a generated
    // test is that it deletes the test. It does not: the Java stays exactly where it is.
    forget.title = 'Remove it from this list. The generated files in the repo are left alone.';
    forget.addEventListener('click', guarded(async () => {
        await api('/api/flow/library/' + summary.id, 'DELETE');
        openLibraryBtn.click();
    }));
    row.appendChild(forget);

    return row;
}

async function openSavedFlow(summary) {
    const resp = await api('/api/flow/library/' + summary.id + '/open', 'POST');
    libraryModal.classList.add('hidden');
    enterRecordingUi(resp.flow.flowName);
    if (resp.flow.targetArea) targetAreaSelect.value = resp.flow.targetArea;
    assertionsToggle.checked = resp.flow.generateAssertions !== false;
    renderSteps(resp.flow);
    applyPreview(resp.preview);
    if (resp.filesStillUntouched === false) {
        showWarning('The generated files for this flow have changed on disk since it was written. '
            + 'Generating again will leave a *GeneratedDraft.java beside them rather than '
            + 'overwriting whatever is there now.');
    }
}

function whenText(instant) {
    if (!instant) return 'unknown date';
    const then = new Date(instant);
    const minutes = Math.round((Date.now() - then.getTime()) / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    if (minutes < 60 * 24) return `${Math.round(minutes / 60)} h ago`;
    return then.toLocaleDateString();
}

// --- Wrapping steps in try/catch -------------------------------------------
//
// The optional-screen problem: a coupon sheet, a permission prompt, a dialog that shows on
// some runs and not others. Without this the honest options are to record two flows or to
// let the whole test fail on a dialog nobody cares about. The repo already solves it by hand
// — HandlingBottomSheets is nothing but try/catch around dismissals — so this generates the
// shape that is already house style, and Replay honours it so the two agree.

const tryModal = el('tryModal');
const tryModalTitle = el('tryModalTitle');
const tryFrom = el('tryFrom');
const tryTo = el('tryTo');
const tryOnFailure = el('tryOnFailure');
const tryNote = el('tryNote');
const tryCatchRange = el('tryCatchRange');
const tryCatchFrom = el('tryCatchFrom');
const tryCatchTo = el('tryCatchTo');
const tryConfirmBtn = el('tryConfirmBtn');
const tryCancelBtn = el('tryCancelBtn');

/** The block a step belongs to, or null. */
function blockFor(step) {
    if (!step || !step.tryGroup) return null;
    return (state.tryBlocks || []).find(b => b.id === step.tryGroup) || null;
}

function openTryModal(index) {
    if (!state.recording) { showError('Start or resume a recording first'); return; }
    const total = stepsList.children.length;
    tryModalTitle.textContent = `Wrap step ${index + 1} in try/catch`;
    tryFrom.value = String(index + 1);
    tryTo.value = String(index + 1);
    tryFrom.max = String(total);
    tryTo.max = String(total);
    tryNote.value = '';
    tryOnFailure.value = 'CONTINUE';
    tryCatchFrom.value = '';
    tryCatchTo.value = '';
    syncTryFields();
    tryModal.classList.remove('hidden');
    tryNote.focus();
}

tryCancelBtn.addEventListener('click', () => tryModal.classList.add('hidden'));
tryOnFailure.addEventListener('change', syncTryFields);

tryConfirmBtn.addEventListener('click', guarded(async () => {
    const from = parseInt(tryFrom.value, 10) - 1;
    const to = parseInt(tryTo.value, 10) - 1;
    if (isNaN(from) || isNaN(to) || from < 0 || to < from || to >= stepsList.children.length) {
        showError('Pick a range inside the flow, with "from" no later than "to"');
        return;
    }
    const payload = { from, to, onFailure: tryOnFailure.value, note: tryNote.value.trim() || null };
    if (tryOnFailure.value === 'RUN_RECOVERY_STEPS') {
        const catchFrom = parseInt(tryCatchFrom.value, 10) - 1;
        const catchTo = parseInt(tryCatchTo.value, 10) - 1;
        if (isNaN(catchFrom) || isNaN(catchTo) || catchFrom < 0 || catchTo < catchFrom) {
            showError('Pick the steps that should run instead when the try fails');
            return;
        }
        payload.catchFrom = catchFrom;
        payload.catchTo = catchTo;
    }
    const resp = await api('/api/flow/try-block', 'POST', payload);
    tryModal.classList.add('hidden');
    const current = await api('/api/flow/current');
    renderSteps(current);
    applyPreview(resp.preview);
}));

// --- Verify for real -------------------------------------------------------
//
// Step replay checks the step list. Once a flow carries a try/catch or a hand edit, the step
// list is no longer the program that was written, and replay can never see a missing import,
// a class that forgets to extend BaseSetup, or a locator PageFactory resolves differently
// from findElements. This runs the actual TestNG class through Maven. It is slow and it takes
// the phone away from the studio, which is why it is a deliberate button and not the default.

const verifyBtn = el('verifyBtn');
const verifyModal = el('verifyModal');
const verifyClass = el('verifyClass');
const verifyEnv = el('verifyEnv');
const verifyRunBtn = el('verifyRunBtn');
const verifyCancelBtn = el('verifyCancelBtn');
const verifyBox = el('verifyBox');
const verifySummary = el('verifySummary');
const verifyOutput = el('verifyOutput');
const verifyStop = el('verifyStop');
const verifyCloseBtn = el('verifyCloseBtn');

let verifyPoll = null;

verifyBtn.addEventListener('click', () => {
    if (!verifyClass.value) verifyClass.value = guessTestClass();
    verifyModal.classList.remove('hidden');
});
verifyCancelBtn.addEventListener('click', () => verifyModal.classList.add('hidden'));
verifyCloseBtn.addEventListener('click', () => verifyBox.classList.add('hidden'));
verifyStop.addEventListener('click', guarded(() => api('/api/flow/verify/stop', 'POST')));

/** The class this flow generates, so the common case needs no typing. */
function guessTestClass() {
    const name = (flowNameInput.value || '').trim();
    if (!name) return '';
    const area = (targetAreaSelect.value || 'GOLD').toLowerCase();
    const className = name.charAt(0).toUpperCase() + name.slice(1) + 'Test';
    return `com.jar.testCases.${area}TestCase.${className}`;
}

verifyRunBtn.addEventListener('click', guarded(async () => {
    const testClass = verifyClass.value.trim();
    if (!testClass) { showError('Which test class should run?'); return; }
    const resp = await api('/api/flow/verify', 'POST', { testClass, env: verifyEnv.value });
    verifyModal.classList.add('hidden');
    verifyBox.classList.remove('hidden');
    verifySummary.className = 'replay-summary running';
    verifySummary.textContent = 'Starting Maven…';
    verifyOutput.textContent = '';
    if (resp.releasedDeviceSession) {
        // Said plainly rather than left for the user to discover when the mirror goes dark.
        setConnected(false);
        showWarning('The device session was handed to the test run. Reconnect when it finishes.');
    }
    startVerifyPolling();
}));

function startVerifyPolling() {
    stopVerifyPolling();
    verifyPoll = setInterval(async () => {
        try {
            renderVerifyStatus(await api('/api/flow/verify/status'));
        } catch (e) {
            stopVerifyPolling();
        }
    }, 2000);
}

function stopVerifyPolling() {
    if (verifyPoll) { clearInterval(verifyPoll); verifyPoll = null; }
}

function renderVerifyStatus(status) {
    const running = status.state === 'RUNNING';
    verifyStop.classList.toggle('hidden', !running);

    const seconds = Math.round((status.elapsedMs || 0) / 1000);
    const head = status.summary ? status.summary + ' · ' : '';
    verifySummary.className = 'replay-summary '
        + (running ? 'running' : (status.state === 'PASSED' ? 'ok' : 'bad'));
    verifySummary.textContent = running
        ? `${head}running ${status.testClass || ''} — ${seconds}s`
        : `${head}${status.message || status.state} (${seconds}s)`;

    verifyOutput.textContent = (status.output || []).join('\n');
    verifyOutput.scrollTop = verifyOutput.scrollHeight;

    if (!running) stopVerifyPolling();
}

// --- Dragging steps into a different order ---------------------------------
//
// Recording can only append, and the order you happen to explore a screen in is not always
// the order the test should run in. Reordering is an edit like any other: nothing happens on
// the device, and whether the flow still works in the new order is Replay's question.

let dragFrom = null;

function attachDragHandlers(li) {
    li.addEventListener('dragstart', (ev) => {
        dragFrom = [...stepsList.children].indexOf(li);
        li.classList.add('dragging');
        ev.dataTransfer.effectAllowed = 'move';
        // Firefox will not start a drag without data on the transfer.
        ev.dataTransfer.setData('text/plain', String(dragFrom));
    });

    li.addEventListener('dragend', () => {
        li.draggable = false;
        li.classList.remove('dragging');
        clearDropMarkers();
        dragFrom = null;
    });

    li.addEventListener('dragover', (ev) => {
        if (dragFrom === null) return;
        ev.preventDefault();
        ev.dataTransfer.dropEffect = 'move';
        clearDropMarkers();
        li.classList.add(isAboveMiddle(ev, li) ? 'drop-above' : 'drop-below');
    });

    li.addEventListener('drop', guarded(async (ev) => {
        ev.preventDefault();
        if (dragFrom === null) return;
        const over = [...stepsList.children].indexOf(li);
        // Dropping on the top half means "before this row", on the bottom half "after it".
        // Removing the dragged step first shifts everything below it up by one, which is why
        // a downward move lands one short without this correction.
        let to = isAboveMiddle(ev, li) ? over : over + 1;
        if (dragFrom < to) to -= 1;
        clearDropMarkers();
        if (to === dragFrom) return;

        const resp = await api(`/api/flow/step/${dragFrom}/move`, 'POST', { to });
        dragFrom = null;
        const current = await api('/api/flow/current');
        renderSteps(current);
        applyPreview(resp.preview);
    }));
}

function isAboveMiddle(ev, li) {
    const box = li.getBoundingClientRect();
    return ev.clientY < box.top + box.height / 2;
}

function clearDropMarkers() {
    [...stepsList.children].forEach(row => row.classList.remove('drop-above', 'drop-below'));
}
