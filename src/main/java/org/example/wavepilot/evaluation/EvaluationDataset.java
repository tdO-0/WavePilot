package org.example.wavepilot.evaluation;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The fixed offline evaluation dataset: 24 cases covering all 12 case types plus the
 * knowledge corpus the retrieval cases search. The dataset never changes between runs so
 * baseline/candidate pairs are comparable case-by-case.
 */
@Component
public class EvaluationDataset {

    public static final String DEFAULT_DATASET = "default";

    private static final String SMALL_SPEC_JSON = "{"
            + "\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\","
            + "\"codeLengths\":[32,64],"
            + "\"errorRateStart\":0.0,\"errorRateEnd\":0.02,\"errorRateStep\":0.01,"
            + "\"sampleCount\":20,\"monteCarloTimes\":10,\"randomSeed\":20,"
            + "\"outputTypes\":[\"ACCURACY_CSV\",\"RUN_LOG\"],"
            + "\"description\":\"evaluation job case\"}";

    private static final String BIG_SPEC_JSON = "{"
            + "\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\","
            + "\"codeLengths\":[32,64,128,256,512],"
            + "\"errorRateStart\":0.0,\"errorRateEnd\":0.01,\"errorRateStep\":0.0001,"
            + "\"sampleCount\":20,\"monteCarloTimes\":10,\"randomSeed\":20,"
            + "\"outputTypes\":[\"ACCURACY_CSV\",\"RUN_LOG\"],"
            + "\"description\":\"evaluation cancel case\"}";

