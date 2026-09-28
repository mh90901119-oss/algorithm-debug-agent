package org.example.algorithmdebug.core;

import java.util.List;
import org.example.algorithmdebug.contracts.ArtifactReference;

/**
 * 一次 Collection 后处理的有界结果。
 *
 * <p>{@code artifactReadable} 只表达派生产物结构和完整性，不表达失败基线或最终确认资格。</p>
 */
record CollectionPostProcessingResult(
        boolean artifactReadable,
        List<ArtifactReference> artifacts) {

    CollectionPostProcessingResult {
        if (artifacts == null || artifacts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Post-processing Artifact must not be null");
        }
        artifacts = List.copyOf(artifacts);
    }
}
