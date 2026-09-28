package org.example.algorithmdebug.contracts.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 一个可由确定性 evaluator 判定的证据义务。
 *
 * @param schemaVersion Schema 版本
 * @param obligationId 义务 ID
 * @param identity 分析身份
 * @param kind 义务类型
 * @param status 当前状态
 * @param description 有界说明
 * @param referenceIds 判定所依据的 Artifact、Evidence 或调查对象 ID
 * @param reasonCodes 未满足时的稳定原因码
 */
public record EvidenceObligation(
        String schemaVersion,
        String obligationId,
        AnalysisIdentity identity,
        EvidenceObligationKind kind,
        ObligationStatus status,
        String description,
        List<String> referenceIds,
        List<CoordinationErrorCode> reasonCodes) {

    /** 校验身份、引用、状态与原因的一致性。 */
    public EvidenceObligation {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.EVIDENCE_OBLIGATION, "EvidenceObligation");
        obligationId = CoordinationContractChecks.requireId(obligationId, "obligationId");
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        kind = CoordinationContractChecks.requireNonNull(kind, "kind");
        status = CoordinationContractChecks.requireNonNull(status, "status");
        description = CoordinationContractChecks.requireText(
                description, "description", CoordinationLimits.MAX_DESCRIPTION_LENGTH, false);
        referenceIds = CoordinationContractChecks.immutableUniqueIds(referenceIds, "referenceIds");
        reasonCodes = CoordinationContractChecks.immutableUniqueList(
                reasonCodes, "reasonCodes", CoordinationLimits.MAX_CONTROL_ITEMS);
        if (status == ObligationStatus.SATISFIED && !reasonCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A satisfied obligation cannot contain failure reasons");
        }
        if (status != ObligationStatus.SATISFIED && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unsatisfied obligation requires at least one reason");
        }
    }
}
