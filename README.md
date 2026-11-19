# Flow Capture Studio

Record a QA engineer's manual exploration of a new app flow on a connected Android
device, and generate Locator / Page Object / Test boilerplate matching
`Jar_Frontend_Automation`'s exact code style — so writing a new flow's automation
starts from working first-draft code instead of a blank file.

The dashboard is a real remote control for the device, not just an observer: a tap
on the mirrored screen actually taps the phone, so the flow genuinely advances while
QA records it.

## How it works

1. QA connects a device via Appium and opens the dashboard (`http://localhost:8787`).
2. The phone screen mirrors live in the browser (screenshot polling over SSE).
3. QA picks a target area (Gold / UPI / Web — the real feature areas that exist in
   the repo) and names the flow, then starts recording.
4. QA clicks a point on the mirrored screen → the click is mapped to the real
   element in the device's live page-source XML → candidate locators are ranked
   by stability (resource-id > content-desc > exact-text xpath > uiAutomator
   text-contains > ancestor-scoped xpath > index-based last resort) → the tap is
   actually performed on the device. Shift+click records a text-input step at that
   point instead of a tap. For scrolls, QA presses an explicit "I scrolled
   down/up" button rather than the tool guessing gestures from screenshots.
5. Each step's screenshot is captured as a thumbnail in the step log, and the
   **Live Preview** pane updates immediately with the Locators/Page/Test code
   generated from the flow so far — catching a bad name or locator mid-recording
   instead of only at the end.
6. When a step matches an existing reusable method already in
   `Jar_Frontend_Automation` (e.g. `ActionUtils.scrollUntilElementFound`), the
   dashboard asks: "we already have a method for this — use it instead of writing
   new logic?"
7. A mis-tap can be undone (removes the last recorded step; does not undo the
   physical tap already performed on the device).
8. On "Finish", the tool re-verifies every step's chosen locator against the
   device's *current* screen (skipped with a warning if no device is connected),
   generates `*Locators.java`, `*Page.java`, and `*Test.java` in the exact shape
   of the repo's existing files, runs a style-conformance check, and writes them
   into the target repo's working tree.
9. If the Flow Capture Studio process crashes or is restarted mid-recording, the
   in-progress session is reloaded from disk and the dashboard offers to resume
   or discard it — nothing is silently lost.

**This tool never runs `git` in any repo.** It only writes files to disk. Staging,
diffing, and committing is entirely up to the engineer reviewing the output.

## What's deterministic vs. AI-assisted

The structural skeleton (package, `@Getter`, constructor, `PageFactory.initElements`
/ `LocatorRegistry.register` branching, import ordering) is produced by plain
Java string templates — zero AI involved, 100% consistent every run. This is
`LocatorsClassGenerator` / `PageClassGenerator` / `TestClassGenerator`. Assertions
in the generated test are deliberately never guessed — only a TODO stub with a
hint of the last named screen reached, since a silently wrong auto-generated
assertion is worse than an engineer writing one by hand.

`BifrostClient` is a disabled-by-default hook for routing judgment calls (method
naming, draft assertions) through Jar's internal Bifrost LLM gateway. It is not
wired into the generation path yet — see "Not yet built" below.

## Running it

```bash
./run.sh
```

