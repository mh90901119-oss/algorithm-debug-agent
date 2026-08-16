package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArchiveWarning;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.SchemaVersions;

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
            throw new IllegalArgumentException("repository 不能为空");
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
        List<ContextSnapshot> contexts = readContexts(layout, warnings);
        List<AnalysisRequest> analyses = readAnalyses(layout, warnings);
        List<RunEntry> runs = readRuns(layout, warnings);

        contexts.sort(Comparator.comparing(ContextSnapshot::createdAt)
                .thenComparing(value -> value.contextId().value()));
        analyses.sort(Comparator.comparing(AnalysisRequest::createdAt)
                .thenComparing(value -> value.analysisId().value()));
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
        AnalysisRequest latestAnalysis = analyses.isEmpty() ? null : analyses.getLast();
        String question = latestAnalysis == null
                ? manifest.initialQuestion() : latestAnalysis.question();
        boolean truncated = completedRunCount(runs) > MAX_ITEMS
                || incompleteRunCount(runs) > MAX_ITEMS || warnings.size() > MAX_ITEMS;

        return new CaseDigest(
                SchemaVersions.CASE_DIGEST,
                manifest.caseId(),
                manifest.projectId(),
                manifest.targetTest(),
                contexts.isEmpty() ? Optional.empty() : Optional.of(contexts.getLast().contextId()),
                latestAnalysis == null ? Optional.empty() : Optional.of(latestAnalysis.analysisId()),
                excerpt(question),
                completed.isEmpty() ? Optional.empty() : Optional.of(completed.getFirst().runId()),
                completed,
                incomplete,
                warnings.stream().limit(MAX_ITEMS).toList(),
                contexts.size(), analyses.size(), runs.size(), truncated);
    }

    private List<ContextSnapshot> readContexts(
            CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<ContextSnapshot> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.contextsRoot())) {
            Path document = directory.resolve("context.json");
            readChild(layout, document, ContextSnapshot.class, warnings).ifPresent(value -> {
                if (value.caseId().equals(caseId(layout))
                        && value.contextId().value().equals(directory.getFileName().toString())) {
                    values.add(value);
                } else {
                    warning(layout, document, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                }
            });
        }
        return values;
    }

    private List<AnalysisRequest> readAnalyses(
            CaseArchiveLayout layout, List<ArchiveWarning> warnings) {
        List<AnalysisRequest> values = new ArrayList<>();
        for (Path directory : repository.childDirectories(layout.analysesRoot())) {
            Path document = directory.resolve("analysis-request.json");
            readChild(layout, document, AnalysisRequest.class, warnings).ifPresent(value -> {
                if (value.caseId().equals(caseId(layout))
                        && value.analysisId().value().equals(directory.getFileName().toString())) {
                    values.add(value);
                } else {
                    warning(layout, document, "CASE_CHILD_IDENTITY_MISMATCH", warnings);
                }
            });
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
                        || !summary.contextId().equals(runRequest.contextId())
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
        warnings.add(new ArchiveWarning(
                code,
                "Case 子文档不可用，已保留其他可读取事实",
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

    private static String excerpt(String question) {
        return question.length() <= MAX_EXCERPT ? question : question.substring(0, MAX_EXCERPT);
    }

    private record RunEntry(RunRequest request, Optional<RunOutcomeSummary> outcome) {
    }
}
