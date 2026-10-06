package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 结论候选引用的因果链 ID。 */
public record CausalChainId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CausalChainId {
        value = InvestigationContractChecks.id(value, "causalChainId");
    }
}
