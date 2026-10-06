package org.example.algorithmdebug.casecore;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;

/**
 * 已校验 Artifact metadata 的有界不可变索引；不打开 Raw 文件，也不从目录名推断业务事实。
 */
public final class AnalysisArtifactIndex {
    /** 单个 Project 控制视图允许索引的最大 Artifact 数。 */
    public static final int MAX_ENTRIES = 4_096;

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing((Entry value) -> value.identity().projectId().value())
            .thenComparing(value -> value.identity().caseId().value())
            .thenComparing(value -> value.identity().analysisId().value())
            .thenComparing(value -> optionalValue(value.runId()))
            .thenComparing(value -> optionalValue(value.planId()))
            .thenComparing(value -> optionalValue(value.collectionId()))
            .thenComparing(value -> optionalValue(value.evidenceId()))
            .thenComparing(value -> value.artifact().artifactId());

    private final List<Entry> entries;

    /**
     * @param identity Artifact 所属 Analysis
     * @param runId 可选 Run ID
     * @param planId 可选 Plan ID
     * @param collectionId 可选 Collection ID
     * @param evidenceId 可选 Evidence ID
     * @param artifact 已做路径与哈希校验的 Artifact 引用
     * @param eligibility 可选动态证据确认资格
     */
    public record Entry(
            AnalysisIdentity identity,
            Optional<RunId> runId,
            Optional<PlanId> planId,
            Optional<CollectionId> collectionId,
            Optional<EvidenceId> evidenceId,
            ArtifactReference artifact,
            Optional<EvidenceEligibility> eligibility) {
        public Entry {
            identity = requireNonNull(identity, "identity");
            runId = requireOptional(runId, "runId");
            planId = requireOptional(planId, "planId");
            collectionId = requireOptional(collectionId, "collectionId");
            evidenceId = requireOptional(evidenceId, "evidenceId");
            artifact = requireNonNull(artifact, "artifact");
            eligibility = requireOptional(eligibility, "eligibility");
        }
    }

    private AnalysisArtifactIndex(List<Entry> entries) {
        this.entries = entries;
    }

    /** 从调用方已验证的元数据建立规范顺序索引。 */
    public static AnalysisArtifactIndex from(List<Entry> source) {
        List<Entry> checked = List.copyOf(requireNonNull(source, "source"));
        if (checked.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "Artifact index exceeds maximum entry count " + MAX_ENTRIES);
        }
        if (checked.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Artifact index must not contain null entries");
        }
        Set<EntryKey> keys = new HashSet<>();
        for (Entry entry : checked) {
            EntryKey key = new EntryKey(entry.identity(), entry.artifact().artifactId());
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate Artifact identity: " + entry.artifact().artifactId());
            }
        }
        return new AnalysisArtifactIndex(checked.stream().sorted(ENTRY_ORDER).toList());
    }

    /** @return 全部条目的规范不可变顺序 */
    public List<Entry> entries() {
        return entries;
    }

    /** @return 指定 Analysis 的规范顺序条目 */
    public List<Entry> forAnalysis(AnalysisIdentity identity) {
        AnalysisIdentity checked = requireNonNull(identity, "identity");
        return entries.stream().filter(value -> value.identity().equals(checked)).toList();
    }

    private record EntryKey(AnalysisIdentity identity, String artifactId) {
    }

    private static String optionalValue(Optional<? extends org.example.algorithmdebug.contracts.OpaqueIdentifier> value) {
        return value.map(org.example.algorithmdebug.contracts.OpaqueIdentifier::value).orElse("");
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
