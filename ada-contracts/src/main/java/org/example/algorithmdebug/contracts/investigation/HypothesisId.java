package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 调查假设 ID。 */
public record HypothesisId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public HypothesisId {
        value = InvestigationContractChecks.id(value, "hypothesisId");
    }
}
