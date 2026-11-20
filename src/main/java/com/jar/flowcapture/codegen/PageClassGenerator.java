package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import com.jar.flowcapture.capture.TryBlock;
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

        // Try/catch is emitted by watching the step list for a change of block, rather than
        // from a pair of indexes on the block: steps move, and a try that slid onto the wrong
        // ones would be a silent change of meaning. A block split by a later insertion simply
        // renders as two try/catch blocks with the same handling, which is still correct.
        String openGroup = null;
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep step = steps.get(i);
            // A catch step is not part of the main sequence; it is emitted inside the catch of
            // the block it belongs to, wherever that block's try sits.
            if (step.getCatchGroup() != null) continue;
            StringBuilder stepOut = new StringBuilder();
            renderStep(step, steps, i, stepOut, locatorFieldName, extraImports,
                    reusedLocatorClasses, instanceFields);

            String group = step.getTryGroup();
            if (!java.util.Objects.equals(group, openGroup)) {
                if (openGroup != null) body.append(renderCatch(flow, openGroup, locatorFieldName,
                extraImports, reusedLocatorClasses, instanceFields));
                if (group != null) body.append("        try {\n");
                openGroup = group;
            }
            body.append(openGroup == null ? stepOut : indentOneLevel(stepOut));
        }
        if (openGroup != null) body.append(renderCatch(flow, openGroup, locatorFieldName,
                extraImports, reusedLocatorClasses, instanceFields));

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pagePackage).append(";\n\n");
        sb.append("import ").append(locatorsPackage).append(".").append(locatorsClassName).append(";\n");
        for (ExistingLocatorRef ref : reusedLocatorClasses.values()) {
            sb.append("import ").append(ref.getLocatorsPackage()).append(".")
                    .append(ref.getLocatorsClassName()).append(";\n");
        }
        for (String imp : extraImports) sb.append("import ").append(imp).append(";\n");
        boolean logsInACatch = flow.getTryBlocks().stream()
                .anyMatch(b -> b.getOnFailure() == TryBlock.OnFailure.CONTINUE);
        if (logsInACatch) sb.append("import lombok.extern.slf4j.Slf4j;\n");
        sb.append("\nimport static utilsPackage.WaitUtils.waitForClick;\n\n");
        // Matches HandlingBottomSheets, which is this repo's own answer to the optional-screen
        // problem: @Slf4j on the class, log.info in the catch.
        if (logsInACatch) sb.append("@Slf4j\n");
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
     * One step's generated lines, at the method's own indent.
     *
     * Extracted so the catch of a RUN_RECOVERY_STEPS block can render its backup steps with
     * exactly the same code as the main sequence — a second rendering path would drift, and
     * the whole point of a recovery step is that it is an ordinary step.
     *
     * @param index this step's position for look-ahead, or -1 when rendering inside a catch,
     *              where "the next tap" is not a meaningful question
     */
    private void renderStep(RecordedStep step, List<RecordedStep> steps, int index, StringBuilder stepOut,
                            String locatorFieldName, Set<String> extraImports,
                            Map<String, ExistingLocatorRef> reusedLocatorClasses,
                            Map<String, String> instanceFields) {
            switch (step.getActionType()) {
                case SCREEN_BOUNDARY -> stepOut.append("        // --- screen: ")
                        .append(commentSafe(step.getScreenName() == null ? "(unnamed)" : step.getScreenName()))
                        .append(" ---\n");

                case TAP -> stepOut.append("        waitForClick(")
                        .append(elementRef(step, locatorFieldName, extraImports, reusedLocatorClasses))
                        .append(").click();\n");

                case INPUT -> stepOut.append("        waitForClick(")
                        .append(elementRef(step, locatorFieldName, extraImports, reusedLocatorClasses))
                        .append(").sendKeys(\"")
                        .append(JavaLiteralEscaper.escape(step.getInputValue())).append("\");\n");

                case SCROLL_DOWN, SCROLL_UP -> {
                    ReusableMethod reused = matchedMethod(step);
                    // scrollUntilElementFound(WebElement)/scrollUpUntilElementFound(WebElement) need a target —
                    // that target is whatever the QA engineer tapped right after scrolling to reveal it, i.e.
                    // the next TAP step in the recorded sequence. Falls back to a TODO only when the scroll
                    // is the last step or isn't immediately followed by a tap (nothing to infer a target from).
                    // -1 means "inside a catch", where there is no following step to read a
                    // scroll target from; the TODO branch below says so in the generated code.
                    RecordedStep nextTap = index < 0 ? null : nextTapStep(steps, index);
                    if (reused != null && nextTap != null) {
                        extraImports.add(reused.getImportStatement());
                        stepOut.append("        ").append(reused.getFullyQualifiedCall()).append("(")
                                .append(elementRef(nextTap, locatorFieldName, extraImports, reusedLocatorClasses))
                                .append(");\n");
                    } else if (reused != null) {
                        extraImports.add(reused.getImportStatement());
                        stepOut.append("        ").append(reused.getFullyQualifiedCall())
                                .append("(/* TODO: no tap follows this scroll — set the target element */);\n");
                    } else {
                        stepOut.append("        // TODO: ")
                                .append(step.getActionType() == RecordedStep.ActionType.SCROLL_DOWN ? "scroll down" : "scroll up")
                                .append(" — no existing helper matched, verify ActionUtils\n");
                    }
                }

                case UTILITY_CALL -> {
                    ReusableMethod method = utilityMethod(step);
                    if (method == null) {
                        stepOut.append("        // TODO: unrecognized utility action \"")
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
                    stepOut.append("        ").append(method.getFullyQualifiedCall()).append("(")
                            .append(renderArgs(method, step, locatorFieldName, extraImports, reusedLocatorClasses))
                            .append(");\n");
                }
            }
    }

    /**
     * The closing half of a try block, in the shape the repo already writes by hand.
     *
     * CONTINUE logs and carries on, which is what makes an optional screen optional.
     * FAIL_WITH_MESSAGE still fails, but with the flow author's sentence instead of
     * "no such element", which is the difference between a reviewer understanding a CI
     * failure and having to reproduce it.
     */
    private String renderCatch(RecordedFlow flow, String groupId, String locatorFieldName,
                               Set<String> extraImports, Map<String, ExistingLocatorRef> reusedClasses,
                               Map<String, String> instanceFields) {
        TryBlock block = flow.tryBlock(groupId).orElse(null);
        String note = (block == null || block.getNote() == null || block.getNote().isBlank())
                ? "optional steps did not run"
                : commentSafe(block.getNote());
        StringBuilder out = new StringBuilder("        } catch (Exception e) {\n");

        if (block != null && block.getOnFailure() == TryBlock.OnFailure.FAIL_WITH_MESSAGE) {
            out.append("            throw new AssertionError(\"")
                    .append(JavaLiteralEscaper.escape(note)).append("\", e);\n");
            return out.append("        }\n").toString();
        }

        out.append("            log.info(\"").append(JavaLiteralEscaper.escape(note)).append("\");\n");
        out.append("            log.info(e.getMessage());\n");

        // The backup route. These steps only run because the first way through failed, so the
        // log line above them is what tells a reviewer why the test took this path.
        List<RecordedStep> recovery = flow.recoveryStepsFor(groupId);
        for (RecordedStep step : recovery) {
            StringBuilder stepOut = new StringBuilder();
            renderStep(step, flow.getSteps(), -1, stepOut, locatorFieldName,
                    extraImports, reusedClasses, instanceFields);
            out.append(indentOneLevel(stepOut));
        }
        return out.append("        }\n").toString();
    }

    /** Pushes a step's generated lines one level in, so the try block reads as a block. */
    private String indentOneLevel(CharSequence lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines.toString().split("\n", -1)) {
            if (line.isEmpty()) continue;
            out.append("    ").append(line).append("\n");
        }
        return out.toString();
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
