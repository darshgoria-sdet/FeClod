package com.jar.flowcapture.catalog;

import com.jar.flowcapture.execute.UtilityExecutor;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MethodCatalogServiceTest {

    private final MethodCatalogService service = new MethodCatalogService();

    /**
     * Guards the bug this test was written for: quickActions() used to return every
     * parameterless method, including SCROLL_DOWN/SCROLL_UP, which is recorded through
     * /step/scroll instead. The dashboard renders a button per entry here, so a kind that
     * can neither be executed nor is flagged manual would be a button that throws the
     * moment QA clicks it.
     */
    @Test
    void everyQuickActionIsEitherDeviceExecutableOrFlaggedManual() {
        UtilityExecutor executor = new UtilityExecutor(null);
        for (ReusableMethod method : service.quickActions()) {
            assertTrue(executor.isExecutable(method) || method.isManualOnDevice(),
                    method.getKind() + " can neither be executed on the device nor is flagged "
                            + "manualOnDevice — its button would throw when clicked");
        }
    }

    /**
     * completePhonePePayment/goToHome drive the PhonePe simulator, which this tool does not
     * reproduce. They must stay offered (so generated code calls the real method) but must
     * be flagged, or the dashboard would silently fail to perform them.
     */
    @Test
    void paymentSimulatorMethodsAreFlaggedManual() {
        for (ReusableMethod.Kind kind : List.of(ReusableMethod.Kind.COMPLETE_PHONEPE_PAYMENT,
                ReusableMethod.Kind.GO_TO_HOME, ReusableMethod.Kind.CHANGE_PAYMENT_STATUS)) {
            ReusableMethod method = service.byKind(kind.name()).orElseThrow();
            assertTrue(method.isManualOnDevice(), kind + " must be flagged manualOnDevice");
            assertTrue(method.isInstanceMethod(),
                    kind + " is an instance method in the target repo — calling it statically would not compile");
        }
    }

    /**
     * A one-click button must never leave an argument unfilled. Either the method takes none,
     * or the tool supplies it from a known repo locator — anything else would record a call
     * with a missing argument and generate code that does not compile.
     */
    @Test
    void everyQuickActionHasAllItsArgumentsCovered() {
        for (ReusableMethod method : service.quickActions()) {
            assertTrue(method.isParameterless() || method.isAutoResolvedArgs(),
                    method.getKind() + " needs a WebElement nobody supplies, so it cannot be a one-click button");
            if (method.isAutoResolvedArgs()) {
                assertNotNull(method.getPresetLocatorField(),
                        method.getKind() + " claims auto-resolved args but names no locator field");
                assertEquals(1, method.getParams().size(),
                        method.getKind() + " auto-resolves exactly one element argument");
            }
        }
    }

    /**
     * Pinned to the locator every test in the repo already uses to prove it is on the home
     * screen. If that field is ever renamed, this test fails here rather than at record time
     * on someone's phone.
     */
    @Test
    void returnToHomeIsPinnedToTheHomeScreenProfileIcon() {
        ReusableMethod method = service.byKind(ReusableMethod.Kind.RETURN_TO_HOME.name()).orElseThrow();
        assertEquals("HomeScreenLocators", method.getPresetLocatorClass());
        assertEquals("profileIcon", method.getPresetLocatorField());
        assertEquals("ActionUtils.navigateBackUntilElementFound", method.getFullyQualifiedCall(),
                "must call the repo's own method, not a reimplementation");
        assertFalse(method.isManualOnDevice(), "the tool can and should drive this one itself");
    }

    /** It is one click in the dashboard, so it must not also appear in the modal-driven list. */
    @Test
    void returnToHomeIsNotOfferedAsAParameterisedAction() {
        assertTrue(service.parameterisedActions().stream()
                        .noneMatch(m -> m.getKind() == ReusableMethod.Kind.RETURN_TO_HOME),
                "RETURN_TO_HOME fills its own argument, so it must not open an argument modal");
        assertTrue(service.quickActions().stream()
                        .anyMatch(m -> m.getKind() == ReusableMethod.Kind.RETURN_TO_HOME),
                "RETURN_TO_HOME must be offered as a one-click button");
    }

    @Test
    void scrollStepsAreNotOfferedAsQuickActions() {
        List<ReusableMethod.Kind> kinds = service.quickActions().stream().map(ReusableMethod::getKind).toList();
        assertFalse(kinds.contains(ReusableMethod.Kind.SCROLL_DOWN),
                "scroll down is recorded via /step/scroll, not as a utility quick action");
        assertFalse(kinds.contains(ReusableMethod.Kind.SCROLL_UP),
                "scroll up is recorded via /step/scroll, not as a utility quick action");
    }

    @Test
    void backNavigationIsOfferedAsAQuickAction() {
        ReusableMethod back = service.quickActions().stream()
                .filter(m -> m.getKind() == ReusableMethod.Kind.NAVIGATE_BACK)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Back must be available as a quick action"));
        assertEquals("ActionUtils.navigateBack", back.getFullyQualifiedCall());
        assertEquals("utilsPackage.ActionUtils", back.getImportStatement());
    }

    @Test
    void quickActionsAreNotEmpty() {
        assertFalse(service.quickActions().isEmpty(), "dashboard would render an empty quick-action bar");
    }
}
