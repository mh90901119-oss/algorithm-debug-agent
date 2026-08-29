package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceId;

import java.util.UUID;
import java.util.function.Supplier;

/** 生成不可解析的不透明 Case/Context/Analysis/Run ID；测试可注入固定 token。 */
public final class OpaqueIdGenerator {

    private final Supplier<String> tokenSupplier;

    /** 使用随机 UUID token。 */
    public OpaqueIdGenerator() {
        this(() -> UUID.randomUUID().toString());
    }

    /** @param tokenSupplier 每次返回一个安全单路径段 token */
    public OpaqueIdGenerator(Supplier<String> tokenSupplier) {
        if (tokenSupplier == null) {
            throw new IllegalArgumentException("tokenSupplier must not be null");
        }
        this.tokenSupplier = tokenSupplier;
    }

    /** @return 新 Case ID */
    public CaseId newCaseId() {
        return new CaseId(value("case"));
    }

    /** @return 新 Context ID */
    public ContextId newContextId() {
        return new ContextId(value("context"));
    }

    /** @return 新 Analysis ID */
    public AnalysisId newAnalysisId() {
        return new AnalysisId(value("analysis"));
    }

    /** @return 新 Run ID */
    public RunId newRunId() {
        return new RunId(value("run"));
    }

    /** @return 新 Collection ID */
    public CollectionId newCollectionId() {
        return new CollectionId(value("collection"));
    }

    /** @return 新 Evidence ID */
    public EvidenceId newEvidenceId() {
        return new EvidenceId(value("evidence"));
    }

    private String value(String prefix) {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank() || token.length() > 100
                || token.contains("/") || token.contains("\\") || token.contains(":")) {
            throw new IllegalStateException("ID token is unsafe");
        }
        return prefix + "-" + token;
    }
}
