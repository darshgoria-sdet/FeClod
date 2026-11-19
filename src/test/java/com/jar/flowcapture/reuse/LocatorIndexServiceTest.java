package com.jar.flowcapture.reuse;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.config.FlowCaptureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocatorIndexServiceTest {

    @TempDir
    Path repo;

    private LocatorIndexService service;

    /** A cut-down copy of the real HomeScreenLocators, including its @AndroidFindAll shape. */
    private static final String HOME_SCREEN_LOCATORS = """
            package locaters.goldLocators;

            import org.openqa.selenium.WebElement;

            @Getter
            public class HomeScreenLocators {
                @AndroidFindAll({
                        @AndroidBy(xpath = "//android.view.View[@content-desc='ProfileIcon']"),
                        @AndroidBy(xpath = "//android.view.View[@content-desc='profile Icon']"),
                        @AndroidBy(xpath = "(//android.view.View[contains(@content-desc,'profile')])[1]"),
                        @AndroidBy(xpath = "//android.widget.ImageView[@content-desc='ProfileIcon']")
                })
                private WebElement profileIcon;

                @AndroidFindBy(id = "com.jar.app:id/pay_button")
                private WebElement payButton;
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        Path dir = repo.resolve("src/main/Java/locaters/goldLocators");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("HomeScreenLocators.java"), HOME_SCREEN_LOCATORS);

        FlowCaptureProperties properties = new FlowCaptureProperties();
        properties.setTargetRepoPath(repo.toString());
        service = new LocatorIndexService(properties);
        service.refresh();
    }

    /**
     * A field declared with @AndroidFindAll is OR-ed at runtime, so anything executing it must
     * get all four alternatives. Returning only the highest-ranked one would make the tool
     * give up on screens where the generated test would succeed.
     */
    @Test
    void findDeclaredReturnsEveryAlternativeOnAnAndroidFindAllField() {
        var declared = service.findDeclared("HomeScreenLocators", "profileIcon").orElseThrow();

        assertEquals(4, declared.candidates().size());
        assertTrue(declared.candidates().stream()
                .map(CandidateLocator::getValue)
                .anyMatch(v -> v.contains("android.widget.ImageView")));
        assertEquals("profileIcon", declared.ref().getFieldName());
        assertEquals("locaters.goldLocators", declared.ref().getLocatorsPackage());
    }

    /** The ref is what codegen turns into homeScreenLocators.getProfileIcon(). */
    @Test
    void declaredRefRendersAsAGetterOnTheExistingClass() {
        var declared = service.findDeclared("HomeScreenLocators", "profileIcon").orElseThrow();

        assertEquals("getProfileIcon", declared.ref().getterName());
        assertEquals("homeScreenLocators", declared.ref().instanceFieldName());
    }

    @Test
    void findDeclaredIsEmptyForAFieldThatDoesNotExist() {
        assertTrue(service.findDeclared("HomeScreenLocators", "renamedAwayIcon").isEmpty());
        assertTrue(service.findDeclared("NoSuchLocators", "profileIcon").isEmpty());
    }

    /**
     * findDeclared addresses a field by name; findExisting matches a captured element by
     * locator value. They are different questions and a field must be reachable by name even
     * when its locators carry no stable identity for matching.
     */
    @Test
    void fieldsAreIndexedByNameIndependentlyOfSemanticMatching() {
        assertTrue(service.findDeclared("HomeScreenLocators", "payButton").isPresent());

        var byValue = service.findExisting(
                List.of(new CandidateLocator(CandidateLocator.Strategy.RESOURCE_ID,
                        "com.jar.app:id/pay_button", "android.widget.Button")),
                "SomeOtherLocators");
        assertTrue(byValue.isPresent());
        assertEquals("payButton", byValue.get().getFieldName());
    }
}
