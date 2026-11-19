package com.jar.flowcapture.style;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mechanical (non-AI) gate run on generated file content before it's written into
 * Jar_Frontend_Automation, so style drift is caught deterministically rather than
 * hoped away. Findings are warnings surfaced in the dashboard for the reviewing
 * engineer, not hard failures — generation always completes, but nothing gets
 * silently merged without a human seeing these.
 */
@Service
public class StyleConformanceChecker {

    public record Finding(Severity severity, String message) {}
    public enum Severity { WARNING, ERROR }

    private static final Pattern NEW_JSON_LIB_IMPORT =
            Pattern.compile("import (com\\.google\\.gson|com\\.fasterxml\\.jackson)");

    public List<Finding> checkLocatorsFile(String content) {
        List<Finding> findings = new ArrayList<>();

        if (!content.contains("@Getter")) {
            findings.add(new Finding(Severity.ERROR, "Missing @Getter annotation — all existing *Locators classes use it"));
        }
        if (!content.contains("PageFactory.initElements")) {
            findings.add(new Finding(Severity.ERROR, "Missing PageFactory.initElements(...) branch in constructor"));
        }
        if (!content.contains("basePackage.LocatorRegistry.register")) {
            findings.add(new Finding(Severity.ERROR, "Missing LocatorRegistry.register(...) fallback branch in constructor"));
        }
        if (NEW_JSON_LIB_IMPORT.matcher(content).find()) {
            findings.add(new Finding(Severity.ERROR, "Introduces Gson/Jackson — repo only uses org.json, do not add a new JSON dependency"));
        }
        if (content.contains("Thread.sleep")) {
            findings.add(new Finding(Severity.WARNING, "Raw Thread.sleep found — prefer WaitUtils (waitForVisibility/retryUntil) per repo convention"));
        }
        long indexBasedCount = content.lines().filter(l -> l.contains(".instance(")).count();
        if (indexBasedCount > 0) {
            findings.add(new Finding(Severity.WARNING,
                    indexBasedCount + " index-based locator(s) generated — these are brittle; replace with resource-id/content-desc if available before merging"));
        }

        return findings;
    }

    public List<Finding> checkPageFile(String content) {
        List<Finding> findings = new ArrayList<>();

        if (content.contains(".click();") && !content.contains("waitForClick") && !content.contains("TODO")) {
            findings.add(new Finding(Severity.WARNING,
                    "A .click() call has neither waitForClick(...) nor a TODO marker — verify it's intentional"));
        }
        if (content.contains("Thread.sleep")) {
            findings.add(new Finding(Severity.WARNING, "Raw Thread.sleep found — prefer WaitUtils per repo convention"));
        }

        return findings;
    }

    public List<Finding> checkTestFile(String content) {
        List<Finding> findings = new ArrayList<>();

        if (!content.contains("@Test")) {
            findings.add(new Finding(Severity.ERROR, "Missing @Test annotation — not a valid TestNG test"));
        }
        if (!content.contains("// TODO") && !content.contains("assert")) {
            findings.add(new Finding(Severity.WARNING,
                    "No assertion and no TODO marker found — this test only exercises the flow without verifying anything"));
        }

        return findings;
    }

    public boolean hasBlockingErrors(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }
}
