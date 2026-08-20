package org.example.wavepilot.template.security;

import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static security scanner for candidate MATLAB files. Findings are layered:
 * BLOCKED (must be fixed before publishing), WARNING (requires review), and a scan without
 * BLOCKED findings counts as PASSED. Scanning is rule-based and layered, never a single
 * coarse regex claim of absolute safety: every rule carries an id, a severity, the file,
 * line, message and evidence.
 */
@Component
public class TemplateSecurityScanner {

    public enum Severity { BLOCKED, WARNING }

    public record ScanResult(boolean passed, List<TemplateCandidate.SecurityFinding> findings) {
        public List<TemplateCandidate.SecurityFinding> blocked() {
            return findings.stream()
                    .filter(finding -> Severity.BLOCKED.name().equals(finding.severity()))
                    .toList();
        }
    }

    private record Rule(String ruleId, Severity severity, Pattern pattern, String message) { }

    private static final List<Rule> RULES = List.of(
            rule("MAT-001", Severity.BLOCKED, "\\bsystem\\s*\\(", "system() 执行系统命令被禁止"),
            rule("MAT-002", Severity.BLOCKED, "(?<!\\w)[!]\\s*[A-Za-z]", "bang (!) 系统命令被禁止"),
            rule("MAT-003", Severity.BLOCKED, "\\bunix\\s*\\(", "unix() 被禁止"),
            rule("MAT-004", Severity.BLOCKED, "\\bdos\\s*\\(", "dos() 被禁止"),
            rule("MAT-005", Severity.BLOCKED, "\\bwebread\\s*\\(|\\bwebsave\\s*\\(|\\burlread\\s*\\(|\\burlwrite\\s*\\(",
                    "网络下载/上传调用被禁止"),
            rule("MAT-006", Severity.BLOCKED, "\\btcpclient\\s*\\(|\\budpport\\s*\\(|\\bftp\\s*\\(",
                    "网络连接调用被禁止"),
            rule("MAT-007", Severity.BLOCKED, "\\bdelete\\s*\\(|\\brmdir\\s*\\(", "删除文件/目录调用被禁止"),
            rule("MAT-008", Severity.BLOCKED, "\\beval\\s*\\(|\\bevalin\\s*\\(|\\bassignin\\s*\\(|\\bfeval\\s*\\(",
                    "动态执行调用被禁止"),
            rule("MAT-009", Severity.BLOCKED, "\\bjava\\s*\\(|\\bpy\\.\\w+\\s*\\(", "外部运行时调用被禁止"),
            rule("MAT-010", Severity.WARNING, "\\bmovefile\\s*\\(|\\bcopyfile\\s*\\(",
                    "文件移动/复制需人工确认目标在工作目录内"),
            rule("MAT-011", Severity.WARNING, "\\bfopen\\s*\\(", "fopen 需人工确认不访问绝对路径"),
            rule("MAT-012", Severity.WARNING, "\\bcd\\s*\\(", "cd 需人工确认不离开工作目录"),
            rule("MAT-013", Severity.WARNING, "\\baddpath\\s*\\(", "addpath 需人工确认不引用外部路径"),
            rule("MAT-014", Severity.WARNING, "\\bload\\s*\\(|\\bsave\\s*\\(", "load/save 需人工确认仅使用工作目录内文件"),
            rule("MAT-015", Severity.WARNING, "[A-Za-z]:\\\\|[A-Za-z]:/", "疑似绝对路径引用，需人工确认"));

    public ScanResult scan(List<TemplateCandidate.CandidateFile> files) {
        List<TemplateCandidate.SecurityFinding> findings = new ArrayList<>();
        for (TemplateCandidate.CandidateFile file : files) {
            if (!file.relativePath().endsWith(".m")) continue;
            String[] lines = file.content().split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                for (Rule rule : RULES) {
                    var matcher = rule.pattern().matcher(line);
                    if (matcher.find()) {
                        findings.add(new TemplateCandidate.SecurityFinding(
                                rule.ruleId(), rule.severity().name(), file.relativePath(),
                                index + 1, rule.message(),
                                line.trim().substring(0, Math.min(80, line.trim().length()))));
                    }
                }
            }
        }
        boolean passed = findings.stream().noneMatch(finding -> Severity.BLOCKED.name().equals(finding.severity()));
        return new ScanResult(passed, findings);
    }

    private static Rule rule(String id, Severity severity, String regex, String message) {
        return new Rule(id, severity, Pattern.compile(regex), message);
    }
}
