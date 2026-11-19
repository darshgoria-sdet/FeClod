package com.jar.flowcapture.codegen;

import com.jar.flowcapture.capture.RecordedFlow;
import com.jar.flowcapture.capture.RecordedStep;
import org.springframework.stereotype.Service;

/**
 * Emits a *Test.java file in the shape of the real TestNG tests (e.g. PendingStatusTest):
 * page object instantiated as a field, a single @Test method invoking the generated
 * flow method. Assertions are deliberately left as TODO-marked stubs, never guessed —
 * an assertion encodes the actual intent of the test, and a silently wrong
 * auto-generated assertion is worse than an engineer having to write one by hand.
 * The final screen reached (last SCREEN_BOUNDARY's name) is surfaced as a comment
 * hint for what that assertion probably needs to check.
 */
@Service
public class TestClassGenerator {

    public record Result(String className, String fileContent) {}

    public Result generate(RecordedFlow flow, String pageClassName, String pagePackage, String testPackage) {
        String testClassName = NameUtils.toClassName(flow.getFlowName()) + "Test";
        String testMethodName = NameUtils.toMethodName(flow.getFlowName()) + "Test";
        String flowMethodName = NameUtils.toMethodName(flow.getFlowName()) + "Flow";
        String pageFieldName = Character.toLowerCase(pageClassName.charAt(0)) + pageClassName.substring(1);

        String lastScreenName = lastScreenReached(flow);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(testPackage).append(";\n\n");
        sb.append("import org.testng.annotations.Test;\n");
        sb.append("import ").append(pagePackage).append(".").append(pageClassName).append(";\n\n");
        sb.append("public class ").append(testClassName).append(" {\n");
        sb.append("    ").append(pageClassName).append(" ").append(pageFieldName)
                .append(" = new ").append(pageClassName).append("();\n\n");
        sb.append("    @Test\n");
        sb.append("    public void ").append(testMethodName).append("() {\n");
        sb.append("        ").append(pageFieldName).append(".").append(flowMethodName).append("();\n");
        sb.append("        // TODO: add assertions here — this flow reached")
                .append(lastScreenName != null ? " \"" + lastScreenName + "\"" : " an unnamed final screen")
                .append(".\n");
        sb.append("        // e.g. AssertionUtils / Assert.assertTrue(...) against the expected end state,\n");
        sb.append("        // using an externalized string from src/main/resources/assertions/ per repo convention.\n");
        sb.append("    }\n");
        sb.append("}\n");

        return new Result(testClassName, sb.toString());
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
}
