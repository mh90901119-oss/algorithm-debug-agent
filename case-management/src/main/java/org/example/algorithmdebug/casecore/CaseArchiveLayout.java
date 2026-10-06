package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;

import java.nio.file.Path;
import java.time.LocalDate;

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
            throw new IllegalArgumentException("casesRoot and caseId must not be null");
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

    /** @return 当前 Case 的 Java Agent 执行日志目录。 */
    public Path logsRoot() {
        return child(caseRoot, "logs");
    }

    /** @return 指定日期的 Java Agent 执行日志文件。 */
    public Path executionLog(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return child(logsRoot(), "agent-" + date + ".log");
    }

    /** @return Case 终态清单 */
    public Path caseDocument() {
        return child(caseRoot, "case.json");
    }

    /** @return 当前 Case 的 Artifact 注册根目录 */
    public Path artifactsRoot() {
        return child(caseRoot, "artifacts");
    }

    /** @return 指定 Artifact ID 的不可变注册文档 */
    public Path artifactRegistration(String artifactId) {
        return child(artifactsRoot(), safeSegment(artifactId, "artifactId") + ".json");
    }

    /** @return Analysis 根目录 */
    public Path analysesRoot() {
        return child(caseRoot, "analyses");
    }

    /** @return 指定 Analysis 请求文档 */
    public Path analysisDocument(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "analysis-request.json");
    }

    /** @return 指定 Analysis 目录 */
    public Path analysisRoot(AnalysisId analysisId) {
        return child(analysesRoot(), safeSegment(analysisId.value(), "analysisId"));
    }

    /** @return 指定 Analysis 的幂等操作日志根目录。 */
    public Path operationJournalRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "operations");
    }

    /** @return 指定操作的追加式生命周期目录。 */
    public Path operationRoot(AnalysisId analysisId, OperationId operationId) {
        return child(operationJournalRoot(analysisId),
                safeSegment(operationId.value(), "operationId"));
    }

    /** @return 操作启动文档。 */
    public Path operationStarted(AnalysisId analysisId, OperationId operationId) {
        return child(operationRoot(analysisId, operationId), "started.json");
    }

    /** @return 操作成功终态文档。 */
    public Path operationCompleted(AnalysisId analysisId, OperationId operationId) {
        return child(operationRoot(analysisId, operationId), "completed.json");
    }

    /** @return 操作失败终态文档。 */
    public Path operationFailed(AnalysisId analysisId, OperationId operationId) {
        return child(operationRoot(analysisId, operationId), "failed.json");
    }

    /** @return 操作结果不确定终态文档。 */
    public Path operationUncertain(AnalysisId analysisId, OperationId operationId) {
        return child(operationRoot(analysisId, operationId), "uncertain.json");
    }

    /** @return 指定 Analysis 的协调决策目录。 */
    public Path coordinationRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "coordination");
    }

    /** @return 指定协调决策的不可变文档。 */
    public Path coordinationDecision(AnalysisId analysisId, String decisionId) {
        return child(coordinationRoot(analysisId),
                safeSegment(decisionId, "decisionId") + ".json");
    }

    /** @return 指定 Analysis 的调查事件目录。 */
    public Path investigationEventsRoot(AnalysisId analysisId) {
        return child(child(analysisRoot(analysisId), "investigation"), "events");
    }

    /** @return 由严格序号和事件 ID 共同命名的调查事件文档。 */
    public Path investigationEvent(AnalysisId analysisId, long sequence, String eventId) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        return child(investigationEventsRoot(analysisId),
                sequence + "-" + safeSegment(eventId, "eventId") + ".json");
    }

    /** @return 指定 Analysis 的 Source Query 根目录。 */
    public Path sourceQueriesRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "source-queries");
    }

    /** @return 指定 Source Query 的追加式目录。 */
    public Path sourceQueryRoot(AnalysisId analysisId, SourceQueryId queryId) {
        return child(sourceQueriesRoot(analysisId), safeSegment(queryId.value(), "queryId"));
    }

    /** @return Source Query 请求文档。 */
    public Path sourceQueryRequest(AnalysisId analysisId, SourceQueryId queryId) {
        return child(sourceQueryRoot(analysisId, queryId), "request.json");
    }

    /** @return Source Query 结果文档。 */
    public Path sourceQueryResult(AnalysisId analysisId, SourceQueryId queryId) {
        return child(sourceQueryRoot(analysisId, queryId), "result.json");
    }

    /** @return 指定结论候选的追加式目录。 */
    public Path conclusionsRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "conclusions");
    }

    /** @return 指定结论候选的追加式目录。 */
    public Path conclusionRoot(AnalysisId analysisId, String conclusionId) {
        return child(conclusionsRoot(analysisId),
                safeSegment(conclusionId, "conclusionId"));
    }

    /** @return 结论候选文档。 */
    public Path conclusionCandidate(AnalysisId analysisId, String conclusionId) {
        return child(conclusionRoot(analysisId, conclusionId), "candidate.json");
    }

    /** @return 已接受结论决策文档。 */
    public Path conclusionAccepted(AnalysisId analysisId, String conclusionId) {
        return child(conclusionRoot(analysisId, conclusionId), "accepted.json");
    }

    /** @return 已拒绝结论决策文档。 */
    public Path conclusionRejected(AnalysisId analysisId, String conclusionId) {
        return child(conclusionRoot(analysisId, conclusionId), "rejected.json");
    }

    /** @return 当前 Case 唯一算法输入快照目录。 */
    public Path caseInputRoot() {
        return child(caseRoot, "input");
    }

    /** @return 使用源文件 basename 保存的 Case 唯一算法输入。 */
    public Path caseInputArtifact(String fileName) {
        return child(caseInputRoot(), safeSegment(fileName, "fileName"));
    }

    /** @return 指定 Analysis 的算法输入归档目录。 */
    public Path analysisInputRoot(AnalysisId analysisId) {
        return child(analysisRoot(analysisId), "input");
    }

    /** @return 指定 Analysis 的输入定位与一致性控制文档。 */
    public Path analysisInputCapture(AnalysisId analysisId) {
        return child(analysisInputRoot(analysisId), "input-analysis.json");
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
            throw new IllegalArgumentException("Case archive path escapes its root: " + segment);
        }
        return result;
    }

    private static String safeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(field + " must be a single safe path segment");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }
}
