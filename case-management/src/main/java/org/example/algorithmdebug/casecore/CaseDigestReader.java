package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArchiveWarning;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SufficiencyEvaluation;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 从不可变 Case 子文档确定性重建有界摘要，不创建新的事实文件。 */
public final class CaseDigestReader {

    private static final int MAX_ITEMS = 20;
    private static final int MAX_EXCERPT = 2_048;

    private final CaseArchiveRepository repository;

    /** @param repository Case 归档读取入口 */
    public CaseDigestReader(CaseArchiveRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.repository = repository;
    }

    /**
     * 读取一个 Case；Case 身份损坏时失败，子文档损坏时返回告警并继续读取其他事实。
     *
     * @param caseId Case ID
     * @return 有界摘要
     */
    public CaseDigest read(CaseId caseId) {
        CaseManifest manifest = repository.requireCase(caseId);
        CaseArchiveLayout layout = repository.layout(caseId);
        List<ArchiveWarning> warnings = new ArrayList<>();
        List<AnalysisEntry> analyses = readAnalyses(layout, warnings);
        List<RunEntry> runs = readRuns(layout, warnings);
        List<CollectionEntry> collections = readCollections(layout, warnings);
        List<EvidenceEntry> evidence = readEvidence(layout, warnings);
        analyses.sort(Comparator.comparing((AnalysisEntry value) -> value.request().createdAt())
                .thenComparing(value -> value.request().analysisId().value()));
        runs.sort(Comparator.comparing((RunEntry value) -> value.request().createdAt())
                .thenComparing(value -> value.request().runId().value()));

        List<RunOutcomeSummary> completed = runs.stream()
                .filter(value -> value.outcome().isPresent())
                .sorted(Comparator.comparing((RunEntry value) -> value.request().createdAt())
                        .thenComparing(value -> value.request().runId().value()).reversed())
                .limit(MAX_ITEMS)
                .map(value -> value.outcome().orElseThrow())
                .toList();
        List<RunId> incomplete = runs.stream()
                .filter(value -> value.outcome().isEmpty())
                .sorted(Comparator.comparing((RunEntry value) -> value.request().createdAt())
                        .thenComparing(value -> value.request().runId().value()).reversed())
                .limit(MAX_ITEMS)
                .map(value -> value.request().runId())
                .toList();
        List<CollectionExecutionSummary> recentCollections = collections.stream()
                .filter(value -> value.summary().isPresent())
                .sorted(Comparator.comparing((CollectionEntry value) -> value.createdAt())
                        .thenComparing(value -> value.collectionId().value()).reversed())
                .limit(MAX_ITEMS).map(value -> value.summary().orElseThrow()).toList();
        List<SufficiencyEvaluation> recentEvidence = evidence.stream()
                .filter(value -> value.sufficiency().isPresent())
                .sorted(Comparator.comparing((EvidenceEntry value) -> value.request().createdAt())
                        .thenComparing(value -> value.request().evidenceId().value()).reversed())
                .limit(MAX_ITEMS).map(value -> value.sufficiency().orElseThrow()).toList();
        AnalysisRequest latestAnalysis = analyses.isEmpty() ? null : analyses.getLast().request();
        String question = latestAnalysis == null
                ? manifest.initialQuestion() : latestAnalysis.question();
        boolean truncated = completedRunCount(runs) > MAX_ITEMS
                || incompleteRunCount(runs) > MAX_ITEMS
                || completedCollectionCount(collections) > MAX_ITEMS
                || completedEvidenceCount(evidence) > MAX_ITEMS
                || warnings.size() > MAX_ITEMS;

        return new CaseDigest(
                SchemaVersions.CASE_DIGEST,
                manifest.caseId(),
                manifest.projectId(),
                manifest.targetTest(),
                latestAnalysis == null ? Optional.empty() : Optional.of(latestAnalysis.analysisId()),
                excerpt(question),
                completed.isEmpty() ? Optional.empty() : Optional.of(completed.getFirst().runId()),
                completed,
                incomplete,
                recentCollections,
                recentEvidence,
                warnings.stream().limit(MAX_ITEMS).toList(),
                analyses.size(), runs.size(), collections.size(), evidence.size(), truncated);
    }


