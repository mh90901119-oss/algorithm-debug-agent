package org.example.algorithmdebug.evidence;

import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ContextRecord;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunResultFingerprint;

/**
 * Evidence Bundle 构建时已由归档层读取并验证结构的显式输入。
 *
 * @param runOutcome 当前 Context 的目标 UT 结果摘要
 * @param runOutcomeArtifact 结果摘要的不可变引用
 * @param contextRecord 当前显式 Context 记录
 * @param contextArtifact Context 记录的不可变引用
 * @param runFingerprint 可选的运行结果指纹
 * @param runFingerprintArtifact 指纹存在时对应的不可变引用
 * @param collections 当前请求显式引用的全部当前或比较 Collection
 */
public record EvidenceBuildSources(
        RunOutcomeSummary runOutcome,
        ArtifactReference runOutcomeArtifact,
        ContextRecord contextRecord,
        ArtifactReference contextArtifact,
        Optional<RunResultFingerprint> runFingerprint,
        Optional<ArtifactReference> runFingerprintArtifact,
        List<ValidatedCollectionSource> collections) {

    /** 校验可选项配对、集合上限和空值。 */
    public EvidenceBuildSources {
        if (runOutcome == null || runOutcomeArtifact == null
                || contextRecord == null || contextArtifact == null
                || runFingerprint == null || runFingerprintArtifact == null
                || collections == null || collections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Evidence build sources must not be null");
        }
        if (runFingerprint.isPresent() != runFingerprintArtifact.isPresent()) {
            throw new IllegalArgumentException("Run fingerprint and Artifact must both be present");
        }
        if (collections.size() > 32) {
            throw new IllegalArgumentException("Collection sources must not exceed 32 entries");
        }
        collections = List.copyOf(collections);
    }
}
