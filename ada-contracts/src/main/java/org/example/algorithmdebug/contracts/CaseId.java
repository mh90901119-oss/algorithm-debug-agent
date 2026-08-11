package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一个确定性算法问题 Case 的不透明 ID。 */
public record CaseId(String value) implements OpaqueIdentifier {

    /**
     * 创建 Case ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CaseId {
        value = ContractChecks.requireOpaqueId(value, "caseId");
    }
}

