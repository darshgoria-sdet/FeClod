package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Emits a *Test.java file in the shape of the real TestNG tests (e.g. TrustSafetyCentreTest):
 * extends BaseSetup, page object as a field, one @Test method invoking the generated flow
 * method.
 *
 * Assertions are opt-in and narrow by design. The generator cannot know what a test is for,
 * so it asserts only what the recording itself established — see AssertionPlanner. With the
 * option off, the TODO stub comes back, because a test that silently asserts nothing while
 * looking complete is the worse of the two failures.
 */
@Service
public class TestClassGenerator {

    private final AssertionPlanner assertionPlanner;

    public TestClassGenerator(AssertionPlanner assertionPlanner) {
        this.assertionPlanner = assertionPlanner;
    }

    public record Result(String className, String fileContent) {}

    public Result generate(RecordedFlow flow, String pageClassName, String pagePackage, String testPackage) {
        String testClassName = NameUtils.toClassName(flow.getFlowName()) + "Test";
        String testMethodName = NameUtils.toMethodName(flow.getFlowName()) + "Test";
        String flowMethodName = NameUtils.toMethodName(flow.getFlowName()) + "Flow";
        String pageFieldName = Character.toLowerCase(pageClassName.charAt(0)) + pageClassName.substring(1);
        String lastScreenName = lastScreenReached(flow);

        var endState = assertionPlanner.endStateFor(flow);
        boolean assertions = flow.isGenerateAssertions();

        // Collected first, because what gets asserted decides what has to be imported.
        Set<String> imports = new LinkedHashSet<>();
        imports.add("basePackage.BaseSetup");
        imports.add("org.testng.annotations.Test");
        imports.add(pagePackage + "." + pageClassName);
        if (assertions) {
            imports.add("basePackage.DriverFactory");
            imports.add("org.testng.Assert");
        }
        if (endState.isPresent()) {
            imports.add("utilsPackage.WaitUtils");
            ExistingLocatorRef reused = endState.get().reusedLocator();
            if (reused != null) {
                imports.add(reused.getLocatorsPackage() + "." + reused.getLocatorsClassName());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(testPackage).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append("\n");

        // BaseSetup carries the TestNG lifecycle that creates the driver. A generated test
        // without it compiles and then fails at runtime with no session, which is how earlier
        // generated tests (FestiveSavingsTest) ended up differing from every hand-written one.
        sb.append("public class ").append(testClassName).append(" extends BaseSetup {\n");
        sb.append("    ").append(pageClassName).append(" ").append(pageFieldName)
                .append(" = new ").append(pageClassName).append("();\n");

        if (endState.isPresent() && endState.get().reusedLocator() != null) {
            ExistingLocatorRef reused = endState.get().reusedLocator();
            sb.append("    ").append(reused.getLocatorsClassName()).append(" ").append(reused.instanceFieldName())
                    .append(" = new ").append(reused.getLocatorsClassName()).append("(null);\n");
        }
        sb.append("\n");

        sb.append("    @Test(description = \"").append(JavaLiteralEscaper.escape(describe(flow, lastScreenName)))
                .append("\")\n");
        sb.append("    public void ").append(testMethodName).append("() {\n");
        sb.append("        ").append(pageFieldName).append(".").append(flowMethodName).append("();\n");

        if (!assertions) {
            sb.append("        // TODO: add assertions here — this flow reached")
                    .append(lastScreenName != null ? " \"" + commentSafe(lastScreenName) + "\"" : " an unnamed final screen")
                    .append(".\n");
            sb.append("        // e.g. AssertionUtils / Assert.assertTrue(...) against the expected end state,\n");
            sb.append("        // using an externalized string from src/main/resources/assertions/ per repo convention.\n");
        } else {
            sb.append("\n");
            sb.append("        Assert.assertNotNull(DriverFactory.driver, \"Driver session lost during the ")
                    .append(JavaLiteralEscaper.escape(NameUtils.toClassName(flow.getFlowName()))).append(" flow\");\n");
            endState.ifPresent(assertion -> sb.append("        Assert.assertTrue(WaitUtils.isVisible(10, ")
                    .append(elementRef(assertion)).append("),\n                \"")
                    .append(JavaLiteralEscaper.escape(assertion.description())).append("\");\n"));
            if (endState.isEmpty()) {
                // Said plainly rather than hidden: the session check alone is a weak test, and
                // QA should know the tool declined to invent the assertion that matters.
                sb.append("        // TODO: assert the expected end state — this flow ends on an action\n");
                sb.append("        // whose intended result only you know")
                        .append(lastScreenName != null ? " (last screen: \"" + commentSafe(lastScreenName) + "\")" : "")
                        .append(".\n");
            }
        }

        sb.append("    }\n");
        sb.append("}\n");

        return new Result(testClassName, sb.toString());
    }

    private String elementRef(AssertionPlanner.EndStateAssertion assertion) {
        ExistingLocatorRef reused = assertion.reusedLocator();
        if (reused != null) {
            return reused.instanceFieldName() + "." + reused.getterName() + "()";
        }
        return "/* TODO: " + commentSafe(assertion.ownFieldName()) + " */";
    }

    private String describe(RecordedFlow flow, String lastScreenName) {
        String base = NameUtils.toClassName(flow.getFlowName()) + " flow";
        return lastScreenName == null ? base : base + " — ends on " + lastScreenName;
    }

    private String lastScreenReached(RecordedFlow flow) {
        String last = null;
        for (RecordedStep step : flow.getSteps()) {
            if (step.getActionType() == RecordedStep.ActionType.SCREEN_BOUNDARY && step.getScreenName() != null) {
                last = step.getScreenName();
            }
        }
        return last;
    }

    private String commentSafe(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }
}
