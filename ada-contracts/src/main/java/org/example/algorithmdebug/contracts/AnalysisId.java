package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识基于某次运行开展的一轮独立分析的不透明 ID。 */
public record AnalysisId(String value) implements OpaqueIdentifier {

    /**
     * 创建分析 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public AnalysisId {
        value = ContractChecks.requireOpaqueId(value, "analysisId");
    }
}

