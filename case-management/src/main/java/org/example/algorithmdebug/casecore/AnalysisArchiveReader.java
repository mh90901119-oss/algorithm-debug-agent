package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult;

/**
 * 从有界注册 metadata 与 typed 控制文档重建 Analysis 控制快照。
 *
 * <p>本 Reader 不枚举 Collection/Run 的 raw 子目录，也不打开 Raw Trace；所有归属都来自控制文档身份和
 * 已做内容哈希验证的 Artifact 注册。</p>
 */
public final class AnalysisArchiveReader {
    private static final int MAX_CONTROL_ENTRIES = AnalysisArtifactIndex.MAX_ENTRIES;

    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /** @param casesRoot 一个 Project 的 Case 根，可在合法引导态尚不存在 @param mapper 有界 JSON 映射器 */
    public AnalysisArchiveReader(Path casesRoot, BoundedDocumentMapper mapper) {
        if (casesRoot == null || mapper == null) {
            throw new IllegalArgumentException("casesRoot and mapper must not be null");
        }
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        this.mapper = mapper;
        this.writer = new AtomicDocumentWriter();
    }

    /** 读取一次不可变快照；半初始化、损坏或越界控制数据统一返回 INVALID。 */
    public AnalysisArchiveSnapshot read(AnalysisIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, identity.caseId());
        boolean manifestExists = regularFile(layout.analysisDocument(identity.analysisId()));
        boolean journalExists = regularDirectory(
                layout.investigationEventsRoot(identity.analysisId()));
        if (!manifestExists && !journalExists) {
            return AnalysisArchiveSnapshot.empty(AnalysisArchiveSnapshot.Initialization.ABSENT);
        }
        if (manifestExists != journalExists || !regularDirectory(casesRoot)) {
            return AnalysisArchiveSnapshot.empty(AnalysisArchiveSnapshot.Initialization.INVALID);
        }
        try {
            return readInitialized(identity, layout);
        } catch (RuntimeException failure) {
            return AnalysisArchiveSnapshot.empty(AnalysisArchiveSnapshot.Initialization.INVALID);
        }
    }

    private AnalysisArchiveSnapshot readInitialized(
            AnalysisIdentity identity, CaseArchiveLayout layout) {
        CaseArchiveRepository repository = new CaseArchiveRepository(casesRoot, mapper, writer);
        if (!repository.requireCase(identity.caseId()).projectId().equals(identity.projectId())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "Case does not belong to Project");
        }
        repository.requireAnalysis(identity.caseId(), identity.analysisId());
        InvestigationJournalReader.Result journal = new InvestigationJournalReader(
                casesRoot, mapper).readValidatedEvents(identity);
        if (!journal.limitations().isEmpty() || journal.events().isEmpty()
                || !(journal.events().getFirst()
                instanceof InvestigationEvent.ProblemFrameDefined first)
                || first.sequence() != 1
                || !first.problemFrame().caseId().equals(identity.caseId())
                || !first.problemFrame().analysisId().equals(identity.analysisId())) {
            throw new WorkspaceException(
                    "INVESTIGATION_ARCHIVE_INVALID", "Analysis has no valid initial ProblemFrame");
        }

        Map<String, CaseArtifactRegistration> registrations = registrations(layout);
        LinkedHashMap<String, AnalysisArtifactIndex.Entry> indexed = new LinkedHashMap<>();
        indexAnalysisScopedArtifacts(identity, layout, registrations, indexed);
        indexAlgorithmInput(identity, repository, registrations, indexed);
        indexRuns(identity, layout, repository, registrations, indexed);
        Map<CollectionId, CollectionExecutionSummary> collections = indexCollections(
                identity, layout, repository, registrations, indexed);
        Map<EvidenceId, EvidenceEligibility> evidence = indexEvidence(
                identity, layout, repository, registrations, indexed, collections);
        Set<SourceQueryId> sourceQueries = sourceQueries(identity, layout, repository);
        AnalysisArtifactIndex artifactIndex = AnalysisArtifactIndex.from(
                new ArrayList<>(indexed.values()));
        return new AnalysisArchiveSnapshot(
                AnalysisArchiveSnapshot.Initialization.INITIALIZED,
                artifactIndex, journal, evidence, sourceQueries,
                new LinkedHashSet<>(indexed.keySet()));
    }

    private Map<String, CaseArtifactRegistration> registrations(CaseArchiveLayout layout) {
        LinkedHashMap<String, CaseArtifactRegistration> result = new LinkedHashMap<>();
        ArtifactIntegrityChecker integrity = new ArtifactIntegrityChecker();
        for (Path file : regularJsonFiles(layout.artifactsRoot())) {
            CaseArtifactRegistration registration = mapper.readJson(
                    file, CaseArtifactRegistration.class);
            String expectedName = registration.artifact().artifactId() + ".json";
            Path artifact = layout.caseRoot().resolve(
                    registration.artifact().relativePath()).normalize();
            if (!registration.caseId().equals(
                    new org.example.algorithmdebug.contracts.CaseId(
                            layout.caseRoot().getFileName().toString()))
                    || !file.getFileName().toString().equals(expectedName)
                    || !artifact.startsWith(layout.caseRoot())
                    || integrity.verify(registration.artifact(), artifact).status()
                    != ArtifactIntegrityChecker.Status.VALID
                    || result.putIfAbsent(
                    registration.artifact().artifactId(), registration) != null) {
                throw new WorkspaceException(
                        "CASE_ARTIFACT_REGISTRATION_INVALID",
                        "Artifact registration is invalid or duplicated");
            }
        }
        return Map.copyOf(result);
    }

    private void indexAnalysisScopedArtifacts(
            AnalysisIdentity identity,
            CaseArchiveLayout layout,
            Map<String, CaseArtifactRegistration> registrations,
            Map<String, AnalysisArtifactIndex.Entry> indexed) {
        String prefix = portable(layout.caseRoot().relativize(
                layout.analysisRoot(identity.analysisId()))) + "/";
        Map<String, PlanId> plansByPath = plansByPath(identity, layout);
        registrations.values().stream()
                .map(CaseArtifactRegistration::artifact)
                .filter(artifact -> artifact.relativePath().startsWith(prefix))
                .sorted(Comparator.comparing(ArtifactReference::artifactId))
                .forEach(artifact -> put(indexed, entry(
                        identity, Optional.empty(),
                        Optional.ofNullable(plansByPath.get(artifact.relativePath())),
                        Optional.empty(), Optional.empty(), artifact, Optional.empty())));
    }

    private Map<String, PlanId> plansByPath(
            AnalysisIdentity identity, CaseArchiveLayout layout) {
        HashMap<String, PlanId> result = new HashMap<>();
        for (Path path : regularJsonFiles(layout.analysisPlansRoot(identity.analysisId()))) {
            JsonNode node = mapper.readJson(path, JsonNode.class);
            if (!node.hasNonNull("planId")) {
                throw new WorkspaceException("PLAN_DOCUMENT_INVALID", "Plan has no PlanId");
            }
            PlanId planId = new PlanId(node.get("planId").asText());
            if (node.has("methodSelections")) {
                CodePathCollectionPlan plan = mapper.readJson(path, CodePathCollectionPlan.class);
                requireIdentity(identity, plan.caseId(), plan.analysisId());
            } else if (node.has("tracepoints")) {
                JdwpCollectionPlan plan = mapper.readJson(path, JdwpCollectionPlan.class);
                requireIdentity(identity, plan.caseId(), plan.analysisId());
            } else {
                throw new WorkspaceException("PLAN_DOCUMENT_INVALID", "Unknown Plan document");
            }
            result.put(portable(layout.caseRoot().relativize(path)), planId);
        }
        return Map.copyOf(result);
    }

    private void indexAlgorithmInput(
            AnalysisIdentity identity,
            CaseArchiveRepository repository,
            Map<String, CaseArtifactRegistration> registrations,
            Map<String, AnalysisArtifactIndex.Entry> indexed) {
        repository.findAlgorithmInputCapture(identity.caseId(), identity.analysisId())
                .ifPresent(capture -> put(indexed, entry(
                        identity, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), registered(registrations, capture.artifact()),
                        Optional.empty())));
    }

    private void indexRuns(
            AnalysisIdentity identity,
            CaseArchiveLayout layout,
            CaseArchiveRepository repository,
            Map<String, CaseArtifactRegistration> registrations,
            Map<String, AnalysisArtifactIndex.Entry> indexed) {
        for (Path runRoot : regularDirectories(layout.runsRoot())) {
            RunId runId = new RunId(runRoot.getFileName().toString());
            Path requestPath = layout.runRequest(runId);
            Path outcomePath = layout.runOutcome(runId);
            if (!regularFile(requestPath) || !regularFile(outcomePath)) {
                continue;
            }
            var request = repository.requireRunRequest(identity.caseId(), runId);
            if (!request.analysisId().equals(identity.analysisId())) {
                continue;
            }
            var outcome = repository.findRunOutcome(identity.caseId(), runId).orElseThrow();
            requireIdentity(identity, outcome.caseId(), outcome.analysisId());
            for (ArtifactReference artifact : outcome.artifacts()) {
                put(indexed, entry(
                        identity, Optional.of(request.runId()), Optional.empty(),
                        Optional.empty(), Optional.empty(),
                        registered(registrations, artifact), Optional.empty()));
            }
        }
    }

    private Map<CollectionId, CollectionExecutionSummary> indexCollections(
            AnalysisIdentity identity,
            CaseArchiveLayout layout,
            CaseArchiveRepository repository,
            Map<String, CaseArtifactRegistration> registrations,
            Map<String, AnalysisArtifactIndex.Entry> indexed) {
        LinkedHashMap<CollectionId, CollectionExecutionSummary> summaries = new LinkedHashMap<>();
        for (Path collectionRoot : regularDirectories(layout.collectionsRoot())) {
            CollectionId collectionId = new CollectionId(
                    collectionRoot.getFileName().toString());
            Path summaryPath = layout.collectionSummary(collectionId);
            if (!regularFile(summaryPath)) {
                continue;
            }
            CollectionExecutionSummary summary = repository.requireCollectionExecutionSummary(
                    identity.caseId(), collectionId);
            if (!summary.caseId().equals(identity.caseId())
                    || !summary.analysisId().equals(identity.analysisId())) {
                continue;
            }
            if (summaries.putIfAbsent(summary.collectionId(), summary) != null) {
                throw new WorkspaceException(
                        "COLLECTION_SUMMARY_INVALID", "Duplicate Collection summary");
            }
            for (String artifactId : summary.artifactIds()) {
                ArtifactReference artifact = requireRegistration(registrations, artifactId);
                put(indexed, entry(
                        identity, Optional.of(summary.runId()), Optional.of(summary.planId()),
                        Optional.of(summary.collectionId()), Optional.empty(), artifact,
                        Optional.of(summary.eligibility())));
            }
        }
        return Map.copyOf(summaries);
    }

    private Map<EvidenceId, EvidenceEligibility> indexEvidence(
            AnalysisIdentity identity,
            CaseArchiveLayout layout,
            CaseArchiveRepository repository,
            Map<String, CaseArtifactRegistration> registrations,
            Map<String, AnalysisArtifactIndex.Entry> indexed,
            Map<CollectionId, CollectionExecutionSummary> collections) {
        LinkedHashMap<EvidenceId, EvidenceEligibility> eligibility = new LinkedHashMap<>();
        for (Path evidenceRoot : regularDirectories(layout.evidenceRoot())) {
            EvidenceId evidenceId = new EvidenceId(evidenceRoot.getFileName().toString());
            Path requestPath = layout.evidenceBuildRequest(evidenceId);
            Path bundlePath = layout.evidenceBundle(evidenceId);
            if (!regularFile(requestPath) || !regularFile(bundlePath)) {
                continue;
            }
            EvidenceBuildRequest request = repository.requireEvidenceRequest(
                    identity.caseId(), evidenceId);
            if (!request.caseId().equals(identity.caseId())
                    || !request.analysisId().equals(identity.analysisId())) {
                continue;
            }
            var bundle = repository.requireEvidenceBundle(identity.caseId(), evidenceId);
            requireIdentity(identity, bundle.caseId(), bundle.analysisId());
            if (!bundle.evidenceId().equals(request.evidenceId())) {
                throw new WorkspaceException(
                        "EVIDENCE_IDENTITY_MISMATCH", "Evidence Bundle does not match request");
            }
            EvidenceEligibility combined = combineEligibility(
                    request.collectionIds().stream()
                            .map(collections::get)
                            .map(summary -> summary == null ? null : summary.eligibility())
                            .toList());
            if (eligibility.putIfAbsent(request.evidenceId(), combined) != null) {
                throw new WorkspaceException(
                        "EVIDENCE_IDENTITY_MISMATCH", "Duplicate Evidence identity");
            }
            for (ArtifactReference reference : bundle.artifacts()) {
                ArtifactReference artifact = registered(registrations, reference);
                AnalysisArtifactIndex.Entry current = indexed.get(artifact.artifactId());
                put(indexed, entry(
                        identity,
                        current == null ? Optional.empty() : current.runId(),
                        current == null ? Optional.empty() : current.planId(),
                        current == null ? Optional.empty() : current.collectionId(),
                        Optional.of(request.evidenceId()), artifact, Optional.of(combined)));
            }
        }
        return Map.copyOf(eligibility);
    }

    private Set<SourceQueryId> sourceQueries(
            AnalysisIdentity identity,
            CaseArchiveLayout layout,
            CaseArchiveRepository repository) {
        LinkedHashSet<SourceQueryId> result = new LinkedHashSet<>();
        Path root = layout.sourceQueriesRoot(identity.analysisId());
        for (Path queryRoot : regularDirectories(root)) {
            SourceQueryId queryId = new SourceQueryId(queryRoot.getFileName().toString());
            Path resultPath = layout.sourceQueryResult(identity.analysisId(), queryId);
            if (!regularFile(resultPath)) {
                continue;
            }
            SourceQueryResult value = repository.requireSourceQueryResult(
                    identity.caseId(), identity.analysisId(), queryId);
            result.add(value.queryId());
        }
        return Set.copyOf(result);
    }

    private static EvidenceEligibility combineEligibility(List<EvidenceEligibility> values) {
        if (values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return EvidenceEligibility.legacyUnknown();
        }
        boolean readable = values.stream().allMatch(EvidenceEligibility::artifactReadable);
        boolean complete = values.stream().allMatch(EvidenceEligibility::collectionComplete);
        boolean baselineRequired = values.stream().anyMatch(EvidenceEligibility::baselineRequired);
        boolean comparable = baselineRequired && values.stream()
                .filter(EvidenceEligibility::baselineRequired)
                .allMatch(EvidenceEligibility::baselineComparable);
        boolean matched = baselineRequired && values.stream()
                .filter(EvidenceEligibility::baselineRequired)
                .allMatch(EvidenceEligibility::failureFingerprintMatched);
        boolean obligation = values.stream().allMatch(EvidenceEligibility::obligationSatisfied);
        List<String> reasons = eligibilityReasons(
                readable, complete, baselineRequired, comparable, matched, obligation);
        return new EvidenceEligibility(
                org.example.algorithmdebug.contracts.SchemaVersions.EVIDENCE_ELIGIBILITY,
                readable, complete, baselineRequired, comparable, matched, obligation,
                readable && complete && obligation && (!baselineRequired || matched), reasons);
    }

    private static List<String> eligibilityReasons(
            boolean readable,
            boolean complete,
            boolean baselineRequired,
            boolean comparable,
            boolean matched,
            boolean obligation) {
        List<String> reasons = new ArrayList<>();
        if (!readable) {
            reasons.add(EvidenceEligibilityReason.ARTIFACT_UNREADABLE.name());
        }
        if (!complete) {
            reasons.add(EvidenceEligibilityReason.COLLECTION_INCOMPLETE.name());
        }
        if (!baselineRequired) {
            reasons.add(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name());
        } else if (matched) {
            reasons.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_MATCHED.name());
        } else if (comparable) {
            reasons.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_CHANGED.name());
        } else {
            reasons.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_INCOMPARABLE.name());
        }
        if (!obligation) {
            reasons.add(EvidenceEligibilityReason.OBLIGATION_UNSATISFIED.name());
        }
        return List.copyOf(reasons);
    }

    private static AnalysisArtifactIndex.Entry entry(
            AnalysisIdentity identity,
            Optional<RunId> runId,
            Optional<PlanId> planId,
            Optional<CollectionId> collectionId,
            Optional<EvidenceId> evidenceId,
            ArtifactReference artifact,
            Optional<EvidenceEligibility> eligibility) {
        return new AnalysisArtifactIndex.Entry(
                identity, runId, planId, collectionId, evidenceId, artifact, eligibility);
    }

    private static void put(
            Map<String, AnalysisArtifactIndex.Entry> indexed,
            AnalysisArtifactIndex.Entry entry) {
        indexed.put(entry.artifact().artifactId(), entry);
    }

    private static ArtifactReference registered(
            Map<String, CaseArtifactRegistration> registrations,
            ArtifactReference expected) {
        ArtifactReference registered = requireRegistration(
                registrations, expected.artifactId());
        if (!registered.equals(expected)) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_IDENTITY_MISMATCH",
                    "Control document Artifact does not match its registration");
        }
        return registered;
    }

    private static ArtifactReference requireRegistration(
            Map<String, CaseArtifactRegistration> registrations, String artifactId) {
        CaseArtifactRegistration registration = registrations.get(artifactId);
        if (registration == null) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_NOT_REGISTERED", "Control Artifact is not registered");
        }
        return registration.artifact();
    }

    private List<Path> regularJsonFiles(Path root) {
        return boundedChildren(root, false).stream()
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .toList();
    }

    private List<Path> regularDirectories(Path root) {
        return boundedChildren(root, true);
    }

    private List<Path> boundedChildren(Path root, boolean directories) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!regularDirectory(root)) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_READ_FAILED", "Control path is not a regular directory");
        }
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                boolean valid = directories ? regularDirectory(path) : regularFile(path);
                if (!valid || Files.isSymbolicLink(path)) {
                    throw new WorkspaceException(
                            "CASE_ARCHIVE_READ_FAILED", "Control directory has an invalid entry");
                }
                if (result.size() == MAX_CONTROL_ENTRIES) {
                    throw new WorkspaceException(
                            "CASE_ARCHIVE_LIMIT_EXCEEDED", "Control entry budget exceeded");
                }
                result.add(path);
            }
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_READ_FAILED", "Unable to enumerate control documents", failure);
        }
        result.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(result);
    }

    private static boolean regularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static boolean regularDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void requireIdentity(
            AnalysisIdentity expected,
            org.example.algorithmdebug.contracts.CaseId caseId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId) {
        if (!expected.caseId().equals(caseId) || !expected.analysisId().equals(analysisId)) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH",
                    "Control document belongs to another Analysis");
        }
    }
}
