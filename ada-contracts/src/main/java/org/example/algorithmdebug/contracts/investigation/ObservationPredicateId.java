package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 冻结 Observation Predicate ID。 */
public record ObservationPredicateId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ObservationPredicateId {
        value = InvestigationContractChecks.id(value, "predicateId");
    }
}
