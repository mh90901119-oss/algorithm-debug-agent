package org.example.algorithmdebug.casecore;

import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;

/**
 * 单个 Analysis 的有界只读控制快照，不包含 Raw Trace 内容。
 *
 * @param initialization Analysis manifest 与首条 Investigation 事件的组合状态
 * @param artifactIndex 已验证注册 Artifact 的 typed 归属索引
 * @param journal 已验证 Investigation Journal
 * @param evidenceEligibility 可用于结论门禁的 Evidence 资格目录
 * @param sourceQueryIds 已成功归档的 Source Query ID
 * @param artifactIds 当前 Analysis 可解引用的注册 Artifact ID
 */
public record AnalysisArchiveSnapshot(
        Initialization initialization,
        AnalysisArtifactIndex artifactIndex,
        InvestigationJournalReader.Result journal,
        Map<EvidenceId, EvidenceEligibility> evidenceEligibility,
        Set<SourceQueryId> sourceQueryIds,
        Set<String> artifactIds) {

    /** Analysis 的三态初始化语义。 */
    public enum Initialization { ABSENT, INITIALIZED, INVALID }

    /** 防御性冻结快照集合。 */
    public AnalysisArchiveSnapshot {
        if (initialization == null || artifactIndex == null || journal == null
                || evidenceEligibility == null || sourceQueryIds == null
                || artifactIds == null
                || evidenceEligibility.entrySet().stream().anyMatch(value ->
                value.getKey() == null || value.getValue() == null)
                || sourceQueryIds.stream().anyMatch(java.util.Objects::isNull)
                || artifactIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Analysis archive snapshot fields are invalid");
        }
        evidenceEligibility = Map.copyOf(evidenceEligibility);
        sourceQueryIds = Set.copyOf(sourceQueryIds);
        artifactIds = Set.copyOf(artifactIds);
    }

    /** 创建不携带任何可见 Analysis 数据的引导或损坏快照。 */
    public static AnalysisArchiveSnapshot empty(Initialization initialization) {
        if (initialization == Initialization.INITIALIZED) {
            throw new IllegalArgumentException("Initialized snapshot requires control data");
        }
        return new AnalysisArchiveSnapshot(
                initialization, AnalysisArtifactIndex.from(java.util.List.of()),
                new InvestigationJournalReader.Result(java.util.List.of(), java.util.List.of()),
                Map.of(), Set.of(), Set.of());
    }
}
