package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识一个被分析项目的不透明 ID。 */
public record ProjectId(String value) implements OpaqueIdentifier {

    /**
     * 创建项目 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ProjectId {
        value = ContractChecks.requireOpaqueId(value, "projectId");
    }
}

