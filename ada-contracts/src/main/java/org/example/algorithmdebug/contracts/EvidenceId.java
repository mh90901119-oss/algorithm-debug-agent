package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一条可追溯证据的不透明 ID。 */
public record EvidenceId(String value) implements OpaqueIdentifier {

    /**
     * 创建证据 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public EvidenceId {
        value = ContractChecks.requireOpaqueId(value, "evidenceId");
    }
}

