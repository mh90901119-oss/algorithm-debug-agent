package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 证据缺口 ID。 */
public record EvidenceGapId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public EvidenceGapId {
        value = InvestigationContractChecks.id(value, "gapId");
    }
}
