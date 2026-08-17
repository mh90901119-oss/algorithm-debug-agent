package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识目标代码工作区某个不可变快照上下文的不透明 ID。 */
public record ContextId(String value) implements OpaqueIdentifier {

    /** @param value 不透明字符串值 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ContextId {
        value = ContractChecks.requireOpaqueId(value, "contextId");
    }
}
