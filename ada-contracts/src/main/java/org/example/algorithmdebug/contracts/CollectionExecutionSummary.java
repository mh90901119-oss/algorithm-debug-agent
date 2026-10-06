package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * 面向 CLI/模型的有界动态采集摘要，不泄露本机绝对路径。
 *
 * @param schemaVersion 摘要契约版本
 * @param caseId Case 身份
 * @param analysisId Analysis 身份
 * @param runId 本次采集运行身份
 * @param planId 采集 Plan 身份
 * @param collectionId Collection 身份
 * @param completion Collector 完成状态
 * @param baselineOutcome 基线比较结果
 * @param eligibility 正交证据资格
 * @param artifactRelativePaths 已注册产物相对路径
 * @param artifactIds 已注册产物 ID
 * @param sourceCoverage 当前来源覆盖程度
 * @param reasonCodes 采集限制原因码
 * @param primaryArtifactId 推荐优先查询的产物 ID
 * @param recommendedTool 推荐的下一读取工具
 * @param supportedModes 主产物支持的查询模式
 * @param nextAction 有界下一步提示
 */
public record CollectionExecutionSummary(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String completion,
        ComparisonOutcome baselineOutcome,
        EvidenceEligibility eligibility,
        List<String> artifactRelativePaths,
        List<String> artifactIds,
        EvidenceSourceCoverage sourceCoverage,
        List<String> reasonCodes,
        Optional<String> primaryArtifactId,
        String recommendedTool,
        List<EvidenceQueryMode> supportedModes,
        String nextAction) {

    private static final int MAX_ARTIFACTS = 32;
    private static final int MAX_REASON_CODES = 32;
    private static final int MAX_COMPLETION_LENGTH = 64;
    private static final int MAX_ARTIFACT_PATH_LENGTH = 1_024;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final int MAX_RECOMMENDED_TOOL_LENGTH = 64;
    private static final int MAX_NEXT_ACTION_LENGTH = 256;
    private static final String DEFAULT_NEXT_ACTION =
            "Inspect the returned collection facts before choosing another action";

    /** 校验身份、状态、资格和有界相对产物路径。 */
    public CollectionExecutionSummary {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.COLLECTION_EXECUTION_SUMMARY.equals(schemaVersion)
                && !SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported CollectionExecutionSummary schemaVersion: " + schemaVersion);
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        completion = ContractChecks.requireBoundedText(
                completion, "completion", MAX_COMPLETION_LENGTH, false);
        baselineOutcome = ContractChecks.requireNonNull(baselineOutcome, "baselineOutcome");
        eligibility = ContractChecks.requireNonNull(eligibility, "eligibility");
        if (SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY.equals(schemaVersion)
                && !eligibility.reasonCodes().contains(
                        EvidenceEligibilityReason.LEGACY_UNKNOWN.name())) {
            throw new IllegalArgumentException("Legacy summary must have unknown eligibility");
        }
        if (SchemaVersions.COLLECTION_EXECUTION_SUMMARY.equals(schemaVersion)
                && eligibility.reasonCodes().contains(
                        EvidenceEligibilityReason.LEGACY_UNKNOWN.name())) {
            throw new IllegalArgumentException("Current summary must have evaluated eligibility");
        }
        artifactRelativePaths = ContractChecks.immutableBoundedStrings(
                artifactRelativePaths, "artifactRelativePaths", MAX_ARTIFACT_PATH_LENGTH);
        if (artifactRelativePaths.size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException(
                    "artifactRelativePaths must not exceed " + MAX_ARTIFACTS + " entries");
        }
        artifactRelativePaths.forEach(path ->
                ContractChecks.requirePortableRelativePath(path, "artifactRelativePath"));
        artifactIds = artifactIds == null ? List.of()
                : ContractChecks.immutableNonBlankStrings(artifactIds, "artifactIds");
        if (artifactIds.size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException(
                    "artifactIds must not exceed " + MAX_ARTIFACTS + " entries");
        }
        artifactIds.forEach(id -> ContractChecks.requireOpaqueId(id, "artifactId"));
        sourceCoverage = sourceCoverage == null ? EvidenceSourceCoverage.UNKNOWN : sourceCoverage;
        reasonCodes = ContractChecks.immutableBoundedStrings(
                reasonCodes == null ? List.of() : reasonCodes,
                "reasonCodes", MAX_REASON_CODE_LENGTH);
        if (reasonCodes.size() > MAX_REASON_CODES) {
            throw new IllegalArgumentException("reasonCodes exceeds the limit");
        }
        primaryArtifactId = primaryArtifactId == null ? Optional.empty() : primaryArtifactId;
        primaryArtifactId = primaryArtifactId.map(
                id -> ContractChecks.requireOpaqueId(id, "primaryArtifactId"));
        if (primaryArtifactId.isPresent() && !artifactIds.contains(primaryArtifactId.orElseThrow())) {
            throw new IllegalArgumentException("primaryArtifactId must be present in artifactIds");
        }
        recommendedTool = ContractChecks.requireBoundedText(
                recommendedTool == null ? "" : recommendedTool,
                "recommendedTool", MAX_RECOMMENDED_TOOL_LENGTH, true);
        supportedModes = ContractChecks.immutableList(
                supportedModes == null ? List.of() : supportedModes, "supportedModes");
        nextAction = ContractChecks.requireBoundedText(
                nextAction == null ? DEFAULT_NEXT_ACTION : nextAction,
                "nextAction", MAX_NEXT_ACTION_LENGTH, false);
    }

    /**
     * 同时读取 v3 摘要和缺少资格字段的 v2 摘要。
     *
     * <p>历史 `evidenceUsable` 值不会被解释为确认资格，避免把旧的混合语义自动升级为事实。</p>
     *
     * @param schemaVersion 摘要版本；历史 v2 可以缺失
     * @param caseId Case 身份
     * @param analysisId Analysis 身份
     * @param runId 采集运行身份
     * @param planId Plan 身份
     * @param collectionId Collection 身份
     * @param completion Collector 完成状态
     * @param baselineOutcome 基线比较结果
     * @param eligibility v3 正交资格；历史 v2 可以缺失
     * @param ignoredLegacyEvidenceUsable 历史混合布尔，仅消费字段而不继承其语义
     * @param artifactRelativePaths 产物相对路径
     * @param artifactIds 产物 ID
     * @param sourceCoverage 来源覆盖程度
     * @param reasonCodes 采集限制原因码
     * @param primaryArtifactId 主产物 ID
     * @param recommendedTool 推荐工具
     * @param supportedModes 支持的查询模式
     * @param nextAction 下一步提示
     * @return v3 摘要或资格为 LEGACY_UNKNOWN 的只读历史摘要
     */
    @JsonCreator
    public static CollectionExecutionSummary fromJson(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("caseId") CaseId caseId,
            @JsonProperty("analysisId") AnalysisId analysisId,
            @JsonProperty("runId") RunId runId,
            @JsonProperty("planId") PlanId planId,
            @JsonProperty("collectionId") CollectionId collectionId,
            @JsonProperty("completion") String completion,
            @JsonProperty("baselineOutcome") ComparisonOutcome baselineOutcome,
            @JsonProperty("eligibility") EvidenceEligibility eligibility,
            @JsonProperty("evidenceUsable") Boolean ignoredLegacyEvidenceUsable,
            @JsonProperty("artifactRelativePaths") List<String> artifactRelativePaths,
            @JsonProperty("artifactIds") List<String> artifactIds,
            @JsonProperty("sourceCoverage") EvidenceSourceCoverage sourceCoverage,
            @JsonProperty("reasonCodes") List<String> reasonCodes,
            @JsonProperty("primaryArtifactId") Optional<String> primaryArtifactId,
            @JsonProperty("recommendedTool") String recommendedTool,
            @JsonProperty("supportedModes") List<EvidenceQueryMode> supportedModes,
            @JsonProperty("nextAction") String nextAction) {
        boolean legacy = eligibility == null;
        if (legacy && schemaVersion != null
                && !SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Only a legacy summary may omit EvidenceEligibility");
        }
        String effectiveVersion = legacy
                ? SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY : schemaVersion;
        if (!legacy && !SchemaVersions.COLLECTION_EXECUTION_SUMMARY.equals(effectiveVersion)) {
            throw new IllegalArgumentException("Current summary must declare schemaVersion 3.0");
        }
        return new CollectionExecutionSummary(
                effectiveVersion, caseId, analysisId, runId, planId, collectionId,
                completion, baselineOutcome,
                legacy ? EvidenceEligibility.legacyUnknown() : eligibility,
                artifactRelativePaths, artifactIds, sourceCoverage, reasonCodes,
                primaryArtifactId, recommendedTool, supportedModes, nextAction);
    }

    /**
     * 仅供历史调用方迁移读取；新生产代码必须按需读取 {@link #eligibility()} 的正交字段。
     *
     * @return 当前摘要是否可用于确认性结论
     */
    @Deprecated(forRemoval = false)
    @JsonIgnore
    public boolean evidenceUsable() {
        return eligibility.confirmationEligible();
    }
}
