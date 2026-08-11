package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 不透明强类型 ID 的共同契约。
 *
 * <p>调用方只能把 ID 作为完整值传递和比较，不应解析其中可能出现的分隔符。</p>
 */
public interface OpaqueIdentifier {

    /**
     * 返回不透明字符串值，并将其作为 JSON 表现形式。
     *
     * @return ID 原始值
     */
    @JsonValue
    String value();
}

