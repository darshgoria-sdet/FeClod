package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.UtilityArgs;
import com.jar.flowcapture.catalog.MethodCatalogService;
import com.jar.flowcapture.catalog.MethodParam;
import com.jar.flowcapture.catalog.ReusableMethod;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits a *Page.java file in the shape of MonthlySavingsPage.java: locator object
 * instantiated as a field, flow steps turned into one method body calling
 * waitForClick(x.getY()).click() per tap, matching the exact call shape already
 * used throughout pageObjectModel.gold.*.
 *
 * Every real tap in the target repo is wrapped in waitForClick(...) — there is no
 * case in the repo where a click is NOT wait-wrapped — so that wrapping is applied
 * unconditionally here rather than offered as a per-step "use existing method?"
 * suggestion; that suggestion flow is reserved for genuinely optional reuse (e.g.
 * scroll-then-tap), not for something that's mandatory repo convention.
 *
 * Catalogued utility calls render with their real argument lists, and elements whose
 * locator already exists elsewhere in the repo are called through that existing class
 * rather than redeclared — both aimed at the same problem: Daily/Weekly/Monthly
 * currently duplicate near-identical logic and locators.
 */
@Service
public class PageClassGenerator {

    private final MethodCatalogService methodCatalog;

    public PageClassGenerator(MethodCatalogService methodCatalog) {
        this.methodCatalog = methodCatalog;
    }

    public record Result(String className, String fileContent) {}

