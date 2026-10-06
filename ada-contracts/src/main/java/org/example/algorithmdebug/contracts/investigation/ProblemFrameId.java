package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 唯一 Problem Frame ID。 */
public record ProblemFrameId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ProblemFrameId {
        value = InvestigationContractChecks.id(value, "problemFrameId");
    }
}
