package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一次多轮对话轮次的不透明 ID。 */
public record TurnId(String value) implements OpaqueIdentifier {

    /**
     * 创建对话轮次 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TurnId {
        value = ContractChecks.requireOpaqueId(value, "turnId");
    }
}

