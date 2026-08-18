package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/** Evidence Bundle 中的一条有界、可分类事实。 */
public record EvidenceFact(
        ClaimClassification classification,
        EvidenceDimension dimension,
        String code,
        String summary,
        List<ArtifactReference> artifacts,
        Optional<TraceProvenance> provenance) {

    /** 校验事实分类、维度和有界引用。 */
    public EvidenceFact {
        classification = ContractChecks.requireNonNull(classification, "classification");
        dimension = ContractChecks.requireNonNull(dimension, "dimension");
        code = ContractChecks.requireBoundedText(code, "code", 128, false);
        summary = ContractChecks.requireBoundedText(summary, "summary", 2_048, false);
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
        if (artifacts.size() > 16) {
            throw new IllegalArgumentException("单条事实最多引用 16 个 Artifact");
        }
        provenance = ContractChecks.requireNonNull(provenance, "provenance");
    }
}