    public Result generate(RecordedFlow flow, String locatorsClassName, String locatorsPackage, String pagePackage) {
        String pageClassName = NameUtils.toClassName(flow.getFlowName()) + "Page";
        String methodName = NameUtils.toMethodName(flow.getFlowName()) + "Flow";
        String locatorFieldName = Character.toLowerCase(locatorsClassName.charAt(0)) + locatorsClassName.substring(1);

        // LinkedHashSet/LinkedHashMap so a method or locator class referenced by several
        // steps only contributes one import and one field — this, plus never duplicating the
        // unconditional waitForClick import below, is what fixes the "import static ...
        // waitForClick;" appearing twice in a previously generated file.
        Set<String> extraImports = new LinkedHashSet<>();
        Map<String, String> instanceFields = new LinkedHashMap<>();   // fieldName -> className
        Map<String, ExistingLocatorRef> reusedLocatorClasses = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        List<RecordedStep> steps = flow.getSteps();

        for (int i = 0; i < steps.size(); i++) {
            RecordedStep step = steps.get(i);
            switch (step.getActionType()) {
                case SCREEN_BOUNDARY -> body.append("        // --- screen: ")
                        .append(commentSafe(step.getScreenName() == null ? "(unnamed)" : step.getScreenName()))
                        .append(" ---\n");

                case TAP -> body.append("        waitForClick(")
                        .append(elementRef(step, locatorFieldName, extraImports, reusedLocatorClasses))
                        .append(").click();\n");

                case INPUT -> body.append("        waitForClick(")
                        .append(elementRef(step, locatorFieldName, extraImports, reusedLocatorClasses))
                        .append(").sendKeys(\"")
                        .append(JavaLiteralEscaper.escape(step.getInputValue())).append("\");\n");

                case SCROLL_DOWN, SCROLL_UP -> {
                    ReusableMethod reused = matchedMethod(step);
                    // scrollUntilElementFound(WebElement)/scrollUpUntilElementFound(WebElement) need a target —
                    // that target is whatever the QA engineer tapped right after scrolling to reveal it, i.e.
                    // the next TAP step in the recorded sequence. Falls back to a TODO only when the scroll
                    // is the last step or isn't immediately followed by a tap (nothing to infer a target from).
                    RecordedStep nextTap = nextTapStep(steps, i);
                    if (reused != null && nextTap != null) {
                        extraImports.add(reused.getImportStatement());
                        body.append("        ").append(reused.getFullyQualifiedCall()).append("(")
                                .append(elementRef(nextTap, locatorFieldName, extraImports, reusedLocatorClasses))
                                .append(");\n");
                    } else if (reused != null) {
                        extraImports.add(reused.getImportStatement());
                        body.append("        ").append(reused.getFullyQualifiedCall())
                                .append("(/* TODO: no tap follows this scroll — set the target element */);\n");
                    } else {
                        body.append("        // TODO: ")
                                .append(step.getActionType() == RecordedStep.ActionType.SCROLL_DOWN ? "scroll down" : "scroll up")
                                .append(" — no existing helper matched, verify ActionUtils\n");
                    }
                }

                case UTILITY_CALL -> {
                    ReusableMethod method = utilityMethod(step);
                    if (method == null) {
                        body.append("        // TODO: unrecognized utility action \"")
                                .append(commentSafe(step.getActionLabel())).append("\"\n");
                        break;
                    }
                    extraImports.add(method.getImportStatement());
                    if (method.isInstanceMethod()) {
                        // Matches MonthlySavingsPage's own `PaymentHelper payment = new PaymentHelper();`
                        // style — calling an instance method statically would not compile.
                        instanceFields.put(method.getInstanceFieldName(), method.getInstanceClassName());
                    }
                    if (method.isNeedsDriverArg()) {
                        extraImports.add("basePackage.DriverFactory");
                    }
                    body.append("        ").append(method.getFullyQualifiedCall()).append("(")
                            .append(renderArgs(method, step, locatorFieldName, extraImports, reusedLocatorClasses))
                            .append(");\n");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pagePackage).append(";\n\n");
        sb.append("import ").append(locatorsPackage).append(".").append(locatorsClassName).append(";\n");
        for (ExistingLocatorRef ref : reusedLocatorClasses.values()) {
            sb.append("import ").append(ref.getLocatorsPackage()).append(".")
                    .append(ref.getLocatorsClassName()).append(";\n");
        }
        for (String imp : extraImports) sb.append("import ").append(imp).append(";\n");
        sb.append("\nimport static utilsPackage.WaitUtils.waitForClick;\n\n");
        sb.append("public class ").append(pageClassName).append(" {\n");
        sb.append("    ").append(locatorsClassName).append(" ").append(locatorFieldName)
                .append(" = new ").append(locatorsClassName).append("(null);\n");
        for (ExistingLocatorRef ref : reusedLocatorClasses.values()) {
            sb.append("    ").append(ref.getLocatorsClassName()).append(" ").append(ref.instanceFieldName())
                    .append(" = new ").append(ref.getLocatorsClassName()).append("(null);\n");
        }
        for (var field : instanceFields.entrySet()) {
            sb.append("    ").append(field.getValue()).append(" ").append(field.getKey())
                    .append(" = new ").append(field.getValue()).append("();\n");
        }
        sb.append("\n    public void ").append(methodName).append("() {\n");
        sb.append(body);
        sb.append("    }\n");
        sb.append("}\n");

        return new Result(pageClassName, sb.toString());
    }

    /**
     * Renders one element reference, pointing either at this flow's own Locators class or
     * at an existing class QA chose to reuse. Reuse also registers the other class so it
     * gets an import and a page field.
     */
    private String elementRef(RecordedStep step, String locatorFieldName,
                              Set<String> extraImports, Map<String, ExistingLocatorRef> reusedClasses) {
        return elementRef(step.getReusedLocator(), step.getElementFieldName(), locatorFieldName, reusedClasses);
    }

    private String elementRef(ExistingLocatorRef reused, String ownFieldName, String locatorFieldName,
                              Map<String, ExistingLocatorRef> reusedClasses) {
        if (reused != null) {
            reusedClasses.putIfAbsent(reused.getLocatorsClassName(), reused);
            return reused.instanceFieldName() + "." + reused.getterName() + "()";
        }
        if (ownFieldName == null) return "/* TODO: element */";
        return locatorFieldName + ".get" + capitalize(ownFieldName) + "()";
    }

    /** Builds the argument list for a catalogued call, in declared MethodParam order. */
    private String renderArgs(ReusableMethod method, RecordedStep step, String locatorFieldName,
                              Set<String> extraImports, Map<String, ExistingLocatorRef> reusedClasses) {
        List<String> rendered = new ArrayList<>();
        if (method.isNeedsDriverArg()) {
            rendered.add("DriverFactory.driver");
        }

        UtilityArgs args = step.getUtilityArgs();
        for (MethodParam param : method.getParams() == null ? List.<MethodParam>of() : method.getParams()) {
            switch (param.getKind()) {
                case ELEMENT, ELEMENT_LIST -> {
                    List<UtilityArgs.ElementTarget> targets =
                            args == null ? List.of() : args.elementsFor(param.getName());
                    if (targets.isEmpty()) {
                        rendered.add("/* TODO: " + commentSafe(param.getName()) + " */");
                    } else {
                        for (UtilityArgs.ElementTarget target : targets) {
                            rendered.add(elementRef(target.getReusedFrom(), target.getFieldName(),
                                    locatorFieldName, reusedClasses));
                        }
                    }
                }
                case INT -> rendered.add(intLiteral(args, param));
                case STRING -> rendered.add("\"" + JavaLiteralEscaper.escape(
                        value(args, param) == null ? "" : value(args, param)) + "\"");
                case PAYMENT_STATUS -> {
                    extraImports.add("enums.PaymentStatus");
                    String raw = value(args, param);
                    rendered.add("PaymentStatus." + (raw == null || raw.isBlank() ? "SUCCESS" : raw.trim()));
                }
            }
        }
        return String.join(", ", rendered);
    }

    /** A non-numeric value here would emit code that does not compile, so fall back to the declared default. */
    private String intLiteral(UtilityArgs args, MethodParam param) {
        String raw = value(args, param);
        String fallback = param.getDefaultValue() == null ? "0" : param.getDefaultValue();
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return String.valueOf(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String value(UtilityArgs args, MethodParam param) {
        return args == null ? null : args.scalarFor(param.getName());
    }

    /** Scans forward from a scroll step to the next TAP, stopping at a screen boundary (a new screen means the scroll's target isn't on this one). */
    private RecordedStep nextTapStep(List<RecordedStep> steps, int fromIndex) {
        for (int j = fromIndex + 1; j < steps.size(); j++) {
            RecordedStep candidate = steps.get(j);
            if (candidate.getActionType() == RecordedStep.ActionType.TAP) {
                return candidate;
            }
            if (candidate.getActionType() == RecordedStep.ActionType.SCREEN_BOUNDARY) {
                return null;
            }
        }
        return null;
    }

    /**
     * RecordedStep.matchedExistingMethod holds the ReusableMethod.Kind name() the QA
     * engineer accepted in the dashboard's "use existing method?" prompt (set by
     * FlowSessionController when the suggestion is confirmed). Null means no suggestion
     * was accepted for this step, so codegen falls back to a raw call + TODO comment.
     */
    private ReusableMethod matchedMethod(RecordedStep step) {
        return methodCatalog.byKind(step.getMatchedExistingMethod()).orElse(null);
    }

    /** UTILITY_CALL steps record which catalog entry they invoke directly via utilityMethodKind, set at record time rather than via the reuse-suggestion popup (these are deliberate explicit actions, not inferred suggestions). */
    private ReusableMethod utilityMethod(RecordedStep step) {
        return methodCatalog.byKind(step.getUtilityMethodKind()).orElse(null);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A "//" line comment cannot safely contain a raw newline — anything after it would silently fall out of the comment and become uncommented (potentially non-compiling) code. */
    private String commentSafe(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }
}
