package org.example.algorithmdebug.evidence;

import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CollectionValidation;

/**
 * 一次 Collection 校验结果及其自身归档引用。
 *
 * @param validation 确定性 Validator 产出的技术可信度
 * @param validationArtifact 该校验文档在 Case 中的不可变引用
 */
public record ValidatedCollectionSource(
        CollectionValidation validation,
        ArtifactReference validationArtifact) {

    /** 校验输入非空且 Artifact 类型明确。 */
    public ValidatedCollectionSource {
        if (validation == null || validationArtifact == null) {
            throw new IllegalArgumentException("Collection validation source 不能为空");
        }
        if (!"COLLECTION_VALIDATION".equals(validationArtifact.artifactType())) {
            throw new IllegalArgumentException("validationArtifact 类型必须为 COLLECTION_VALIDATION");
        }
    }
}
