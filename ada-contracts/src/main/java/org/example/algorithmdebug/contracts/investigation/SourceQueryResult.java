package org.example.algorithmdebug.contracts.investigation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;

/**
 * 有界 Source Query 结果；静态关系不得伪装为运行时事实。
 *
 * @param schemaVersion Schema 版本
 * @param queryId Query ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param mode 查询模式
 * @param completeness 结果完整度
 * @param methodCatalogArtifact Catalog provenance
 * @param methods 方法结果
 * @param edges 调用边结果
 * @param paths 可达路径结果
 * @param sourceWindows 源码窗口结果
 * @param limitations 限制说明
 * @param truncated 是否受预算截断
 * @param completedAt 完成时间
 */
public record SourceQueryResult(
        String schemaVersion,
        SourceQueryId queryId,
        CaseId caseId,
        AnalysisId analysisId,
        SourceQueryMode mode,
        SourceQueryCompleteness completeness,
        ArtifactReference methodCatalogArtifact,
        List<MethodCatalogEntry> methods,
        List<MethodCallEdge> edges,
        List<List<String>> paths,
        List<SourceWindow> sourceWindows,
        List<String> limitations,
        boolean truncated,
        Instant completedAt) {

    /** 校验归属、查询产物、稳定路径和完整度。 */
    public SourceQueryResult {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.SOURCE_QUERY_RESULT, "SourceQueryResult");
        queryId = InvestigationContractChecks.notNull(queryId, "queryId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        mode = InvestigationContractChecks.notNull(mode, "mode");
        completeness = InvestigationContractChecks.notNull(completeness, "completeness");
        methodCatalogArtifact = InvestigationContractChecks.notNull(
                methodCatalogArtifact, "methodCatalogArtifact");
        if (!SourceQueryLimits.METHOD_CATALOG_ARTIFACT_TYPE.equals(
                methodCatalogArtifact.artifactType())) {
            throw new IllegalArgumentException(
                    "methodCatalogArtifact must have METHOD_CATALOG artifactType");
        }
        methods = InvestigationContractChecks.unique(
                methods, "methods", SourceQueryLimits.MAX_METHODS);
        edges = InvestigationContractChecks.unique(edges, "edges", SourceQueryLimits.MAX_EDGES);
        paths = InvestigationContractChecks.unique(paths, "paths", SourceQueryLimits.MAX_PATHS)
                .stream()
                .map(path -> InvestigationContractChecks.uniqueTexts(
                        path, "path", SourceQueryLimits.MAX_DEPTH + 1))
                .toList();
        sourceWindows = InvestigationContractChecks.unique(
                sourceWindows, "sourceWindows", SourceQueryLimits.MAX_SOURCE_WINDOWS);
        limitations = InvestigationContractChecks.uniqueTexts(
                limitations, "limitations", InvestigationLimits.MAX_LIMITATIONS);
        completedAt = InvestigationContractChecks.notNull(completedAt, "completedAt");
        if (truncated && completeness == SourceQueryCompleteness.COMPLETE) {
            throw new IllegalArgumentException("A truncated result cannot be COMPLETE");
        }
    }

    /** 带源码哈希的有界窗口。 */
    public record SourceWindow(
            SourceAnchor anchor,
            int fromLine,
            int toLine,
            String sourceSha256,
            String text) {
        /** 校验窗口范围、哈希和文本预算。 */
        public SourceWindow {
            anchor = InvestigationContractChecks.notNull(anchor, "anchor");
            if (fromLine < 1 || toLine < fromLine
                    || toLine - fromLine + 1 > SourceQueryLimits.MAX_SOURCE_LINES) {
                throw new IllegalArgumentException("SourceWindow line range is invalid");
            }
            sourceSha256 = InvestigationContractChecks.sha256(sourceSha256, "sourceSha256");
            text = InvestigationContractChecks.sourceText(
                    text, "text", SourceQueryLimits.MAX_RESPONSE_BYTES);
            if (text.getBytes(StandardCharsets.UTF_8).length > SourceQueryLimits.MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("text exceeds the UTF-8 byte budget");
            }
        }
    }
}