This pins `JAVA_HOME` to a JDK 21 install, because Lombok 1.18.34's annotation
processor does not yet support JDK 26 (field getters/setters silently fail to
generate under it — if you ever see "cannot find symbol: method getX()" errors
that look like Lombok didn't run, check which JDK compiled it first).

Requires an Appium server running separately (`appium` on `127.0.0.1:4723` by
default) with a device connected via `adb devices`.

Configuration lives in `src/main/resources/application.yml` — notably
`flow-capture.target-repo-path`, which must point at your local clone of
`Jar_Frontend_Automation`.

## What's built

- Appium session lifecycle (`AppiumSessionService`) — connect/disconnect a device,
  plus real device control: `tap`, `typeText` (via `mobile: type` into whatever's
  focused), `scrollDown`/`scrollUp` (same coordinates/duration as
  `ActionUtils.scrollDownGeneric`/`scrollUp` in the target repo, so recording
  behaves identically to the generated test that later calls those methods). All
  driver-touching methods are synchronized on the service instance, because the
  SSE screenshot poller and REST request threads both call into the same
  non-thread-safe `AndroidDriver` concurrently.
- Live screen mirroring via Server-Sent Events (`ScreenStreamController`).
- Click → element resolution against live page-source XML, with locator ranking
  matching the priority implicit in the real `*Locators.java` files
  (`ClickResolverService`, `LocatorRanker` — covered by unit tests). Includes an
  ancestor-scoped xpath fallback (`CLASS_NAME_XPATH`: `//*[@resource-id='...']//class`)
  for elements with no identity of their own, offered before the brittle
  index-based last resort rather than skipping straight to it.
- **Locator re-verification before write** (`LocatorVerificationService`) — right
  before `/finish` writes anything, every step's chosen locator is re-resolved
  against the device's current screen via `findElements`, confirming it still
  matches (and isn't now ambiguous) rather than trusting whatever matched at the
  moment it was tapped. Results surface as `verificationWarnings` in the finish
  response; skipped (with an explicit warning, not silently) if no device is
  connected at finish time.
- Method catalog seeded from the actual reusable methods confirmed in
  `ActionUtils`/`WaitUtils` (`MethodCatalogService`) — this is a static v1 list,
  not a source-scanner yet.
- **Target areas** (`TargetArea`) — Gold / UPI / Web, matching the real package
  structure already in the repo
  (`locaters/{goldLocators,upiLocators,webLocators}`,
  `pageObjectModel/{gold,upi,web}`,
  `src/test/java/com/jar/testCases/{goldTestCase,upiTestCases,webTestCase}`).
  Selected once per flow in the dashboard; every generator and the file writer
  derive their package/path from it, so there's no hardcoded default silently
  used when working outside the Gold area.
- Deterministic codegen for `*Locators.java`, `*Page.java`, and `*Test.java`
  (`LocatorsClassGenerator`, `PageClassGenerator`, `TestClassGenerator`). A
  scroll step that's immediately followed by a tap generates the reused scroll
  helper (`ActionUtils.scrollUntilElementFound(...)`) with the *actual tapped
  element* as its argument, inferred by looking ahead in the recorded step
  sequence — not a dead TODO placeholder, since the information needed is
  already there.
- **Live preview** (`GET /api/flow/preview`) — the same generators run against
  the in-progress flow after every single step, with no file write, so the
  dashboard's preview pane always reflects exactly what `/finish` would produce.
- **Undo last step** (`POST /api/flow/step/undo`) — removes the most recently
  recorded step for mis-tap recovery without restarting the whole flow.
- **Session persistence** (`FlowPersistenceService`, `~/.flow-capture-studio/current-session.json`)
  — every step mutation is saved to disk. If the process crashes or restarts
  mid-recording, `GET /api/flow/resumable` reports the in-progress flow and the
  dashboard offers to resume (`GET /api/flow/current` + re-render) or discard it
  (`POST /api/flow/discard`, which never runs codegen or writes files — distinct
  from `/finish`). Verified by an actual kill-and-restart test during development.
- Style-conformance gate run before any file is written
  (`StyleConformanceChecker`) — flags missing `@Getter`, missing constructor
  branches, introduced JSON libraries other than `org.json`, raw `Thread.sleep`,
  brittle index-based locators, and (for test files) a missing `@Test` or no
  assertion/TODO marker at all.
- File writer that only touches the target repo's working tree, never git
  (`FileWriterService`). Never silently overwrites a file that already exists —
  a name collision (e.g. a hand-written class with the same generated name) is
  redirected to a sibling `*GeneratedDraft.java` file instead, with the collision
  reported back to the dashboard; pass `allowOverwrite: true` on `/finish` to
  opt into replacing it anyway. Also validated at application startup
  (`StartupValidation`), so a bad `target-repo-path` fails immediately at boot
  rather than after a QA engineer has recorded an entire flow.
- A global `@RestControllerAdvice` (`GlobalExceptionHandler`) so every `/api/**`
  failure returns `{"error": "<real message>"}` instead of Spring's default
  stack-trace-free 500 — and `dashboard.js` reads that message into a visible
  error banner (`guarded()`/`showError()`) instead of failing silently in the
  browser console.
- A three-pane dashboard (Thymeleaf + vanilla JS): device mirror + controls,
  step log with per-step thumbnails and a target-area picker, and a live
  generated-code preview with Locators/Page/Test tabs.
- Unit tests for the parts worth locking in against regressions: click
  resolution and locator ranking, name sanitization edge cases (digit-leading
  labels, all-symbol input), the scroll-then-tap codegen inference, test-class
  generation never fabricating a real assertion, undo semantics, and file-write
  collision handling.

## Not yet built / known gaps

- **Bifrost integration is not wired into codegen yet** — `BifrostClient` exists
  and is disabled by default; nothing currently calls it. Likely first use: a
  Bifrost-assisted method-name suggestion or draft assertion, with the repo's
  real files sent as few-shot context, gated behind `flow-capture.bifrost.enabled`.
- **Flow-similarity / dedup check** — no check yet for "this looks 85% like
  MonthlySavingsLocators, reuse/extend it instead of creating a new class."
  Worth building once the base pipeline has real mileage.
- **Method catalog is a static list**, not scanned from the repo. Fine for v1;
  revisit if `ActionUtils`/`WaitUtils` grow and the list needs to stay in sync
  automatically.
- **Click → device-pixel mapping** (`dashboard.js`) assumes the screenshot's
  `naturalWidth`/`naturalHeight` equal true device resolution with no additional
  scaling from Appium itself — verify this against a real device/OS combo before
  trusting click accuracy; this is the single trickiest piece per the original
  design discussion and hasn't been validated against real hardware yet. Device
  control (`tap`/`typeText`/scroll) is new in this pass and is likewise untested
  against real hardware.
- **Export/import a recording as JSON** — not built. The persisted session file
  (`~/.flow-capture-studio/current-session.json`) happens to already be exactly
  this format, so exposing it as a download/upload is a small addition on top
  of `FlowPersistenceService`, not a new serialization format.
- **Single-session only** — one flow recording at a time, matches running this
  locally per engineer, not a shared multi-user service.
