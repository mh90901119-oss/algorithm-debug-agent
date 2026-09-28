package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;

/**
 * 对当前 Analysis Method Catalog 与允许源码根的有界确定性查询。
 *
 * @param schemaVersion Schema 版本
 * @param queryId Query ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param methodCatalogArtifact Method Catalog Artifact
 * @param mode 查询模式
 * @param methodKey 起始方法键
 * @param targetMethodKey 可达路径目标方法键
 * @param sourceAnchor 源码窗口锚点
 * @param symbol 相对 Java 符号
 * @param budget 查询预算
 * @param requestedAt 请求时间
 */
public record SourceQueryRequest(
        String schemaVersion,
        SourceQueryId queryId,
        CaseId caseId,
        AnalysisId analysisId,
        ArtifactReference methodCatalogArtifact,
        SourceQueryMode mode,
        Optional<String> methodKey,
        Optional<String> targetMethodKey,
        Optional<SourceAnchor> sourceAnchor,
        Optional<String> symbol,
        SourceQueryBudget budget,
        Instant requestedAt) {

    /** 校验查询模式所需输入，禁止模糊的多模式参数组合。 */
    public SourceQueryRequest {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.SOURCE_QUERY_REQUEST, "SourceQueryRequest");
        queryId = InvestigationContractChecks.notNull(queryId, "queryId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        methodCatalogArtifact = InvestigationContractChecks.notNull(
                methodCatalogArtifact, "methodCatalogArtifact");
        if (!SourceQueryLimits.METHOD_CATALOG_ARTIFACT_TYPE.equals(
                methodCatalogArtifact.artifactType())) {
            throw new IllegalArgumentException(
                    "methodCatalogArtifact must have METHOD_CATALOG artifactType");
        }
        mode = InvestigationContractChecks.notNull(mode, "mode");
        methodKey = InvestigationContractChecks.optional(methodKey, "methodKey").map(
                value -> InvestigationContractChecks.text(
                        value, "methodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH));
        targetMethodKey = InvestigationContractChecks.optional(
                targetMethodKey, "targetMethodKey").map(
                        value -> InvestigationContractChecks.text(
                                value, "targetMethodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH));
        sourceAnchor = InvestigationContractChecks.optional(sourceAnchor, "sourceAnchor");
        symbol = InvestigationContractChecks.optional(symbol, "symbol").map(
                value -> InvestigationContractChecks.text(
                        value, "symbol", SourceQueryLimits.MAX_SYMBOL_LENGTH));
        if (symbol.isPresent() && !symbol.orElseThrow().matches(SourceQueryLimits.SYMBOL_PATTERN)) {
            throw new IllegalArgumentException(
                    "symbol must be a relative Java symbol without glob or regex syntax");
        }
        budget = InvestigationContractChecks.notNull(budget, "budget");
        requestedAt = InvestigationContractChecks.notNull(requestedAt, "requestedAt");
        requireModeInputs(mode, methodKey, targetMethodKey, sourceAnchor, symbol);
    }

    private static void requireModeInputs(
            SourceQueryMode mode,
            Optional<String> methodKey,
            Optional<String> targetMethodKey,
            Optional<SourceAnchor> sourceAnchor,
            Optional<String> symbol) {
        boolean valid = switch (mode) {
            case METHOD, CALLERS, CALLEES -> methodKey.isPresent()
                    && targetMethodKey.isEmpty() && sourceAnchor.isEmpty() && symbol.isEmpty();
            case REACHABLE_PATH -> methodKey.isPresent() && targetMethodKey.isPresent()
                    && sourceAnchor.isEmpty() && symbol.isEmpty();
            case SOURCE_WINDOW -> methodKey.isEmpty() && targetMethodKey.isEmpty()
                    && sourceAnchor.isPresent() && symbol.isEmpty();
            case SEARCH_SYMBOL -> methodKey.isEmpty() && targetMethodKey.isEmpty()
                    && sourceAnchor.isEmpty() && symbol.isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException("Query inputs do not match mode " + mode);
        }
    }
}