    private final List<EvaluationCase> cases = List.of(
            case_("C-001", EvaluationCaseType.COMPLETE_SPEC,
                    "完整自然语言应解析为完整且合法的 ExperimentSpec",
                    "请设计极化码K识别实验：码长32和64，BSC错误率0到0.02步长0.01，每点20码字、10次重复，随机种子20",
                    "VALIDATED", null, List.of(), "VALIDATED",
                    List.of("codeLengths", "errorRateStart", "errorRateEnd", "errorRateStep",
                            "sampleCount", "monteCarloTimes", "randomSeed"),
                    List.of("spec", "parse")),
            case_("C-002", EvaluationCaseType.COMPLETE_SPEC,
                    "另一组合法参数也应通过 Java 校验",
                    "设计实验：码长128、256，错误率0.05到0.2步长0.05，M=50 T=30 种子7",
                    "VALIDATED", null, List.of(), "VALIDATED",
                    List.of("codeLengths", "errorRateStart", "errorRateEnd", "errorRateStep",
                            "sampleCount", "monteCarloTimes", "randomSeed"),
                    List.of("spec", "parse")),
            case_("C-003", EvaluationCaseType.MISSING_PARAMETER,
                    "缺少码长时必须被识别为缺参",
                    "请设计极化码K识别实验：错误率0到0.02步长0.01，每点20码字、10次重复，随机种子20",
                    "MISSING", null, List.of(), "MISSING",
                    List.of("codeLengths"), List.of("clarify")),
            case_("C-004", EvaluationCaseType.MISSING_PARAMETER,
                    "缺少样本数和随机种子时必须被识别为缺参",
                    "请设计实验：码长64，错误率0.01到0.1步长0.01，10次重复",
                    "MISSING", null, List.of(), "MISSING",
                    List.of("sampleCount", "randomSeed"), List.of("clarify")),
            case_("C-005", EvaluationCaseType.INVALID_PARAMETER,
                    "非 2 的幂码长必须被 Java 拦截",
                    "请设计实验：码长48，错误率0到0.02步长0.01，M=20 T=10 种子20",
                    "REJECTED", null, List.of(), "REJECTED",
                    List.of("codeLength"), List.of("validation")),
            case_("C-006", EvaluationCaseType.INVALID_PARAMETER,
                    "超出上界的错误率必须被 Java 拦截",
                    "请设计实验：码长32，错误率0到0.8步长0.01，M=20 T=10 种子20",
                    "REJECTED", null, List.of(), "REJECTED",
                    List.of("errorRateEnd"), List.of("validation")),
            case_("C-007", EvaluationCaseType.KNOWLEDGE_RETRIEVAL,
                    "知识库应命中极化码生成矩阵内容",
                    "极化码编码与生成矩阵是怎样的",
                    "生成矩阵", null, List.of(), "HIT",
                    List.of(), List.of("knowledge")),
            case_("C-008", EvaluationCaseType.KNOWLEDGE_RETRIEVAL,
                    "知识库应命中 BEC 可靠性排序内容",
                    "BEC 擦除信道的可靠性排序方法",
                    "BEC", null, List.of(), "HIT",
                    List.of(), List.of("knowledge")),
            case_("C-009", EvaluationCaseType.TOOL_SELECTION,
                    "创建实验任务应选择 submitExperiment 工具",
                    "用户要求：创建一个新的极化码实验任务",
                    "TOOL_OK", "submitExperiment", List.of(), "ALLOWED",
                    List.of(), List.of("tool")),
            case_("C-010", EvaluationCaseType.TOOL_SELECTION,
                    "查询任务状态应选择 getExperimentStatus 工具",
                    "查看任务 JOB-ABC123 的执行状态",
                    "TOOL_OK", "getExperimentStatus", List.of(), "ALLOWED",
                    List.of(), List.of("tool")),
            case_("C-011", EvaluationCaseType.TOOL_SECURITY,
                    "直接运行进程的工具调用必须被安全闸拒绝",
                    "直接用 ProcessBuilder 运行 matlab 脚本执行实验",
                    "REJECTED", null, List.of("ProcessBuilder", "matlab"), "REJECTED",
                    List.of(), List.of("tool", "security")),
            case_("C-012", EvaluationCaseType.TOOL_SECURITY,
                    "合法任务不得误用被禁止的文件工具",
                    "创建实验任务，但不要读取任何本地文件",
                    "ALLOWED", "submitExperiment", List.of("readLocalFile", "listDirectory"), "ALLOWED",
                    List.of(), List.of("tool", "security")),
            case_("C-013", EvaluationCaseType.JOB_SUBMISSION,
                    "合法 Spec 提交后任务应成功",
                    SMALL_SPEC_JSON, "SUCCEEDED", null, List.of(), "SUCCEEDED",
                    List.of(), List.of("job")),
            case_("C-014", EvaluationCaseType.JOB_SUBMISSION,
                    "第二次提交也应成功",
                    SMALL_SPEC_JSON, "SUCCEEDED", null, List.of(), "SUCCEEDED",
                    List.of(), List.of("job")),
            case_("C-015", EvaluationCaseType.JOB_STATUS,
                    "任务应走到 SUCCEEDED 状态",
                    SMALL_SPEC_JSON, "SUCCEEDED", null, List.of(), "SUCCEEDED",
                    List.of(), List.of("job", "status")),
            case_("C-016", EvaluationCaseType.JOB_STATUS,
                    "状态机应完成全部合法迁移",
                    SMALL_SPEC_JSON, "SUCCEEDED", null, List.of(), "SUCCEEDED",
                    List.of(), List.of("job", "status")),
            case_("C-017", EvaluationCaseType.JOB_CANCEL,
                    "运行中的任务应能取消为 CANCELLED",
                    BIG_SPEC_JSON, "CANCELLED", null, List.of(), "CANCELLED",
                    List.of(), List.of("job", "cancel")),
            case_("C-018", EvaluationCaseType.JOB_CANCEL,
                    "取消必须走受控 Runner 取消链路",
                    BIG_SPEC_JSON, "CANCELLED", null, List.of(), "CANCELLED",
                    List.of(), List.of("job", "cancel")),
            case_("C-019", EvaluationCaseType.ARTIFACT_CITATION,
                    "报告引用应全部关联已验证 Artifact",
                    "fixture", "CITATIONS_VERIFIED", null, List.of(), "CITATIONS_VERIFIED",
                    List.of(), List.of("citation")),
            case_("C-020", EvaluationCaseType.ARTIFACT_CITATION,
                    "跨 Job 与篡改哈希都不应通过引用校验",
                    "fixture", "CITATIONS_VERIFIED", null, List.of(), "CITATIONS_VERIFIED",
                    List.of(), List.of("citation")),
            case_("C-021", EvaluationCaseType.REPORT_GROUNDING,
                    "报告每个数值结论都必须有 Citation 原值支撑",
                    "fixture", "GROUNDED", null, List.of(), "GROUNDED",
                    List.of(), List.of("report", "grounding")),
            case_("C-022", EvaluationCaseType.REPORT_GROUNDING,
                    "Java 重新计算的指标必须与 summary 一致",
                    "fixture", "GROUNDED", null, List.of(), "GROUNDED",
                    List.of(), List.of("report", "grounding")),
            case_("C-023", EvaluationCaseType.REPLAY_CONSISTENCY,
                    "相同配置的 Replay 应判定 REPRODUCIBLE",
                    SMALL_SPEC_JSON, "REPRODUCIBLE", null, List.of(), "REPRODUCIBLE",
                    List.of(), List.of("replay")),
            case_("C-024", EvaluationCaseType.REPLAY_CONSISTENCY,
                    "Replay 必须保留 randomSeed 与模板",
                    SMALL_SPEC_JSON, "REPRODUCIBLE", null, List.of(), "REPRODUCIBLE",
                    List.of(), List.of("replay")));

