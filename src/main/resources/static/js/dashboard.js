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
        if (status.connected) setConnected(true);
        showWarning(status.packageWarning);
    } catch (e) {
        // non-fatal — worst case the user clicks Connect, which is idempotent server-side
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
    renderSteps(current.steps);
    await refreshPreview();
}));

discardResumeBtn.addEventListener('click', guarded(async () => {
    await api('/api/flow/discard', 'POST');
    resumeBanner.classList.add('hidden');
}));

function setConnected(connected) {
    state.connected = connected;
    connectBtn.disabled = connected;
    disconnectBtn.disabled = !connected;
    connectionStatus.textContent = connected ? 'connected' : 'not connected';
    connectionStatus.classList.toggle('live', connected);
    if (connected) startScreenStream(); else stopScreenStream();
}

function startScreenStream() {
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
    screenImg.src = '';
}

connectBtn.addEventListener('click', guarded(async () => {
    connectBtn.disabled = true;
    connectionStatus.textContent = 'connecting...';
    try {
        const result = await api('/api/flow/device/connect', 'POST');
        setConnected(true);
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
    await api('/api/flow/start', 'POST', { flowName, targetArea: targetAreaSelect.value });
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

finishFlowBtn.addEventListener('click', guarded(async () => {
    const response = await api('/api/flow/finish', 'POST', { allowOverwrite: false });
    exitRecordingUi();

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
}));

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
 * Replay
 *
 * Recording proves each step worked once, with a human pausing in between. Replay is
 * what proves the flow runs as a flow — which is what the generated test will do. It is
 * offered before "Finish & Generate" on purpose: a flow that cannot replay is a flaky
 * test waiting to be committed.
 * ------------------------------------------------------------------------- */

const STATUS_MARK = { PASSED: '\u2713', FAILED: '\u2717', SKIPPED: '\u2013', NOT_REACHED: '\u00b7' };

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
screenImg.addEventListener('click', guarded(async (ev) => {
    if (!state.connected || !state.recording) return;

    const rect = screenImg.getBoundingClientRect();
    const naturalW = screenImg.naturalWidth;
    const naturalH = screenImg.naturalHeight;
    if (!naturalW || !naturalH) return;

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

    const clickXInImage = ev.clientX - rect.left - offsetX;
    const clickYInImage = ev.clientY - rect.top - offsetY;
    if (clickXInImage < 0 || clickYInImage < 0 || clickXInImage > drawnWidth || clickYInImage > drawnHeight) {
        return; // clicked in the letterbox padding, not on the actual screen
    }

    const deviceX = Math.round((clickXInImage / drawnWidth) * naturalW);
    const deviceY = Math.round((clickYInImage / drawnHeight) * naturalH);

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

    stepsList.appendChild(li);
    updateStepCount();
}

function updateStepCount() {
    const n = stepsList.children.length;
    stepCount.textContent = String(n);
    stepsEmpty.classList.toggle('hidden', n > 0 || !state.recording === false && n > 0);
    stepsEmpty.classList.toggle('hidden', n > 0);
}

function renderSteps(steps) {
    stepsList.innerHTML = '';
    updateStepCount();
    steps.forEach(step => {
        let label;
        if (step.actionType === 'SCREEN_BOUNDARY') {
            label = `--- Screen: ${step.screenName || '(unnamed)'} ---`;
        } else if (step.actionType === 'UTILITY_CALL') {
            // actionLabel here is the catalog's full humanDescription, far too long for the log line.
            // actionLabel here is the catalogue's full humanDescription, far too long for the
            // log line — resolve the method name from the loaded catalogue instead.
            const method = allMethods.find(m => m.kind === step.utilityMethodKind);
            label = method ? displayName(method) : 'Utility call';
        } else {
            label = `${step.actionType}: ${step.actionLabel || ''}`;
        }
        addStepToLog(step, label);
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

function renderActivePreviewTab() {
    if (!state.lastPreview) return;
    const fileMap = {
        locators: state.lastPreview.locatorsFileContent,
        page: state.lastPreview.pageFileContent,
        test: state.lastPreview.testFileContent,
    };
    previewCode.textContent = fileMap[state.activePreviewTab] || '';
}

async function refreshPreview() {
    try {
        const preview = await api('/api/flow/preview');
        applyPreview(preview);
    } catch (e) {
        // no active flow yet — fine, preview stays at its placeholder text
    }
}
