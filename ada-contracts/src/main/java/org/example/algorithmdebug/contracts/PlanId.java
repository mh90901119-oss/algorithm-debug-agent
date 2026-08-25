package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一份不可变调试或采集计划的不透明 ID。 */
public record PlanId(String value) implements OpaqueIdentifier {

    /**
     * 创建计划 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PlanId {
        value = ContractChecks.requireOpaqueId(value, "planId");
    }
}