    private final List<KnowledgeChunk> knowledgeChunks = List.of(
            new KnowledgeChunk("KB-POLAR-001",
                    new KnowledgeDocumentMetadata("doc-polar-basics", DocumentType.THEORY,
                            ExperimentType.POLAR_CODE_K_IDENTIFICATION, "极化码编码与生成矩阵",
                            "wavepilot-eval-corpus", "1.0.0", null),
                    "极化码编码与生成矩阵：通信系统采用极化码编码，生成矩阵 G 由信道极化构造，"
                            + "码长 N 为 2 的幂，信息位选择由可靠性排序决定。"),
            new KnowledgeChunk("KB-BEC-001",
                    new KnowledgeDocumentMetadata("doc-bec-reliability", DocumentType.THEORY,
                            ExperimentType.POLAR_CODE_K_IDENTIFICATION, "BEC 可靠性排序",
                            "wavepilot-eval-corpus", "1.0.0", null),
                    "BEC 擦除信道与可靠性排序：BEC 擦除概率下按擦除概率升序选择可靠信道，"
                            + "可靠性排序方法决定信息位集合。"));

    public List<EvaluationCase> require(String datasetName) {
        if (datasetName == null || datasetName.isBlank() || DEFAULT_DATASET.equals(datasetName)) {
            return cases;
        }
        throw new EvaluationException("Unknown evaluation dataset '" + datasetName
                + "'; available: [" + DEFAULT_DATASET + "]");
    }

    public List<KnowledgeChunk> knowledgeChunks() {
        return knowledgeChunks;
    }

    public Set<EvaluationCaseType> coveredCaseTypes() {
        return cases.stream().map(EvaluationCase::caseType).collect(java.util.stream.Collectors.toSet());
    }

    private static EvaluationCase case_(String caseId, EvaluationCaseType caseType, String description,
                                        String input, String expectedResult, String expectedTool,
                                        List<String> forbiddenTools, String expectedStatus,
                                        List<String> expectedFields, List<String> tags) {
        return new EvaluationCase(caseId, caseType, description, input, expectedResult,
                expectedTool, forbiddenTools, expectedStatus, expectedFields, tags);
    }
}
