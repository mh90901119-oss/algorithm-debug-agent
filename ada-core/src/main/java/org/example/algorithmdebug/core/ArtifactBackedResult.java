package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ArtifactReference;

/** Core 用例向 CLI 返回的有界摘要及其不可变完整产物引用。 */
public record ArtifactBackedResult<T>(T summary, ArtifactReference artifact) {

    /** 校验摘要和引用均存在。 */
    public ArtifactBackedResult {
        if (summary == null || artifact == null) {
            throw new IllegalArgumentException("summary 和 artifact 不能为空");
        }
    }
}
