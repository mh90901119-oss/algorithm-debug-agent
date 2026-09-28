package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** Predicate 确定性评估 ID。 */
public record ObservationEvaluationId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ObservationEvaluationId {
        value = InvestigationContractChecks.id(value, "evaluationId");
    }
}
