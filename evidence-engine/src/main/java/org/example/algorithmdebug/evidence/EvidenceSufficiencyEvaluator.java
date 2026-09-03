package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceBundle;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SufficiencyEvaluation;
import org.example.algorithmdebug.contracts.SufficiencyStatus;

/** 判断调用方声明的证据维度是否已被当前 Analysis 的确定性事实覆盖。 */
public final class EvidenceSufficiencyEvaluator {

    /** 评估覆盖、缺口和阻断矛盾；不判断业务根因是否正确。 */
    public SufficiencyEvaluation evaluate(
            EvidenceBuildRequest request, EvidenceBundle bundle) {
        if (request == null || bundle == null) {
            throw new IllegalArgumentException("request and bundle must not be null");
        }
        validateIdentity(request, bundle);
        Set<EvidenceDimension> covered = Set.copyOf(bundle.coveredDimensions());
        HashSet<EvidenceDimension> missing = new HashSet<>(request.requiredDimensions());
        missing.removeAll(covered);
        List<String> contradictions = bundle.facts().stream()
                .filter(fact -> "COLLECTION_CONTRADICTED".equals(fact.code()))
                .map(fact -> fact.summary())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        ArrayList::new));
        SufficiencyStatus status;
        if (!contradictions.isEmpty()) {
            status = SufficiencyStatus.CONTRADICTED;
        } else if (!missing.isEmpty()) {
            status = SufficiencyStatus.INSUFFICIENT;
        } else {
            status = SufficiencyStatus.SUFFICIENT;
        }
        return new SufficiencyEvaluation(
                SchemaVersions.SUFFICIENCY_EVALUATION, request.evidenceId(),
                request.caseId(), request.analysisId(), status,
                request.requiredDimensions(), covered, Set.copyOf(missing),
                List.copyOf(contradictions), bundle.createdAt());
    }

    private static void validateIdentity(
            EvidenceBuildRequest request, EvidenceBundle bundle) {
        if (!request.evidenceId().equals(bundle.evidenceId())
                || !request.caseId().equals(bundle.caseId())
                || !request.analysisId().equals(bundle.analysisId())) {
            throw new IllegalArgumentException("The Evidence Bundle identity does not match the build request");
        }
    }
}
