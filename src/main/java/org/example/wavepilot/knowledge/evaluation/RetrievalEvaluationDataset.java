package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RetrievalEvaluationDataset {
    public static final String NAME = "wavepilot-bilingual-retrieval-v2";
    private static final ExperimentType TYPE = ExperimentType.POLAR_CODE_K_IDENTIFICATION;

    private final List<KnowledgeChunk> chunks = List.of(
            chunk("RET-T-BPSK", "RET-DOC-BPSK-AWGN", DocumentType.THEORY, "BPSK decision rule",
                    "Modulation", "BPSK maps binary symbols to antipodal amplitudes. Coherent detection decides from the sign of the matched-filter sample."),
            chunk("RET-T-AWGN", "RET-DOC-BPSK-AWGN", DocumentType.THEORY, "AWGN channel model",
                    "Channel", "AWGN adds independent zero-mean Gaussian noise. Its variance follows the requested SNR or Eb/N0 convention."),
            chunk("RET-T-BER", "RET-DOC-ERROR-METRICS", DocumentType.THEORY, "BER definition",
                    "Metrics", "Bit error rate BER is the number of erroneous decoded bits divided by all transmitted bits."),
            chunk("RET-T-BLER", "RET-DOC-ERROR-METRICS", DocumentType.THEORY, "BLER definition",
                    "Metrics", "Block error rate BLER counts a whole frame as wrong when at least one protected block fails decoding."),
            chunk("RET-T-EBN0", "RET-DOC-LINK-METRICS", DocumentType.THEORY, "Eb/N0 and SNR",
                    "Link budget", "Eb/N0 is energy per information bit divided by noise spectral density; conversion to SNR depends on spectral efficiency and sample rate."),
            chunk("RET-T-CAPACITY", "RET-DOC-INFORMATION", DocumentType.THEORY, "Noisy-channel capacity",
                    "Information theory", "Channel capacity is the maximum reliable information rate supported by a noisy link under stated constraints."),
            chunk("RET-T-POLAR", "RET-DOC-CODING", DocumentType.THEORY, "Polar channel polarization",
                    "Polar codes", "Polar coding recursively transforms synthetic channels into reliable and unreliable subchannels; information bits use the reliable set."),
            chunk("RET-T-LDPC", "RET-DOC-CODING", DocumentType.THEORY, "LDPC iterative decoding",
                    "LDPC", "LDPC belief-propagation exchanges variable-node and check-node messages until parity checks pass or iterations are exhausted."),
            chunk("RET-T-OFDM", "RET-DOC-OFDM", DocumentType.THEORY, "OFDM orthogonality",
                    "Multicarrier", "OFDM places subcarriers at orthogonal frequency spacing so overlapping spectra can be separated by the FFT."),
            chunk("RET-T-MULTIPATH", "RET-DOC-OFDM", DocumentType.THEORY, "Cyclic prefix against multipath",
                    "Guard interval", "A cyclic prefix longer than the channel delay spread converts linear convolution to circular convolution and limits inter-symbol interference."),
            chunk("RET-T-QAM", "RET-DOC-MODULATION", DocumentType.THEORY, "QAM order tradeoff",
                    "Modulation", "Higher-order QAM carries more bits per symbol but needs greater signal quality for the same error probability."),
            chunk("RET-T-CONFIDENCE", "RET-DOC-STATISTICS", DocumentType.THEORY, "Monte Carlo confidence",
                    "Statistics", "Rare-error estimates need enough independent trials; confidence intervals quantify sampling uncertainty instead of treating one BER estimate as exact."),

            chunk("RET-P-SNR", "RET-DOC-SIM-PARAM", DocumentType.MATLAB_GUIDE, "SNR sweep parameters",
                    "snrStart snrEnd snrStep", "Use snrStart, snrEnd and snrStep to define an inclusive SNR sweep. Validate finite bounds and a positive step."),
            chunk("RET-P-EBN0", "RET-DOC-SIM-PARAM", DocumentType.MATLAB_GUIDE, "EbN0 parameter convention",
                    "ebN0Db", "The ebN0Db parameter is expressed in dB. Keep the code rate and bitsPerSymbol conversion consistent before calling awgn."),
            chunk("RET-P-SAMPLES", "RET-DOC-SIM-PARAM", DocumentType.MATLAB_GUIDE, "sampleCount and frames",
                    "sampleCount frameCount", "sampleCount controls symbols per point and frameCount controls repeated frames; larger values reduce variance but increase runtime."),
            chunk("RET-P-MONTE", "RET-DOC-SIM-PARAM", DocumentType.MATLAB_GUIDE, "Monte Carlo repetitions",
                    "monteCarloTimes", "monteCarloTimes must be a positive bounded integer. Increase repetitions when confidence is insufficient."),
            chunk("RET-P-SEED", "RET-DOC-REPRO", DocumentType.MATLAB_GUIDE, "randomSeed reproducibility",
                    "randomSeed rng", "Set randomSeed before signal and noise generation. MATLAB rng(randomSeed,'twister') makes the software fixture repeatable."),
            chunk("RET-P-POLAR", "RET-DOC-POLAR-PARAM", DocumentType.MATLAB_GUIDE, "Polar code length",
                    "codeLength informationLength", "Polar codeLength is a supported power of two and informationLength must not exceed it; their ratio determines code rate."),
            chunk("RET-P-OFDM", "RET-DOC-OFDM-PARAM", DocumentType.MATLAB_GUIDE, "OFDM FFT and CP parameters",
                    "fftSize cpLength", "fftSize selects the number of subcarriers. cpLength should cover the modeled channel memory without excessive overhead."),
            chunk("RET-P-STOP", "RET-DOC-SIM-PARAM", DocumentType.MATLAB_GUIDE, "Early-stop error target",
                    "targetErrors maxBits", "Stop a BER point after targetErrors are observed or maxBits are simulated; report which limit ended the point."),
            chunk("RET-P-TOL", "RET-DOC-VALIDATION-PARAM", DocumentType.MATLAB_GUIDE, "Replay numeric tolerance",
                    "numericTolerance", "numericTolerance defines the maximum accepted replay difference and must not be used to hide non-deterministic drift."),
            chunk("RET-P-CUSTOM", "RET-DOC-VALIDATION-PARAM", DocumentType.MATLAB_GUIDE, "MATLAB parameter names",
                    "decodeFrame noiseVariance", "Keep camelCase decodeFrame and snake_case noise_variance names stable across JSON input, MATLAB functions, and result metadata."),

            chunk("RET-F-DIM", "RET-DOC-MATLAB-FAIL", DocumentType.FAILURE_CASE, "Matrix dimensions must agree",
                    "Dimension mismatch", "MATLAB dimension mismatch errors require inspecting size(A), size(B), transpose orientation, and element-wise versus matrix operators."),
            chunk("RET-F-INDEX", "RET-DOC-MATLAB-FAIL", DocumentType.FAILURE_CASE, "Index exceeds array bounds",
                    "Indexing", "An index exceeds array bounds error usually comes from a loop endpoint, one-based indexing, or an unexpectedly empty vector."),
            chunk("RET-F-NAN", "RET-DOC-NUMERIC-FAIL", DocumentType.FAILURE_CASE, "NaN or Inf result",
                    "Non-finite output", "NaN and Inf often indicate division by zero, log10 of an invalid value, overflow, or an empty aggregation. Reject them in result validation."),
            chunk("RET-F-TOOLBOX", "RET-DOC-MATLAB-FAIL", DocumentType.FAILURE_CASE, "Missing Communications Toolbox",
                    "Toolbox", "Undefined comm.AWGNChannel or qammod may mean the Communications Toolbox is missing or the installed release uses another API."),
            chunk("RET-F-UNDEFINED", "RET-DOC-MATLAB-FAIL", DocumentType.FAILURE_CASE, "Undefined function or variable",
                    "MATLAB path", "Undefined function decodeFrame can mean a file-name mismatch, wrong MATLAB path, local function scope, or misspelled camelCase identifier."),
            chunk("RET-F-COMPLEX", "RET-DOC-NUMERIC-FAIL", DocumentType.FAILURE_CASE, "Unexpected complex values",
                    "Complex arithmetic", "Unexpected complex BER inputs can follow sqrt of a negative variance or a missing abs operation before power calculation."),
            chunk("RET-F-MEMORY", "RET-DOC-RUNTIME-FAIL", DocumentType.FAILURE_CASE, "Out of memory during sweep",
                    "Memory", "Avoid materializing every noise realization. Process frames in bounded batches and preallocate only the current result arrays."),
            chunk("RET-F-FILE", "RET-DOC-RUNTIME-FAIL", DocumentType.FAILURE_CASE, "Cannot open result file",
                    "File I/O", "fopen or writetable failures require checking the approved artifact directory, permissions, file locks, and relative path handling."),
            chunk("RET-F-RNG", "RET-DOC-NUMERIC-FAIL", DocumentType.FAILURE_CASE, "Replay differs with same seed",
                    "Random stream", "A replay can drift when rng is called after random draws, parallel workers use independent streams, or hidden state changes draw order."),
            chunk("RET-F-PARFOR", "RET-DOC-RUNTIME-FAIL", DocumentType.FAILURE_CASE, "parfor sliced variable error",
                    "Parallel loop", "A parfor sliced-variable classification error is fixed by using independent indexed assignments and avoiding loop-carried mutable state."),

            chunk("RET-G-BPSK", "RET-DOC-RECIPES", DocumentType.EXPERIMENT_RECIPE, "BPSK AWGN BER recipe",
                    "BPSK workflow", "Generate random bits, map BPSK symbols, add AWGN for each Eb/N0 point, detect signs, count errors, validate CSV, and cite artifacts."),
            chunk("RET-G-POLAR", "RET-DOC-RECIPES", DocumentType.EXPERIMENT_RECIPE, "Polar-code experiment recipe",
                    "Polar workflow", "Validate code length and rate, construct the reliable set, encode, transmit through the approved channel, decode, and verify grounded metrics."),
            chunk("RET-G-OFDM", "RET-DOC-RECIPES", DocumentType.EXPERIMENT_RECIPE, "OFDM multipath experiment",
                    "OFDM workflow", "Sweep cpLength against a fixed multipath profile, keep fftSize and payload constant, then compare BER and overhead."),
            chunk("RET-G-LDPC", "RET-DOC-RECIPES", DocumentType.EXPERIMENT_RECIPE, "LDPC iteration benchmark",
                    "LDPC workflow", "Compare decoder iteration limits on the same frames and seeds, record convergence and BER, then verify parity-check evidence."),
            chunk("RET-G-COMPARE", "RET-DOC-EVAL-GUIDE", DocumentType.EXPERIMENT_RECIPE, "Fair baseline comparison",
                    "Comparison", "A fair experiment changes one controlled factor while sharing corpus, random seeds, parameter grid, result contract, and evaluation metrics."),
            chunk("RET-G-REPLAY", "RET-DOC-EVAL-GUIDE", DocumentType.EXPERIMENT_RECIPE, "Replay procedure",
                    "Reproducibility", "Replay reuses the validated spec and seed in an independent job, verifies artifact hashes, and compares numeric outputs within declared tolerance."),
            chunk("RET-G-CITATION", "RET-DOC-EVAL-GUIDE", DocumentType.EXPERIMENT_RECIPE, "Grounded report procedure",
                    "Citation", "Build conclusions only from validated artifact fields and attach citations that identify document, chunk, artifact, row, and field."),
            chunk("RET-G-SWEEP", "RET-DOC-RECIPES", DocumentType.EXPERIMENT_RECIPE, "Adaptive parameter sweep",
                    "Experiment guidance", "Start with a coarse bounded sweep, inspect grounded observations, then refine a smaller interval without crossing registered parameter limits."),
            chunk("RET-G-METADATA", "RET-DOC-EVAL-GUIDE", DocumentType.EXPERIMENT_RECIPE, "Metadata-filtered retrieval evaluation",
                    "Retrieval workflow", "Apply an explicit user documentType as a hard filter; treat router-inferred type only as a ranking boost and retain fallback documents."),
            chunk("RET-G-VALIDATE", "RET-DOC-EVAL-GUIDE", DocumentType.EXPERIMENT_RECIPE, "Validated execution checklist",
                    "Execution workflow", "Parse ExperimentSpec, run Java schema and bounds validation, execute a registered capability, verify artifacts, then finish or bounded replan."),

            chunk("RET-HN-THEORY", "RET-DOC-HARD-NEG", DocumentType.THEORY, "BPSK AWGN BER dashboard colors",
                    "Hard negative", "BPSK AWGN BER SNR Eb/N0 labels are merely UI color legend examples; this text contains no modulation or channel explanation."),
            chunk("RET-HN-PARAM", "RET-DOC-HARD-NEG", DocumentType.MATLAB_GUIDE, "SNR parameter variable naming policy",
                    "Hard negative", "The words snrStart snrEnd snrStep sampleCount are reserved names in a style guide; no valid value, bound, unit, or simulation choice is provided."),
            chunk("RET-HN-FAIL", "RET-DOC-HARD-NEG", DocumentType.FAILURE_CASE, "MATLAB error archive heading",
                    "Hard negative", "error exception NaN dimension mismatch undefined function are tags in an empty incident index, not a diagnosis or repair procedure."),
            chunk("RET-HN-GUIDE", "RET-DOC-HARD-NEG", DocumentType.EXPERIMENT_RECIPE, "Experiment workflow meeting agenda",
                    "Hard negative", "experiment workflow recipe run compare replay are meeting agenda keywords; there is no executable or validated procedure."));

    private final List<RetrievalEvaluationCase> cases = List.of(
            eval("T-01", "二进制相移键控的判决边界是什么", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BPSK"),
            eval("T-02", "How does additive white Gaussian noise change a received symbol?", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-AWGN"),
            eval("T-03", "BPSK 在 AWGN link 里的 coherent detection 原理", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BPSK", "RET-T-AWGN"),
            eval("T-04", "BER 的分母到底是所有 bit 还是错误 bit", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BER"),
            eval("T-05", "When should BLER be used instead of bit error probability?", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BLER", "RET-T-BER"),
            eval("T-06", "Eb/N0 与每采样点 SNR 为什么不能直接等同", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-EBN0"),
            eval("T-07", "How much information can a constrained noisy link carry reliably?", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-CAPACITY"),
            eval("T-08", "极化码为何把比特放进更可靠的合成子信道", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-POLAR"),
            eval("T-09", "Explain message passing between check and variable nodes", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-LDPC"),
            eval("T-10", "OFDM overlapping spectra 仍能由 FFT 分开的原因", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-OFDM"),
            eval("T-11", "保护间隔怎样缓解有记忆信道造成的码间串扰", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-MULTIPATH"),
            eval("T-12", "What tradeoff appears when moving from QPSK to higher-order QAM?", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-QAM"),
            eval("T-13", "为什么一次很低的 error estimate 不能当成精确真值", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-CONFIDENCE"),
            eval("T-14", "bit-level and frame-level error metrics 的语义差异", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BER", "RET-T-BLER"),
            eval("T-15", "antipodal signalling matched filter sign decision", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BPSK"),
            eval("T-16", "spectral efficiency 如何进入 EbN0 到 SNR 的换算", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-EBN0"),
            eval("T-17", "可靠通信速率的理论上限", QueryType.THEORY, DocumentType.THEORY, true, "RET-HN-THEORY", "RET-T-CAPACITY"),
            eval("T-18", "why a long enough guard copy turns convolution circular", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-MULTIPATH", "RET-T-OFDM"),
            eval("T-19", "通信仿真里 sampling uncertainty 怎么描述", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-CONFIDENCE"),
            eval("T-20", "BPSK AWGN BER SNR legend meaning", QueryType.THEORY, DocumentType.THEORY, false, "RET-HN-THEORY", "RET-T-BPSK", "RET-T-AWGN", "RET-T-BER"),

            eval("P-01", "SNR 参数范围和步长怎么配置", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SNR"),
            eval("P-02", "Which unit should ebN0Db use?", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-EBN0"),
            eval("P-03", "sampleCount 和 frameCount 分别控制什么", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SAMPLES"),
            eval("P-04", "Monte Carlo repetitions must be what kind of parameter?", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-MONTE"),
            eval("P-05", "固定 randomSeed 后如何让 MATLAB rng 可复现", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SEED"),
            eval("P-06", "polar codeLength=非二次幂可以吗", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-POLAR"),
            eval("P-07", "How should cpLength relate to channel delay and fftSize?", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-OFDM"),
            eval("P-08", "BER sweep 的 targetErrors 与 maxBits 停止条件", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-STOP"),
            eval("P-09", "replay 数值差异允许多大的 tolerance", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-TOL"),
            eval("P-10", "decodeFrame 和 noise_variance 参数名如何保持一致", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-CUSTOM"),
            eval("P-11", "What is a safe positive step for an inclusive SNR range?", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SNR"),
            eval("P-12", "Eb/N0, code rate 与 bitsPerSymbol 的 parameter conversion", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-EBN0"),
            eval("P-13", "想降低估计方差应该增加样本还是 frame 次数", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SAMPLES", "RET-P-MONTE"),
            eval("P-14", "seed 应该在 noise generation 前还是后设置", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SEED"),
            eval("P-15", "informationLength 和 codeLength 怎样决定码率", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-POLAR"),
            eval("P-16", "OFDM parameter overhead from cyclic prefix", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-OFDM"),
            eval("P-17", "rare BER point 用 error count 还是 bit budget 终止", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-STOP"),
            eval("P-18", "numericTolerance 不能掩盖什么问题", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-TOL"),
            eval("P-19", "snake_case 与 camelCase variable exact retrieval", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, true, "RET-HN-PARAM", "RET-P-CUSTOM"),
            eval("P-20", "snrStart snrEnd snrStep sampleCount valid values", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE, false, "RET-HN-PARAM", "RET-P-SNR", "RET-P-SAMPLES"),

            eval("F-01", "MATLAB 报错 Matrix dimensions must agree 怎么排查", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-DIM"),
            eval("F-02", "index exceeds array bounds after a loop", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-INDEX"),
            eval("F-03", "result.csv 出现 NaN/Inf failure", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-NAN"),
            eval("F-04", "Undefined comm.AWGNChannel exception on a clean MATLAB install", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-TOOLBOX"),
            eval("F-05", "decodeFrame 未定义函数错误但文件明明存在", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-UNDEFINED"),
            eval("F-06", "Why did a real-valued noise calculation become complex? debug", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-COMPLEX"),
            eval("F-07", "大规模 sweep out of memory 如何修复", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-MEMORY"),
            eval("F-08", "writetable cannot open output file error", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-FILE"),
            eval("F-09", "相同 random seed 重放仍不同的异常原因", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-RNG"),
            eval("F-10", "parfor sliced variable classification failed", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-PARFOR"),
            eval("F-11", "A*B 维度不一致，是否应该用 element-wise operator 修复", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-DIM"),
            eval("F-12", "one-based indexing 导致的最后一次循环异常", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-INDEX"),
            eval("F-13", "division by zero 后 validation failed", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-NAN"),
            eval("F-14", "qammod is undefined — path issue or missing toolbox?", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-TOOLBOX", "RET-F-UNDEFINED"),
            eval("F-15", "sqrt negative variance produces unexpected complex exception", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-COMPLEX"),
            eval("F-16", "不要保存所有 noise realization 来避免 memory failure", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-MEMORY"),
            eval("F-17", "artifact directory permission denied troubleshooting", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-FILE"),
            eval("F-18", "parallel workers random streams make replay mismatch", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-RNG"),
            eval("F-19", "并行循环存在 loop-carried mutable state 报错", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, true, "RET-HN-FAIL", "RET-F-PARFOR"),
            eval("F-20", "error exception NaN dimension mismatch repair procedure", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE, false, "RET-HN-FAIL", "RET-F-DIM", "RET-F-NAN"),

            eval("G-01", "如何执行 BPSK over AWGN 的 BER 实验流程", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-BPSK"),
            eval("G-02", "Polar code simulation experiment from validation to decoding", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-POLAR"),
            eval("G-03", "OFDM multipath 下比较 CP length 的实验方案", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-OFDM"),
            eval("G-04", "How to benchmark LDPC decoder iteration limits fairly?", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-LDPC"),
            eval("G-05", "baseline candidate 对比怎样控制变量", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-COMPARE"),
            eval("G-06", "如何复现一个 validated job 并检查数值漂移", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-REPLAY"),
            eval("G-07", "grounded report 的 citation 应关联哪些证据", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-CITATION"),
            eval("G-08", "coarse-to-fine bounded parameter sweep workflow", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-SWEEP"),
            eval("G-09", "explicit metadata filter 与 router hint 的检索流程", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-METADATA"),
            eval("G-10", "ExperimentSpec validation execute verify replan checklist", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-VALIDATE"),
            eval("G-11", "random bits 到 sign detector 再到 artifact citation 的 recipe", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-BPSK"),
            eval("G-12", "可靠子信道集合构造后如何完成 polar workflow", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-POLAR"),
            eval("G-13", "固定 payload 和 FFT，仅 sweep guard interval 的 experiment", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-OFDM"),
            eval("G-14", "same frames and seeds for iterative decoder comparison", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-LDPC", "RET-G-COMPARE"),
            eval("G-15", "Replay workflow must verify hashes and tolerance", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-REPLAY", "RET-G-CITATION"),
            eval("G-16", "观察结果后缩小参数区间但不能越界的方案", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-SWEEP", "RET-G-VALIDATE"),
            eval("G-17", "用户明确只要 experiment recipe 文档", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, true, "RET-HN-GUIDE", "RET-G-VALIDATE"),
            eval("G-18", "retrieval evaluation should keep fallback document types", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-METADATA"),
            eval("G-19", "从 artifact field 生成可核验结论的步骤", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-CITATION"),
            eval("G-20", "experiment workflow recipe run compare replay validated procedure", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE, false, "RET-HN-GUIDE", "RET-G-COMPARE", "RET-G-REPLAY"));

    public String name() { return NAME; }
    public List<KnowledgeChunk> chunks() { return chunks; }
    public List<RetrievalEvaluationCase> cases() { return cases; }

    private static RetrievalEvaluationCase eval(String id, String query, QueryType type,
                                                 DocumentType documentType, boolean explicitFilter,
                                                 String hardNegative, String... relevant) {
        return new RetrievalEvaluationCase(id, query, type, Set.of(relevant), Set.of(),
                Set.of(hardNegative), documentType, TYPE, 5, explicitFilter);
    }

    private static KnowledgeChunk chunk(String chunkId, String documentId, DocumentType documentType,
                                        String title, String section, String content) {
        return new KnowledgeChunk(chunkId, new KnowledgeDocumentMetadata(documentId, documentType,
                TYPE, title, "classpath://retrieval-eval/" + documentId, "1.0.0", Instant.EPOCH),
                content, section, Map.of("dataset", NAME));
    }
}
