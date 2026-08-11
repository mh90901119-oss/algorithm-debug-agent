package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一次确定性目标算法运行的不透明 ID。 */
public record RunId(String value) implements OpaqueIdentifier {

    /**
     * 创建运行 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public RunId {
        value = ContractChecks.requireOpaqueId(value, "runId");
    }
}

