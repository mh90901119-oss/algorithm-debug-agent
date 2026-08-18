package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/** Validator 输出的一条稳定、可追溯技术结论。 */
public record ValidationFinding(
        String code,
        EvidenceValidationStatus status,
        String detail,
        List<ArtifactReference> artifacts,
        Optional<TraceProvenance> provenance) {

    /** 校验代码、状态、详情和有界引用。 */
    public ValidationFinding {
        code = ContractChecks.requireBoundedText(code, "code", 128, false);
        status = ContractChecks.requireNonNull(status, "status");
        detail = ContractChecks.requireBoundedText(detail, "detail", 2_048, false);
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
        if (artifacts.size() > 16) throw new IllegalArgumentException("artifacts 不能超过 16 项");
        provenance = ContractChecks.requireNonNull(provenance, "provenance");
    }
}
