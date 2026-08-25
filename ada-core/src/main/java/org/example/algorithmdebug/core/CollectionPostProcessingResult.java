package org.example.algorithmdebug.core;

import java.util.List;
import org.example.algorithmdebug.contracts.ArtifactReference;

/** 一次 Collection 后处理的有界结果；失败与 Collector 完成状态相互独立。 */
record CollectionPostProcessingResult(
        boolean confirmationUsable,
        List<ArtifactReference> artifacts) {

    CollectionPostProcessingResult {
        if (artifacts == null || artifacts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("后处理 Artifact 不能为空");
        }
        artifacts = List.copyOf(artifacts);
    }
}
