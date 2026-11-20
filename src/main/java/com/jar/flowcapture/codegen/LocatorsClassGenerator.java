package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.LocatorBy;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a *Locators.java file byte-for-byte in the shape of the real
 * MonthlySavingsLocators.java: @Getter class, constructor accepting AppiumDriver,
 * PageFactory.initElements / LocatorRegistry.register branch, one @AndroidFindBy
 * field per captured element. This part is 100% deterministic/templated — no LLM
 * call — so the boilerplate 80% is byte-identical to hand-written repo style every time.
 */
@Service
public class LocatorsClassGenerator {

    public record Result(String className, String fileContent, String packageName) {}

    public Result generate(RecordedFlow flow, String packageName) {
        String className = NameUtils.toClassName(flow.getFlowName()) + "Locators";

        // Dedup by field name in case the same element gets tapped more than once in a flow.
        // Two sources contribute fields: directly tapped/typed elements, and the element
        // arguments of parameterised utility calls (e.g. the target of
        // scrollUntilElementFound) — the latter need a locator field just as much, since
        // the generated call passes them as WebElements.
        //
        // A step whose locator was matched to one already declared elsewhere in the repo and
        // reused by QA is skipped entirely: declaring it again here is exactly the
        // duplication this is meant to remove.
        Map<String, List<CandidateLocator>> fieldsByName = new LinkedHashMap<>();
        for (RecordedStep step : flow.getSteps()) {
            if (step.getActionType() == RecordedStep.ActionType.SCREEN_BOUNDARY) continue;

            if (step.getElementFieldName() != null && step.getReusedLocator() == null) {
                fieldsByName.putIfAbsent(step.getElementFieldName(), step.getCandidateLocators());
            }

            UtilityArgs args = step.getUtilityArgs();
            if (args != null && args.getElements() != null) {
                for (UtilityArgs.ElementTarget target : args.getElements()) {
                    if (target.getReusedFrom() != null) continue;
                    if (target.getFieldName() == null) continue;
                    fieldsByName.putIfAbsent(target.getFieldName(), target.getCandidateLocators());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import io.appium.java_client.AppiumDriver;\n");
        sb.append("import io.appium.java_client.pagefactory.AndroidBy;\n");
        sb.append("import io.appium.java_client.pagefactory.AndroidFindAll;\n");
        sb.append("import io.appium.java_client.pagefactory.AndroidFindBy;\n");
        sb.append("import io.appium.java_client.pagefactory.AppiumFieldDecorator;\n");
        sb.append("import lombok.Getter;\n");
        sb.append("import org.openqa.selenium.WebElement;\n");
        sb.append("import org.openqa.selenium.support.PageFactory;\n\n");
        sb.append("@Getter\n");
        sb.append("public class ").append(className).append(" {\n");
        sb.append("    private AppiumDriver driver;\n\n");
        sb.append("    public ").append(className).append("(AppiumDriver driver) {\n");
        sb.append("        this.driver = driver;\n");
        sb.append("        if (driver != null) { PageFactory.initElements(new AppiumFieldDecorator(driver), this); } else { basePackage.LocatorRegistry.register(this); }\n");
        sb.append("    }\n\n");

        for (var entry : fieldsByName.entrySet()) {
            String fieldName = entry.getKey();
            List<CandidateLocator> candidates = entry.getValue();
            CandidateLocator chosen = (candidates == null || candidates.isEmpty()) ? null : candidates.get(0);

            if (chosen == null) continue;

            if (chosen.isBrittle()) {
                sb.append("    // WARNING: no stable resource-id/content-desc/text found — this is an\n");
                sb.append("    // index-based locator and will break on any layout change. Review before relying on it.\n");
            }
            sb.append(renderAnnotation(chosen));
            sb.append("    private WebElement ").append(fieldName).append(";\n\n");
        }

        sb.append("}\n");
        return new Result(className, sb.toString(), packageName);
    }

    /**
     * JavaLiteralEscaper runs on every branch here, after LocatorRanker's own
     * XPath/UiSelector-level escaping — without it, a locator built from on-screen text
     * containing a literal line break (common: a button label that wraps onto two lines,
     * e.g. "Festival\nSavings") or an embedded double-quote gets written as raw characters
     * into the generated .java file, breaking compilation. Confirmed by this exact failure
     * in a real generated file before this fix.
     */
    private String renderAnnotation(CandidateLocator locator) {
        String value = JavaLiteralEscaper.escape(locator.getValue());
        return switch (locator.getStrategy()) {
            // @AndroidFindBy(id=...) uses UiAutomator2's id strategy, which resolves a BARE id
            // against the app package — so a Jetpack Compose testTag (a resource-id with no
            // package prefix) never matches and the generated locator silently finds nothing at
            // playback. Confirmed on a real device. Emit an XPath for those; keep the native
            // strategy for fully-qualified ids, which is what the repo's own locators use.
            case RESOURCE_ID -> LocatorBy.isFullyQualified(locator.getValue())
                    ? "    @AndroidFindBy(id = \"%s\")\n".formatted(value)
                    : "    @AndroidFindBy(xpath = \"//*[@resource-id='%s']\")\n".formatted(value);
            case CONTENT_DESC -> "    @AndroidFindBy(accessibility = \"%s\")\n".formatted(value);
            case EXACT_TEXT_XPATH -> "    @AndroidFindBy(xpath = \"%s\")\n".formatted(value);
            case TEXT_CONTAINS_UIAUTOMATOR, INDEX_BASED ->
                    "    @AndroidFindBy(uiAutomator = \"%s\")\n".formatted(value);
            case CLASS_NAME_XPATH, INDEXED_UNDER_ANCHOR -> "    @AndroidFindBy(xpath = \"%s\")\n".formatted(value);
        };
    }
}
