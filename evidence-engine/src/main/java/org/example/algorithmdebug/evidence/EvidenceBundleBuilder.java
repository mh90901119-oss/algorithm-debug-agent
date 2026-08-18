package org.example.algorithmdebug.evidence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceBundle;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceFact;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.InputSnapshotStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;

/** 把显式、已校验的当前与比较来源整理成有界 Evidence Bundle。 */
public final class EvidenceBundleBuilder {

    private static final int MAX_FINDINGS_PER_COLLECTION = 16;

    /** 构建不解释业务语义且不内联 Raw Trace 的证据目录。 */
    public EvidenceBundle build(EvidenceBuildRequest request, EvidenceBuildSources sources) {
        if (request == null || sources == null) {
            throw new IllegalArgumentException("request 和 sources 不能为空");
        }
        validateBaseIdentity(request, sources);
        LinkedHashMap<CollectionId, ValidatedCollectionSource> indexed = index(sources.collections());
        List<ValidatedCollectionSource> current = select(
                request.collectionIds(), indexed, "当前");
        List<ValidatedCollectionSource> comparison = select(
                request.comparisonCollectionIds(), indexed, "比较");
        validateCollectionContexts(request, current, comparison);

        ArrayList<EvidenceFact> facts = new ArrayList<>();
        ArrayList<EvidenceFact> comparisonFacts = new ArrayList<>();
        LinkedHashSet<EvidenceDimension> covered = new LinkedHashSet<>();
        LinkedHashMap<String, ArtifactReference> artifacts = new LinkedHashMap<>();
        addArtifact(artifacts, sources.runOutcomeArtifact());
        addArtifact(artifacts, sources.contextArtifact());
        sources.runOutcome().artifacts().forEach(value -> addArtifact(artifacts, value));
        sources.runFingerprintArtifact().ifPresent(value -> addArtifact(artifacts, value));

        addBaseFacts(sources, facts, covered);
        boolean allCurrentValid = true;
        boolean diagnosticsTruncated = false;
        for (ValidatedCollectionSource source : current) {
            addArtifact(artifacts, source.validationArtifact());
            source.validation().summaryArtifact().ifPresent(value -> addArtifact(artifacts, value));
            addValidationArtifacts(artifacts, source.validation());
            facts.addAll(collectionFacts(source.validation()));
            diagnosticsTruncated |= source.validation().findings().size()
                    > MAX_FINDINGS_PER_COLLECTION;
            if (source.validation().status() == EvidenceValidationStatus.VALID) {
                covered.addAll(source.validation().coveredDimensions());
            } else {
                allCurrentValid = false;
            }
        }
        if (allCurrentValid) covered.add(EvidenceDimension.VALIDATION);
        Set<String> requiredArtifactIds = Set.copyOf(artifacts.keySet());

        for (ValidatedCollectionSource source : comparison) {
            addArtifact(artifacts, source.validationArtifact());
            source.validation().summaryArtifact().ifPresent(value -> addArtifact(artifacts, value));
            addValidationArtifacts(artifacts, source.validation());
            comparisonFacts.addAll(collectionFacts(source.validation()));
            diagnosticsTruncated |= source.validation().findings().size()
                    > MAX_FINDINGS_PER_COLLECTION;
        }

        facts.sort(FACT_ORDER);
        comparisonFacts.sort(FACT_ORDER);
        List<ArtifactReference> orderedArtifacts = artifacts.values().stream()
                .sorted(Comparator.comparing(ArtifactReference::relativePath)
                        .thenComparing(ArtifactReference::artifactId))
                .toList();
        EvidenceBundle bundle = new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE, request.evidenceId(), request.caseId(),
                request.contextId(), request.analysisId(), facts, comparisonFacts,
                Set.copyOf(covered), orderedArtifacts, diagnosticsTruncated, request.createdAt());
        if (estimatedBytes(bundle) <= request.maxEvidenceBundleBytes()) {
            return bundle;
        }
        List<ArtifactReference> requiredArtifacts = orderedArtifacts.stream()
                .filter(value -> requiredArtifactIds.contains(value.artifactId()))
                .toList();
        EvidenceBundle truncated = new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE, request.evidenceId(), request.caseId(),
                request.contextId(), request.analysisId(), facts, List.of(),
                Set.copyOf(covered), requiredArtifacts, true, request.createdAt());
        if (estimatedBytes(truncated) > request.maxEvidenceBundleBytes()) {
            throw new IllegalArgumentException("Evidence Bundle 超过 maxEvidenceBundleBytes");
        }
        return truncated;
    }

    private static void validateBaseIdentity(
            EvidenceBuildRequest request, EvidenceBuildSources sources) {
        var outcome = sources.runOutcome();
        var context = sources.contextSnapshot();
        if (!request.caseId().equals(outcome.caseId())
                || !request.contextId().equals(outcome.contextId())
                || !request.analysisId().equals(outcome.analysisId())
                || !request.caseId().equals(context.caseId())
                || !request.contextId().equals(context.contextId())) {
            throw new IllegalArgumentException("RunOutcome 或 Context 身份与请求不一致");
        }
        sources.runFingerprint().ifPresent(fingerprint -> {
            if (!request.caseId().equals(fingerprint.caseId())
                    || !request.contextId().equals(fingerprint.contextId())
                    || !outcome.runId().equals(fingerprint.runId())) {
                throw new IllegalArgumentException("Run fingerprint 身份与请求不一致");
            }
        });
    }

    private static LinkedHashMap<CollectionId, ValidatedCollectionSource> index(
            List<ValidatedCollectionSource> sources) {
        try {
            return sources.stream().collect(Collectors.toMap(
                    source -> source.validation().collectionId(), Function.identity(),
                    (left, right) -> { throw new IllegalArgumentException("Collection source 重复"); },
                    LinkedHashMap::new));
        } catch (IllegalStateException failure) {
            throw new IllegalArgumentException("Collection source 重复", failure);
        }
    }

    private static List<ValidatedCollectionSource> select(
            List<CollectionId> ids,
            LinkedHashMap<CollectionId, ValidatedCollectionSource> indexed,
            String role) {
        return ids.stream().map(id -> {
            ValidatedCollectionSource source = indexed.get(id);
            if (source == null) throw new IllegalArgumentException(role + " Collection 不存在: " + id.value());
            return source;
        }).toList();
    }

    private static void validateCollectionContexts(
            EvidenceBuildRequest request,
            List<ValidatedCollectionSource> current,
            List<ValidatedCollectionSource> comparison) {
        for (ValidatedCollectionSource source : current) {
            CollectionValidation value = source.validation();
            if (!request.caseId().equals(value.caseId())
                    || !request.contextId().equals(value.contextId())) {
                throw new IllegalArgumentException("当前 Collection 必须属于请求 Case/Context");
            }
        }
        for (ValidatedCollectionSource source : comparison) {
            if (!request.caseId().equals(source.validation().caseId())) {
                throw new IllegalArgumentException("比较 Collection 必须属于请求 Case");
            }
        }
    }

    private static void addBaseFacts(
            EvidenceBuildSources sources,
            List<EvidenceFact> facts,
            Set<EvidenceDimension> covered) {
        var outcome = sources.runOutcome();
        boolean targetObserved = outcome.targetFailure().isPresent()
                || outcome.testOutcome() == org.example.algorithmdebug.contracts.TestOutcome.PASSED
                || outcome.testOutcome() == org.example.algorithmdebug.contracts.TestOutcome.FAILED
                || outcome.testOutcome() == org.example.algorithmdebug.contracts.TestOutcome.ERROR;
        if (targetObserved) {
            facts.add(fact(ClaimClassification.CONFIRMED_FACT,
                    EvidenceDimension.TARGET_OUTCOME, "TARGET_OUTCOME_OBSERVED",
                    "目标 UT 运行结果已归档", List.of(sources.runOutcomeArtifact())));
            covered.add(EvidenceDimension.TARGET_OUTCOME);
        } else {
            facts.add(fact(ClaimClassification.MISSING_EVIDENCE,
                    EvidenceDimension.TARGET_OUTCOME, "TARGET_OUTCOME_UNAVAILABLE",
                    "Agent 未获得目标 UT 的可确认运行结果",
                    List.of(sources.runOutcomeArtifact())));
        }

        if (sources.contextSnapshot().inputSnapshot().status() == InputSnapshotStatus.PRESENT) {
            facts.add(fact(ClaimClassification.CONFIRMED_FACT, EvidenceDimension.INPUT,
                    "INPUT_SNAPSHOT_PRESENT", "算法输入存在且具有内容 Hash",
                    List.of(sources.contextArtifact())));
            covered.add(EvidenceDimension.INPUT);
        }
        if (sources.contextSnapshot().sourceSnapshot().completeness()
                == SnapshotCompleteness.COMPLETE) {
            facts.add(fact(ClaimClassification.CONFIRMED_FACT, EvidenceDimension.SOURCE,
                    "SOURCE_SNAPSHOT_COMPLETE", "目标源码快照完整",
                    List.of(sources.contextArtifact())));
            covered.add(EvidenceDimension.SOURCE);
        }
        Optional<ArtifactReference> gantt = outcome.artifacts().stream()
                .filter(value -> "GANTT".equals(value.artifactType())).findFirst();
        if (outcome.ganttOutcome() == GanttOutcome.PRESENT
                && gantt.isPresent() && sources.runFingerprint().isPresent()
                && sources.runFingerprint().orElseThrow().ganttRawSha256().isPresent()
                && sources.runFingerprint().orElseThrow().ganttRawSha256().orElseThrow()
                        .equals(gantt.orElseThrow().sha256())) {
            ArrayList<ArtifactReference> refs = new ArrayList<>();
            refs.add(gantt.orElseThrow());
            sources.runFingerprintArtifact().ifPresent(refs::add);
            facts.add(fact(ClaimClassification.CONFIRMED_FACT,
                    EvidenceDimension.SCHEDULE_RESULT, "SCHEDULE_RESULT_FINGERPRINTED",
                    "调度结果存在且与运行结果指纹一致", refs));
            covered.add(EvidenceDimension.SCHEDULE_RESULT);
        }
    }

    private static List<EvidenceFact> collectionFacts(CollectionValidation validation) {
        String code = switch (validation.status()) {
            case VALID -> "COLLECTION_VALID";
            case INCONCLUSIVE -> "COLLECTION_INCONCLUSIVE";
            case CONTRADICTED -> "COLLECTION_CONTRADICTED";
            case INVALID -> "COLLECTION_INVALID";
        };
        String summary = validation.collectorType() + " Collection 校验状态为 " + validation.status();
        ArrayList<EvidenceFact> result = new ArrayList<>();
        result.add(fact(ClaimClassification.VALIDATOR_CONCLUSION,
                EvidenceDimension.VALIDATION, code, summary,
                validation.summaryArtifact().stream().toList()));
        validation.findings().stream().limit(MAX_FINDINGS_PER_COLLECTION).forEach(finding ->
                result.add(new EvidenceFact(
                        ClaimClassification.VALIDATOR_CONCLUSION,
                        EvidenceDimension.VALIDATION, finding.code(), finding.detail(),
                        finding.artifacts(), finding.provenance())));
        return List.copyOf(result);
    }

    private static EvidenceFact fact(
            ClaimClassification classification, EvidenceDimension dimension,
            String code, String summary, List<ArtifactReference> artifacts) {
        return new EvidenceFact(classification, dimension, code, summary,
                artifacts, Optional.empty());
    }

    private static void addArtifact(
            LinkedHashMap<String, ArtifactReference> artifacts, ArtifactReference value) {
        ArtifactReference existing = artifacts.putIfAbsent(value.artifactId(), value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException("Artifact ID 指向了不同内容");
        }
    }

    private static void addValidationArtifacts(
            LinkedHashMap<String, ArtifactReference> artifacts,
            CollectionValidation validation) {
        validation.findings().stream().limit(MAX_FINDINGS_PER_COLLECTION).forEach(finding -> {
            finding.artifacts().forEach(value -> addArtifact(artifacts, value));
            finding.provenance().ifPresent(value -> addArtifact(artifacts, value.rawArtifact()));
        });
    }

    private static long estimatedBytes(EvidenceBundle bundle) {
        long bytes = 512;
        for (EvidenceFact fact : bundle.facts()) bytes += factBytes(fact);
        for (EvidenceFact fact : bundle.comparisonFacts()) bytes += factBytes(fact);
        for (ArtifactReference artifact : bundle.artifacts()) {
            bytes += artifactBytes(artifact);
        }
        return bytes;
    }

    private static long factBytes(EvidenceFact fact) {
        long bytes = utf8(fact.code()) + utf8(fact.summary()) + 192L;
        for (ArtifactReference artifact : fact.artifacts()) bytes += artifactBytes(artifact);
        if (fact.provenance().isPresent()) {
            bytes += artifactBytes(fact.provenance().orElseThrow().rawArtifact()) + 512L;
        }
        return bytes;
    }

    private static long artifactBytes(ArtifactReference artifact) {
        return utf8(artifact.artifactId()) + utf8(artifact.artifactType())
                + utf8(artifact.relativePath()) + utf8(artifact.mediaType()) + 256L;
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final Comparator<EvidenceFact> FACT_ORDER = Comparator
            .comparing((EvidenceFact fact) -> fact.dimension().ordinal())
            .thenComparing(EvidenceFact::code)
            .thenComparing(EvidenceFact::summary);
}
