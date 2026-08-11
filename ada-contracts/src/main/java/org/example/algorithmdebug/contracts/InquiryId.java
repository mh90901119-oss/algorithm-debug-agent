package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识用户一次问题定位主题的不透明 ID。 */
public record InquiryId(String value) implements OpaqueIdentifier {

    /**
     * 创建问题 ID。
     *
     * @param value 不透明字符串值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public InquiryId {
        value = ContractChecks.requireOpaqueId(value, "inquiryId");
    }
}

