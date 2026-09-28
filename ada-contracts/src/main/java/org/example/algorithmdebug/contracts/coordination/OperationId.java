package org.example.algorithmdebug.contracts.coordination;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.example.algorithmdebug.contracts.OpaqueIdentifier;

/** 标识一次可能产生副作用的幂等操作。 */
public record OperationId(String value) implements OpaqueIdentifier {
    /**
     * 创建操作 ID。
     *
     * @param value 不透明 ID
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public OperationId {
        value = CoordinationContractChecks.requireId(value, "operationId");
    }
}
