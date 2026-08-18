package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 标识同一 Analysis 下可重复执行的一次动态采集。 */
public record CollectionId(String value) implements OpaqueIdentifier {
    /** 创建不透明采集 ID。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CollectionId {
        value = ContractChecks.requireOpaqueId(value, "collectionId");
    }
}
