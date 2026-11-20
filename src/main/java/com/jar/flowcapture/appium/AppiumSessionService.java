package com.jar.flowcapture.appium;

import com.jar.flowcapture.config.FlowCaptureProperties;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Pause;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.RemoteWebElement;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Owns a single live AndroidDriver session against the connected device, mirroring
 * the role DriverFactory plays in Jar_Frontend_Automation (static, process-wide driver),
 * except scoped to one QA engineer's capture session instead of a parallel test run.
 *
 * AndroidDriver's underlying HTTP command executor is not safe under concurrent
 * calls on the same session, and this app has exactly that risk: a scheduled thread
 * polls screenshots (ScreenStreamController) while REST request threads call
 * capturePageSource() for tap/input resolution at the same time. Every driver-touching
 * method is synchronized on this instance so calls are serialized instead of racing —
 * a tap's getPageSource() can briefly wait behind a screenshot poll, which is a small,
 * acceptable latency cost for not getting interleaved/corrupted driver responses.
 */
@Slf4j
@Service
public class AppiumSessionService {

    private final FlowCaptureProperties properties;
    private AndroidDriver driver;

    public AppiumSessionService(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    public synchronized void startSession() {
        if (driver != null) {
            log.info("Appium session already active — reusing it");
            return;
        }

        var appiumProps = properties.getAppium();
        UiAutomator2Options options = new UiAutomator2Options()
                .setPlatformName(appiumProps.getPlatformName())
                .setAutomationName(appiumProps.getAutomationName())
                .setNewCommandTimeout(java.time.Duration.ofMinutes(10))
                .setNoReset(true);

        if (appiumProps.getDeviceName() != null && !appiumProps.getDeviceName().isBlank()) {
            options.setDeviceName(appiumProps.getDeviceName());
        }

        try {
            driver = new AndroidDriver(new URL(appiumProps.getServerUrl()), options);
            log.info("Appium session started against {}", appiumProps.getServerUrl());
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid Appium server URL: " + appiumProps.getServerUrl(), e);
        }
    }

    public synchronized void stopSession() {
        if (driver != null) {
            try {
                driver.quit();
            } finally {
                driver = null;
                cachedScreenSize = null;
            }
        }
    }

    public synchronized boolean isActive() {
        return driver != null;
    }

    /**
     * Used by LocatorVerificationService to re-check a candidate locator against the
     * live device. Takes the By rather than exposing the raw driver, so the actual
     * findElements call stays inside this class's synchronization along with every
     * other driver interaction — not scattered across callers.
     */
    public synchronized int countMatches(org.openqa.selenium.By by) {
        return requireDriver().findElements(by).size();
    }

    private synchronized AndroidDriver requireDriver() {
        if (driver == null) {
            throw new IllegalStateException("No active Appium session — start a session first");
        }
        return driver;
    }

    /** Base64 PNG, ready to drop straight into an <img src="data:image/png;base64,..."> on the dashboard. */
    public synchronized String captureScreenshotBase64() {
        byte[] bytes = requireDriver().getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * The real device resolution, which the dashboard needs to turn a click on the mirror
     * into device coordinates. It must come from here rather than from the mirrored image's
     * own pixel size: the MJPEG broadcaster sends downscaled frames (600x1335 for a
     * 1200x2670 screen), so trusting the image would halve every tap coordinate.
     */
    public synchronized int[] screenSize() {
        var size = requireDriver().manage().window().getSize();
        return new int[]{size.getWidth(), size.getHeight()};
    }

    public synchronized String capturePageSource() {
        return requireDriver().getPageSource();
    }

    /**
     * Taps absolute device-pixel coordinates — this is the dashboard acting as a real
     * remote control, not just an observer. Without this, clicking the mirrored
     * screenshot only ever recorded a step; the phone never actually advanced, which
     * meant live preview / verify-before-write / step thumbnails had nothing real to
     * show.
     *
     * Uses "mobile: clickGesture", NOT "mobile: tap" — confirmed by hitting a real
     * device that "mobile: tap" is not a supported execute method on uiautomator2
     * driver 7.6.0 (the version actually installed here); the driver's own error
     * response lists "mobile: clickGesture" as the supported equivalent. This bug
     * silently swallowed every tap (caught upstream, logged, never surfaced to the
     * dashboard as anything but a generic failure), so no steps were ever recorded
     * and /finish produced empty skeleton files with no locators or flow logic.
     */
    public synchronized void tap(int x, int y) {
        requireDriver().executeScript("mobile: clickGesture", Map.of("x", x, "y", y));
    }

    /** The package of whatever is in the foreground right now, or null if the driver cannot report it. */
    public synchronized String currentPackage() {
        try {
            return requireDriver().getCurrentPackage();
        } catch (Exception e) {
            log.debug("Could not read the foreground package: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Warns when the app in the foreground is not the one this tool is configured for.
     *
     * This matters more than it looks: the phone may have several builds of the same app
     * installed (observed: com.jar.app, com.jar.app.replica and com.aso_centric.jar.staging all
     * present at once). Recording against the wrong one produces locators that look fine but
     * belong to a different build, and openApp/closeApp would act on the configured package
     * rather than the one being recorded. Both failures are silent without this check.
     *
     * @return null when they match, when no package is configured, or when the foreground
     *         package cannot be read — this is advisory and must never block recording.
     */
    public synchronized String foregroundPackageWarning() {
        String configured = properties.getAppium().getAndroidPackage();
        if (configured == null || configured.isBlank()) return null;

        String actual = currentPackage();
        if (actual == null || actual.isBlank() || actual.equals(configured)) return null;

        return "The app in the foreground is \"" + actual + "\" but this tool is configured for \""
                + configured + "\". Locators you record now may not match the build your tests run "
                + "against, and Open app / Close app act on the configured package. Use Open app to "
                + "switch, or change flow-capture.appium.android-package.";
    }

    /** Presses the real Android back action, same as ActionUtils.navigateBack() in the target repo — so the dashboard's Back button drives the device identically to how the generated code will later call it. */
    public synchronized void pressBack() {
        requireDriver().navigate().back();
    }

    /** Types into whatever is currently focused — called right after tap(), so the just-tapped field has focus. Uses UiAutomator2's "mobile: type" extension rather than Selenium's Keyboard API, which isn't exposed on AndroidDriver in this client version. */
    public synchronized void typeText(String text) {
        requireDriver().executeScript("mobile: type", Map.of("text", text));
    }

    /**
     * Matches ActionUtils.scrollDownGeneric's actual code: 70% -> 20% of screen height.
     * Note its javadoc says "80% -> 30%", which is stale and disagrees with the method
     * body right beneath it; this follows the body, since that is what the generated test
     * will really do. Recording with different geometry than playback would mean a flow
     * that records fine and then fails in CI.
     */
    public synchronized void scrollDown() {
        swipeVertical(0.7, 0.2, 700);
    }

    /** Same coordinates/duration as ActionUtils.scrollUp in Jar_Frontend_Automation. */
    public synchronized void scrollUp() {
        swipeVertical(0.3, 0.85, 900);
    }

    /** Dismisses the on-screen keyboard — same "mobile: performEditorAction" Android uses in ActionUtils.closeKeyboard. (The target repo also branches for iOS; this tool is Android-only per its UiAutomator2Options setup, so that branch doesn't apply here.) */
    public synchronized void closeKeyboard() {
        requireDriver().executeScript("mobile: performEditorAction", Map.of("action", "done"));
    }

    /** Brings the app to the foreground by package ID, same as ActionUtils.openApp. */
    public synchronized void openApp() {
        requireDriver().activateApp(properties.getAppium().getAndroidPackage());
    }

    /** Terminates the app process, same as ActionUtils.closeApp. */
    public synchronized void closeApp() {
        requireDriver().terminateApp(properties.getAppium().getAndroidPackage());
    }

    /** Switches context to the app's WebView, same as WebUtils.switchToWebView. */
    public synchronized void switchToWebView() {
        var contexts = requireDriver().getContextHandles();
        for (String context : contexts) {
            if (context.startsWith("WEBVIEW")) {
                requireDriver().context(context);
                return;
            }
        }
        throw new IllegalStateException("No WebView context found — ensure WebView debugging is enabled in the app");
    }

    /** Switches context back to native, same as WebUtils.switchToNative. */
    public synchronized void switchToNative() {
        requireDriver().context("NATIVE_APP");
    }

    // ---------------------------------------------------------------------------
    // Element-level operations backing the catalogued utility methods. Each mirrors the
    // behaviour of its ActionUtils/WaitUtils counterpart so that what QA sees while
    // recording is what the generated test will do. MAX_ATTEMPTS matches
    // ActionUtils.MAX_ATTEMPTS = 6 exactly; changing it here without changing it there
    // would make recording and playback disagree.
    // ---------------------------------------------------------------------------

    /** Mirrors ActionUtils.MAX_ATTEMPTS. */
    public static final int MAX_ATTEMPTS = 6;

    /**
     * Present in the hierarchy AND actually on the screen.
     *
     * isDisplayed() alone is not enough on Android: a RecyclerView or Compose LazyColumn keeps
     * rows attached after they scroll out of view, and they report displayed with bounds that
     * sit outside the viewport. Trusting it produces two bad outcomes that both look like
     * success — scrollUntilElementFound stops scrolling before the element is on screen, and a
     * replayed tap is dispatched at off-screen coordinates, does nothing, and reports PASSED.
     *
     * Observed exactly that: a replay of a tap on a tile that was below the fold reported the
     * step passed while the app sat on an unrelated screen. The viewport check is what makes
     * "visible" mean what the rest of this class assumes it means.
     */
    public synchronized boolean isElementVisible(By by) {
        try {
            var found = requireDriver().findElements(by);
            if (found.isEmpty()) return false;
            WebElement element = found.get(0);
            return element.isDisplayed() && isOnScreen(element);
        } catch (WebDriverException e) {
            return false;
        }
    }

    /** Judged by the element's centre, because that is the point a tap is dispatched to. */
    private boolean isOnScreen(WebElement element) {
        Dimension size = element.getSize();
        if (size.getWidth() <= 0 || size.getHeight() <= 0) return false;

        Point location = element.getLocation();
        int centreX = location.getX() + size.getWidth() / 2;
        int centreY = location.getY() + size.getHeight() / 2;
        Dimension screen = screenSizeCached();
        return centreX >= 0 && centreY >= 0
                && centreX < screen.getWidth() && centreY < screen.getHeight();
    }

    /**
     * Cached per session: this is consulted on every visibility check inside every scroll and
     * wait loop, and a WebDriver round-trip per check would be a meaningful cost for a value
     * that cannot change while the session lives.
     */
    private Dimension cachedScreenSize;

    private Dimension screenSizeCached() {
        if (cachedScreenSize == null) {
            cachedScreenSize = requireDriver().manage().window().getSize();
        }
        return cachedScreenSize;
    }

    /**
     * Scrolls down until the element is visible, same loop as
     * ActionUtils.scrollUntilElementFound: check, swipe, settle, and bail out early when
     * the page source stops changing (end of list reached), rather than burning all 6
     * attempts on a page that cannot scroll any further.
     */
    public synchronized boolean scrollUntilElementFound(By by) {
        String prevSource = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (isElementVisible(by)) return true;
            scrollDown();
            sleep(2000);
            String currentSource = capturePageSource();
            if (currentSource.equals(prevSource)) {
                log.info("Page source unchanged after scroll — end of page reached");
                return false;
            }
            prevSource = currentSource;
        }
        return isElementVisible(by);
    }

    /**
     * scrollUntilElementFound over several alternative locators for the same element.
     *
     * Needed because a described off-screen target is a guess at how the element identifies
     * itself: QA types "Offers", and the label might be exposed as text, as a content-desc, or
     * as part of a longer string. The alternatives exist precisely to cover that, so searching
     * only the first one throws away the fallbacks that were built for this case.
     */
    public synchronized By scrollUntilAnyFound(List<By> locators) {
        String prevSource = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            By hit = firstVisible(locators);
            if (hit != null) return hit;
            scrollDown();
            sleep(2000);
            String currentSource = capturePageSource();
            if (currentSource.equals(prevSource)) {
                log.info("Page source unchanged after scroll — end of page reached");
                return null;
            }
            prevSource = currentSource;
        }
        return firstVisible(locators);
    }

    public synchronized By scrollUpUntilAnyFound(List<By> locators) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            By hit = firstVisible(locators);
            if (hit != null) return hit;
            scrollUp();
        }
        return firstVisible(locators);
    }

    /** One pass over the alternatives, no waiting. */
    public synchronized By firstVisible(List<By> locators) {
        for (By by : locators) {
            if (isElementVisible(by)) return by;
        }
        return null;
    }

    /** Mirrors ActionUtils.scrollUpUntilElementFound, which has no end-of-page short-circuit. */
    public synchronized boolean scrollUpUntilElementFound(By by) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (isElementVisible(by)) return true;
            scrollUp();
        }
        return isElementVisible(by);
    }

    /** Mirrors ActionUtils.navigateBackUntilElementFound. */
    public synchronized boolean navigateBackUntilElementFound(By by) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (isElementVisible(by)) return true;
            pressBack();
            sleep(2000);
        }
        return isElementVisible(by);
    }

    /**
     * navigateBackUntilElementFound against a field declared with @AndroidFindAll, whose
     * alternatives are OR-ed by PageFactory at runtime. Checking only the first XPath here
     * would make the tool give up on a screen where the real generated test would succeed.
     */
    public synchronized By navigateBackUntilAnyFound(List<By> locators) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            By hit = firstVisible(locators);
            if (hit != null) return hit;
            pressBack();
            sleep(2000);
        }
        return firstVisible(locators);
    }

    public synchronized boolean anyVisible(List<By> locators) {
        return firstVisible(locators) != null;
    }

    /**
     * Polls until one of the locators resolves, and returns which one did.
     *
     * Taking a list rather than a single By is the whole point. A repo field declared with
     * @AndroidFindAll holds several alternatives that PageFactory ORs at runtime, and a
     * captured element has several ranked candidates. Checking only the best one makes the
     * tool declare an element missing that the generated test would have found — observed in
     * practice on the home-screen profile icon, whose content-desc is 'ProfileIcon' on some
     * screens and 'profile Icon' on others.
     */
    public synchronized By findFirstVisible(List<By> locators, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        do {
            for (By by : locators) {
                if (isElementVisible(by)) return by;
            }
            sleep(300);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /**
     * The replay equivalent of the generated `waitForClick(x.getY()).click()`: wait for the
     * element, then issue a real click rather than a coordinate tap, so replay exercises the
     * same path the test will.
     */
    public synchronized By clickFirstVisible(List<By> locators, int timeoutSeconds) {
        By found = findFirstVisible(locators, timeoutSeconds);
        if (found == null) return null;
        requireElement(found).click();
        return found;
    }

    /** Replay equivalent of `waitForClick(x.getY()).sendKeys("...")`. */
    public synchronized By sendKeysToFirstVisible(List<By> locators, String text, int timeoutSeconds) {
        By found = findFirstVisible(locators, timeoutSeconds);
        if (found == null) return null;
        WebElement element = requireElement(found);
        element.click();
        element.sendKeys(text == null ? "" : text);
        return found;
    }

    public synchronized boolean clickElement(By by, int timeoutSeconds) {
        return clickFirstVisible(List.of(by), timeoutSeconds) != null;
    }

    public synchronized boolean sendKeysToElement(By by, String text, int timeoutSeconds) {
        return sendKeysToFirstVisible(List.of(by), text, timeoutSeconds) != null;
    }

    /** Mirrors ActionUtils.scrollTillEndOfSection — a section-scoped swipe that leaves page scroll alone. */
    public synchronized void scrollTillEndOfSection(By by) {
        WebElement section = requireElement(by);
        requireDriver().executeScript("mobile: swipeGesture", Map.of(
                "elementId", ((RemoteWebElement) section).getId(),
                "direction", "down",
                "percent", 1.0));
    }

    /** Mirrors ActionUtils.holdAndDrag: 800ms hold at the element's centre, then drag to 95% of screen height. */
    public synchronized void holdAndDrag(By by) {
        WebElement element = requireElement(by);
        Point loc = element.getLocation();
        Dimension size = element.getSize();
        Dimension screen = requireDriver().manage().window().getSize();

        int startX = loc.getX() + size.getWidth() / 2;
        int startY = loc.getY() + size.getHeight() / 2;
        int endY = (int) (screen.getHeight() * 0.95);

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence drag = new Sequence(finger, 0);
        drag.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        drag.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        drag.addAction(new Pause(finger, Duration.ofMillis(800)));
        drag.addAction(finger.createPointerMove(Duration.ofMillis(600), PointerInput.Origin.viewport(), startX, endY));
        drag.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        requireDriver().perform(Collections.singletonList(drag));
    }

    /** Mirrors ActionUtils.dragLeftToRight — a horizontal drag across the element's own width. */
    public synchronized void dragLeftToRight(By by) {
        WebElement element = requireElement(by);
        Point loc = element.getLocation();
        Dimension size = element.getSize();
        Dimension screen = requireDriver().manage().window().getSize();

        int startX = loc.getX() + 5;
        int startY = loc.getY() + size.getHeight() / 2;
        int endX = (int) (screen.getWidth() * 0.9);

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence drag = new Sequence(finger, 0);
        drag.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        drag.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        drag.addAction(new Pause(finger, Duration.ofMillis(300)));
        drag.addAction(finger.createPointerMove(Duration.ofMillis(800), PointerInput.Origin.viewport(), endX, startY));
        drag.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        requireDriver().perform(Collections.singletonList(drag));
    }

    /** Mirrors ActionUtils.dragCoinToEnd — drags the first element onto the second. */
    public synchronized void dragCoinToEnd(By coinBy, By targetBy) {
        WebElement coin = requireElement(coinBy);
        WebElement target = requireElement(targetBy);
        Point from = coin.getLocation();
        Point to = target.getLocation();

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence drag = new Sequence(finger, 0);
        drag.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(),
                from.getX() + coin.getSize().getWidth() / 2, from.getY() + coin.getSize().getHeight() / 2));
        drag.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        drag.addAction(new Pause(finger, Duration.ofMillis(300)));
        drag.addAction(finger.createPointerMove(Duration.ofMillis(900), PointerInput.Origin.viewport(),
                to.getX() + target.getSize().getWidth() / 2, to.getY() + target.getSize().getHeight() / 2));
        drag.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        requireDriver().perform(Collections.singletonList(drag));
    }

    /** Mirrors WaitUtils.tapElement — a coordinate tap at the element's centre, for when click() does not register. */
    public synchronized void tapElementAt(By by) {
        WebElement element = requireElement(by);
        Point loc = element.getLocation();
        Dimension size = element.getSize();
        tap(loc.getX() + size.getWidth() / 2, loc.getY() + size.getHeight() / 2);
    }

    /** Polls until the element is visible or the timeout expires; mirrors WaitUtils.waitForVisibility/isVisible. */
    public synchronized boolean waitForVisible(By by, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isElementVisible(by)) return true;
            sleep(500);
        }
        return isElementVisible(by);
    }

    /** Polls until any one of the given locators is visible; mirrors WaitUtils.waitForAnyVisible. */
    public synchronized boolean waitForAnyVisible(List<By> locators, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (By by : locators) {
                if (isElementVisible(by)) return true;
            }
            sleep(500);
        }
        return false;
    }

    private WebElement requireElement(By by) {
        var found = requireDriver().findElements(by);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No element on the current screen matches " + by);
        }
        return found.get(0);
    }

    /** Interruption here means the session is shutting down; restore the flag and stop waiting rather than swallowing it. */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void swipeVertical(double startFraction, double endFraction, long durationMs) {
        AndroidDriver d = requireDriver();
        Dimension size = d.manage().window().getSize();
        int startX = size.width / 2;
        int startY = (int) (size.height * startFraction);
        int endY = (int) (size.height * endFraction);

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence swipe = new Sequence(finger, 1);
        swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(durationMs), PointerInput.Origin.viewport(), startX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

        d.perform(Collections.singletonList(swipe));
    }
}
