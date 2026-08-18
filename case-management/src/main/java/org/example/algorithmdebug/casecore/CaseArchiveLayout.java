package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceId;

import java.nio.file.Path;

/** 从受信任的项目 Case 根和不透明 ID 安全派生追加式归档路径。 */
public final class CaseArchiveLayout {

    private final Path casesRoot;
    private final Path caseRoot;

    private CaseArchiveLayout(Path casesRoot, Path caseRoot) {
        this.casesRoot = casesRoot;
        this.caseRoot = caseRoot;
    }

    /**
     * 创建一个只计算路径、不写文件的 Case 布局。
     *
     * @param casesRoot 已登记项目的 Case 根目录
     * @param caseId Case ID，必须能安全作为单一路径段
     * @return 规范化绝对布局
     */
    public static CaseArchiveLayout of(Path casesRoot, CaseId caseId) {
        if (casesRoot == null || caseId == null) {
            throw new IllegalArgumentException("casesRoot 和 caseId 不能为空");
        }
        Path root = casesRoot.toAbsolutePath().normalize();
        Path caseRoot = child(root, safeSegment(caseId.value(), "caseId"));
        return new CaseArchiveLayout(root, caseRoot);
    }

    /** @return 项目 Case 根目录 */
    public Path casesRoot() {
        return casesRoot;
    }

    /** @return 当前 Case 根目录 */
    public Path caseRoot() {
        return caseRoot;
    }

    /** @return Case 终态清单 */
    public Path caseDocument() {
        return child(caseRoot, "case.json");
    }

    /** @return Context 根目录 */
    public Path contextsRoot() {
        return child(caseRoot, "contexts");
    }

    /** @return 指定 Context 目录 */
    public Path contextRoot(ContextId contextId) {
        return child(contextsRoot(), safeSegment(contextId.value(), "contextId"));
    }

    /** @return 指定 Context 终态文档 */
    public Path contextDocument(ContextId contextId) {
        return child(contextRoot(contextId), "context.json");
    }

    /** @return 指定 Context 的一次性复现参考 */
    public Path contextReproduction(ContextId contextId) {
        return child(contextRoot(contextId), "reproduction.json");
    }

    /** @return Analysis 根目录 */
    public Path analysesRoot() {
        return child(caseRoot, "analyses");
    }