    private List<AnalysisEntry> readAnalyses(
            CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<AnalysisEntry> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.analysesRoot())) {
            Path document = directory.resolve("analysis-request.json");
            readChild(layout, document, AnalysisRequest.class, warnings).ifPresent(value -> {
                if (value.caseId().equals(caseId(layout))
                        && value.analysisId().value().equals(directory.getFileName().toString())) {
                    values.add(new AnalysisEntry(value));
                } else {
                    warning(layout, document, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                }
            });
        }
        return values;
    }

    private List<CollectionEntry> readCollections(
            CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<CollectionEntry> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.collectionsRoot())) {
            Path requestDocument = directory.resolve("collection-request.json");
            Optional<CollectionEntry> request = readCollectionRequest(
                    layout, directory, requestDocument, warnings);
            if (request.isEmpty()) continue;
            CollectionEntry entry = request.orElseThrow();
            Path summaryDocument = directory.resolve("collection-summary.json");
            Optional<CollectionExecutionSummary> summary = Files.isRegularFile(
                    summaryDocument, LinkOption.NOFOLLOW_LINKS)
                    ? readChild(layout, summaryDocument, CollectionExecutionSummary.class, warnings)
                    : Optional.empty();
            if (summary.isPresent()) {
                CollectionExecutionSummary item = summary.orElseThrow();
                if (!item.caseId().equals(caseId(layout))
                        || !item.collectionId().equals(entry.collectionId())
                        || !item.analysisId().equals(entry.analysisId())) {
                    warning(layout, summaryDocument, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                    summary = Optional.empty();
                }
            }
            values.add(entry.withSummary(summary));
        }
        return values;
    }

    private Optional<CollectionEntry> readCollectionRequest(
            CaseArchiveLayout layout, Path directory, Path document,
            List<ArchiveWarning> warnings) {
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            warning(layout, document, "CASE_CHILD_DOCUMENT_MISSING", warnings);
            return Optional.empty();
        }
        try {
            MethodPathCollectionRecord value = repository.mapper().readJson(
                    document, MethodPathCollectionRecord.class);
            return collectionEntry(layout, directory, document, value.caseId(), value.analysisId(), value.collectionId(), value.createdAt(), warnings);
        } catch (RuntimeException ignored) {
            try {
                JdwpCollectionRecord value = repository.mapper().readJson(
                        document, JdwpCollectionRecord.class);
                return collectionEntry(layout, directory, document, value.caseId(), value.analysisId(), value.collectionId(), value.createdAt(), warnings);
            } catch (RuntimeException failure) {
                warning(layout, document, "CASE_CHILD_DOCUMENT_INVALID", warnings);
                return Optional.empty();
            }
        }
    }

    private static Optional<CollectionEntry> collectionEntry(
            CaseArchiveLayout layout, Path directory, Path document,
            CaseId caseId, org.example.algorithmdebug.contracts.AnalysisId analysisId,
            CollectionId collectionId, java.time.Instant createdAt,
            List<ArchiveWarning> warnings) {
        if (!caseId.equals(caseId(layout))
                || !collectionId.value().equals(directory.getFileName().toString())) {
            warning(layout, document, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
            return Optional.empty();
        }
        return Optional.of(new CollectionEntry(
                collectionId, analysisId, createdAt, Optional.empty()));
    }

    private List<EvidenceEntry> readEvidence(
            CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<EvidenceEntry> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.evidenceRoot())) {
            Path requestDocument = directory.resolve("evidence-build-request.json");
            Optional<EvidenceBuildRequest> request = readChild(
                    layout, requestDocument, EvidenceBuildRequest.class, warnings);
            if (request.isEmpty()) continue;
            EvidenceBuildRequest item = request.orElseThrow();
            if (!item.caseId().equals(caseId(layout))
                    || !item.evidenceId().value().equals(directory.getFileName().toString())) {
                warning(layout, requestDocument, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                continue;
            }
            Path sufficiencyDocument = directory.resolve("sufficiency-evaluation.json");
            Optional<SufficiencyEvaluation> sufficiency = Files.isRegularFile(
                    sufficiencyDocument, LinkOption.NOFOLLOW_LINKS)
                    ? readChild(layout, sufficiencyDocument, SufficiencyEvaluation.class, warnings)
                    : Optional.empty();
            if (sufficiency.isPresent()) {
                SufficiencyEvaluation result = sufficiency.orElseThrow();
                if (!result.caseId().equals(item.caseId())
                        || !result.analysisId().equals(item.analysisId())
                        || !result.evidenceId().equals(item.evidenceId())) {
                    warning(layout, sufficiencyDocument, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                    sufficiency = Optional.empty();
                }
            }
            values.add(new EvidenceEntry(item, sufficiency));
        }
        return values;
    }

    private List<RunEntry> readRuns(CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<RunEntry> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.runsRoot())) {
            Path requestDocument = directory.resolve("run-request.json");
            Optional<RunRequest> request = readChild(
                    layout, requestDocument, RunRequest.class, warnings);
            if (request.isEmpty()) {
                continue;
            }
            RunRequest runRequest = request.orElseThrow();
            if (!runRequest.caseId().equals(caseId(layout))
                    || !runRequest.runId().value().equals(directory.getFileName().toString())) {
                warning(layout, requestDocument, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                continue;
            }
            Path outcomeDocument = directory.resolve("run-outcome.json");
            Optional<RunOutcomeSummary> outcome = Files.isRegularFile(
                    outcomeDocument, LinkOption.NOFOLLOW_LINKS)
                    ? readChild(layout, outcomeDocument, RunOutcomeSummary.class, warnings)
                    : Optional.empty();
            if (outcome.isPresent()) {
                RunOutcomeSummary summary = outcome.orElseThrow();
                if (!summary.caseId().equals(runRequest.caseId())
                        || !summary.analysisId().equals(runRequest.analysisId())
                        || !summary.runId().equals(runRequest.runId())) {
                    warning(layout, outcomeDocument, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                    outcome = Optional.empty();
                }
            }
            values.add(new RunEntry(runRequest, outcome));
        }
        return values;
    }

    private <T> Optional<T> readChild(
            CaseArchiveLayout layout,
            Path document,
            Class<T> type,
            List<ArchiveWarning> warnings) {
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            warning(layout, document, "CASE_CHILD_DOCUMENT_MISSING", warnings);
            return Optional.empty();
        }
        try {
            return Optional.of(repository.mapper().readJson(document, type));
        } catch (RuntimeException failure) {
            warning(layout, document, "CASE_CHILD_DOCUMENT_INVALID", warnings);
            return Optional.empty();
        }
    }

    private static void warning(
            CaseArchiveLayout layout,
            Path document,
            String code,
            List<ArchiveWarning> warnings) {
        if (warnings.size() > MAX_ITEMS) return;
        warnings.add(new ArchiveWarning(
                code,
                "Case subdocument is unavailable; other readable facts were preserved",
                layout.caseRoot().relativize(document.toAbsolutePath().normalize())
                        .toString().replace('\\', '/')));
    }

    private static CaseId caseId(CaseArchiveLayout layout) {
        return new CaseId(layout.caseRoot().getFileName().toString());
    }

    private static int completedRunCount(List<RunEntry> runs) {
        return (int) runs.stream().filter(value -> value.outcome().isPresent()).count();
    }

    private static int incompleteRunCount(List<RunEntry> runs) {
        return (int) runs.stream().filter(value -> value.outcome().isEmpty()).count();
    }

    private static int completedCollectionCount(List<CollectionEntry> values) {
        return (int) values.stream().filter(value -> value.summary().isPresent()).count();
    }

    private static int completedEvidenceCount(List<EvidenceEntry> values) {
        return (int) values.stream().filter(value -> value.sufficiency().isPresent()).count();
    }

    private static String excerpt(String question) {
        return question.length() <= MAX_EXCERPT ? question : question.substring(0, MAX_EXCERPT);
    }

    private record RunEntry(RunRequest request, Optional<RunOutcomeSummary> outcome) {
    }

    private record AnalysisEntry(AnalysisRequest request) {
    }

    private record CollectionEntry(
            CollectionId collectionId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId,
            java.time.Instant createdAt,
            Optional<CollectionExecutionSummary> summary) {
        CollectionEntry withSummary(Optional<CollectionExecutionSummary> value) {
            return new CollectionEntry(collectionId, analysisId, createdAt, value);
        }
    }

    private record EvidenceEntry(
            EvidenceBuildRequest request, Optional<SufficiencyEvaluation> sufficiency) {
    }
}
