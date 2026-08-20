package org.example.wavepilot.template;

import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.security.TemplateSecurityScanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateSecurityScannerTest {

    private final TemplateSecurityScanner scanner = new TemplateSecurityScanner();

    @Test
    void dangerousMatlabCallsAreBlockedWithRuleEvidence() {
        TemplateCandidate.CandidateFile evil = file("matlab/run_experiment.m",
                "function run_experiment()\n"
                        + "  system('rm -rf /');\n"
                        + "  !dir\n"
                        + "  eval('disp(1)');\n"
                        + "  webread('http://evil.example.com');\n"
                        + "  delete('important.mat');\n"
                        + "end\n");
        TemplateSecurityScanner.ScanResult result = scanner.scan(List.of(evil));
        assertFalse(result.passed(), "BLOCKED findings must fail the scan");
        assertTrue(result.blocked().stream().anyMatch(finding -> finding.ruleId().equals("MAT-001")));
        assertTrue(result.blocked().stream().anyMatch(finding -> finding.ruleId().equals("MAT-002")));
        assertTrue(result.blocked().stream().anyMatch(finding -> finding.ruleId().equals("MAT-008")));
        assertTrue(result.blocked().stream().anyMatch(finding -> finding.ruleId().equals("MAT-005")));
        assertTrue(result.blocked().stream().anyMatch(finding -> finding.ruleId().equals("MAT-007")));
        TemplateCandidate.SecurityFinding system = result.blocked().stream()
                .filter(finding -> finding.ruleId().equals("MAT-001")).findFirst().orElseThrow();
        assertTrue(system.file().endsWith("run_experiment.m"));
        assertTrue(system.line() != null && system.line() == 2);
        assertTrue(system.evidence().contains("system"));
    }

    @Test
    void suspiciousButNotBlockingCallsAreWarnings() {
        TemplateCandidate.CandidateFile file = file("matlab/run_experiment.m",
                "function run_experiment()\n"
                        + "  movefile('a.csv', 'b.csv');\n"
                        + "  load('results.mat');\n"
                        + "end\n");
        TemplateSecurityScanner.ScanResult result = scanner.scan(List.of(file));
        assertTrue(result.passed(), "warnings alone do not block");
        assertTrue(result.findings().stream().anyMatch(finding -> finding.ruleId().equals("MAT-010")));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.ruleId().equals("MAT-014")));
    }

    @Test
    void aPlainFixedTemplatePasses() {
        TemplateCandidate.CandidateFile file = file("matlab/run_experiment.m",
                "function run_experiment(inputFile, outputDir)\n"
                        + "  data = jsondecode(fileread(inputFile));\n"
                        + "  fprintf('frames=%d\\n', data.frames);\n"
                        + "end\n");
        TemplateSecurityScanner.ScanResult result = scanner.scan(List.of(file));
        assertTrue(result.passed());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void nonMatlabFilesAreNotScanned() {
        TemplateCandidate.CandidateFile file = file("README.md",
                "system('rm -rf /') is mentioned in documentation only");
        TemplateSecurityScanner.ScanResult result = scanner.scan(List.of(file));
        assertTrue(result.passed() && result.findings().isEmpty());
    }

    private TemplateCandidate.CandidateFile file(String path, String content) {
        return new TemplateCandidate.CandidateFile(path, content, "fixture-hash");
    }
}