    /** @return 指定 Analysis 请求文档 */
    public Path analysisDocument(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "analysis-request.json");
    }

    /** @return 指定 Analysis 的一次性完成结果 */
    public Path analysisResult(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "analysis-result.json");
    }

    /** @return 指定 Analysis 目录 */
    public Path analysisRoot(AnalysisId analysisId) {
        return child(analysesRoot(), safeSegment(analysisId.value(), "analysisId"));
    }

    /** @return 指定 Analysis 的静态方法目录 */
    public Path analysisMethodCatalog(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "method-catalog.json");
    }

    /** @return 指定 Analysis 的采集计划根目录 */
    public Path analysisPlansRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "plans");
    }

    /** @return 指定 CodePath 计划文档 */
    public Path planDocument(AnalysisId analysisId, PlanId planId) {
        return child(analysisPlansRoot(analysisId), safeSegment(planId.value(), "planId") + ".json");
    }

    /** @return 当前 Case 的动态采集根目录 */
    public Path collectionsRoot() {
        return child(caseRoot, "collections");
    }

    /** @return 指定动态采集目录 */
    public Path collectionRoot(CollectionId collectionId) {
        return child(collectionsRoot(), safeSegment(collectionId.value(), "collectionId"));
    }

    /** @return Collector 启动前的不可变请求文档 */
    public Path collectionRequest(CollectionId collectionId) {
        return child(collectionRoot(collectionId), "collection-request.json");
    }

    /** @return 动态采集完成后面向模型的有界摘要 */
    public Path collectionSummary(CollectionId collectionId) {
        return child(collectionRoot(collectionId), "collection-summary.json");
    }

    /** @return 动态采集的 Baseline 一致性检查文档 */
    public Path collectionBaselineCheck(CollectionId collectionId) {
        return child(child(collectionRoot(collectionId), "validation"), "baseline-check.json");
    }

    /** @return 指定 Collection 的追加式派生根目录 */
    public Path collectionDerivedRoot(CollectionId collectionId) {
        return child(collectionRoot(collectionId), "derived");
    }

    /** @return 指定 Evidence 版本的 Collection 派生目录 */
    public Path collectionDerivedRoot(CollectionId collectionId, EvidenceId evidenceId) {
        return child(collectionDerivedRoot(collectionId),
                safeSegment(evidenceId.value(), "evidenceId"));
    }

    /** @return 一次派生的归一化清单 */
    public Path normalizationManifest(CollectionId collectionId, EvidenceId evidenceId) {
        return child(collectionDerivedRoot(collectionId, evidenceId),
                "normalization-manifest.json");
    }

    /** @return 一次 CodePath 派生的方法路径摘要 */
    public Path methodPathSummary(CollectionId collectionId, EvidenceId evidenceId) {
        return child(collectionDerivedRoot(collectionId, evidenceId),
                "method-path-summary.json");
    }

    /** @return 一次 JDWP 派生的快照摘要 */
    public Path jdwpSnapshotSummary(CollectionId collectionId, EvidenceId evidenceId) {
        return child(collectionDerivedRoot(collectionId, evidenceId),
                "jdwp-snapshot-summary.json");
    }

    /** @return 一次派生的 Collection 技术校验 */
    public Path collectionValidation(CollectionId collectionId, EvidenceId evidenceId) {
        return child(collectionDerivedRoot(collectionId, evidenceId),
                "collection-validation.json");
    }

    /** @return Run 根目录 */
    public Path runsRoot() {
        return child(caseRoot, "runs");
    }

    /** @return 指定 Run 目录 */
    public Path runRoot(RunId runId) {
        return child(runsRoot(), safeSegment(runId.value(), "runId"));
    }

    /** @return 指定 Run 启动请求文档 */
    public Path runRequest(RunId runId) {
        return child(runRoot(runId), "run-request.json");
    }

    /** @return 指定 Run 完成摘要 */
    public Path runOutcome(RunId runId) {
        return child(runRoot(runId), "run-outcome.json");
    }

    /** @return 指定 Run 的确定性结果指纹 */
    public Path runResultFingerprint(RunId runId) {
        return child(runRoot(runId), "run-result-fingerprint.json");
    }

    /** @return 指定 Run 原始产物目录 */
    public Path runRaw(RunId runId) {
        return child(runRoot(runId), "raw");
    }

    /** @return 预留 Evidence 根目录 */
    public Path evidenceRoot() {
        return child(caseRoot, "evidence");
    }

    /** @return 指定 Evidence 的追加式目录 */
    public Path evidenceRoot(EvidenceId evidenceId) {
        return child(evidenceRoot(), safeSegment(evidenceId.value(), "evidenceId"));
    }

    /** @return Evidence 构建请求 */
    public Path evidenceBuildRequest(EvidenceId evidenceId) {
        return child(evidenceRoot(evidenceId), "evidence-build-request.json");
    }

    /** @return 面向模型的 Evidence Bundle */
    public Path evidenceBundle(EvidenceId evidenceId) {
        return child(evidenceRoot(evidenceId), "evidence-bundle.json");
    }

    /** @return 请求维度的证据充分性评估 */
    public Path sufficiencyEvaluation(EvidenceId evidenceId) {
        return child(evidenceRoot(evidenceId), "sufficiency-evaluation.json");
    }

    private static Path child(Path parent, String segment) {
        Path result = parent.resolve(segment).normalize();
        if (!result.startsWith(parent) || result.equals(parent)) {
            throw new IllegalArgumentException("Case 归档路径越界: " + segment);
        }
        return result;
    }

    private static String safeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(field + " 必须是单一安全路径段");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 不允许包含控制字符");
        }
        return value;
    }
}
