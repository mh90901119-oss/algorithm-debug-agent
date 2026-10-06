package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 可追溯 Source Query ID。 */
public record SourceQueryId(String value) implements OpaqueIdentifier {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SourceQueryId {
        value = InvestigationContractChecks.id(value, "sourceQueryId");
    }
}
