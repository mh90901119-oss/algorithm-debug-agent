package org.example.algorithmdebug.core;

import java.util.List;
import org.example.algorithmdebug.contracts.ArtifactReference;

/** Core 用例向 CLI 返回的有界摘要及多个标准 Artifact 引用。 */
public record MultiArtifactBackedResult<T>(T summary, List<ArtifactReference> artifacts) {
    /** 校验摘要存在、引用不可变且数量有界。 */
    public MultiArtifactBackedResult {
        if (summary == null || artifacts == null || artifacts.isEmpty() || artifacts.size() > 32) {
            throw new IllegalArgumentException("summary and 1 to 32 artifacts are required");
        }
        artifacts = List.copyOf(artifacts);
    }
}
