package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AlgorithmInputCapture;
import org.example.algorithmdebug.contracts.AlgorithmInputComparison;
import org.example.algorithmdebug.contracts.AlgorithmInputPathKind;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisArchiveReaderTest {
    private static final ProjectId PROJECT = new ProjectId("project-1");
    private static final CaseId CASE = new CaseId("case-1");
    private static final AnalysisId ANALYSIS = new AnalysisId("analysis-1");
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(PROJECT, CASE, ANALYSIS);
    private static final TargetTest TARGET = new TargetTest("a.b.ScheduleTest", "case1");
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void distinguishesAbsentInitializedAndHalfInitializedAnalyses() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        AnalysisArchiveReader reader = new AnalysisArchiveReader(
                casesRoot, new BoundedDocumentMapper());
        assertEquals(AnalysisArchiveSnapshot.Initialization.ABSENT,
                reader.read(IDENTITY).initialization());

        Files.createDirectories(casesRoot);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        CaseArchiveRepository repository = new CaseArchiveRepository(casesRoot, mapper, writer);
        createInitializedSession(repository);
        assertEquals(AnalysisArchiveSnapshot.Initialization.INITIALIZED,
                new AnalysisArchiveReader(casesRoot, mapper).read(IDENTITY).initialization());

        AnalysisIdentity half = new AnalysisIdentity(
                PROJECT, CASE, new AnalysisId("analysis-half"));
        repository.createAnalysis(new org.example.algorithmdebug.contracts.AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE, half.analysisId(), "half", NOW));
        assertEquals(AnalysisArchiveSnapshot.Initialization.INVALID,
                new AnalysisArchiveReader(casesRoot, mapper).read(half).initialization());
    }

    @Test
    void indexesRegisteredTypedControlArtifactsWithoutOpeningRawTrace() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        CaseArchiveRepository repository = new CaseArchiveRepository(casesRoot, mapper, writer);
        createInitializedSession(repository);

        Path input = repository.caseRoot(CASE).resolve("input/input.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{\"jobs\":[]}");
        ArtifactReference inputArtifact = artifact(
                input, repository.caseRoot(CASE), "algorithm-input", "ALGORITHM_INPUT");
        repository.createAlgorithmInputCapture(new AlgorithmInputCapture(
                SchemaVersions.ALGORITHM_INPUT_CAPTURE, CASE, ANALYSIS, TARGET,
                "inputPath", "src/test/java/a/b/ScheduleTest.java", 12,
                AlgorithmInputPathKind.RELATIVE, "input.json",
                AlgorithmInputComparison.FIRST_CAPTURE, Optional.empty(), inputArtifact, NOW));
        repository.registerArtifact(CASE, inputArtifact, NOW);

        Path catalog = repository.createMethodCatalog(CaseArchiveRepositoryTest.methodCatalog());
        ArtifactReference catalogArtifact = artifact(
                catalog, repository.caseRoot(CASE), "analysis-1-method-catalog", "METHOD_CATALOG");
        repository.registerArtifact(CASE, catalogArtifact, NOW);

        Path raw = repository.caseRoot(CASE).resolve("runs/run-unowned/raw/broken.json");
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "not-json-and-not-a-control-document");

        AnalysisArchiveSnapshot snapshot = new AnalysisArchiveReader(casesRoot, mapper)
                .read(IDENTITY);

        assertEquals(AnalysisArchiveSnapshot.Initialization.INITIALIZED,
                snapshot.initialization());
        assertEquals(List.of("ALGORITHM_INPUT", "METHOD_CATALOG"),
                snapshot.artifactIndex().forAnalysis(IDENTITY).stream()
                        .map(value -> value.artifact().artifactType()).sorted().toList());
        assertTrue(Files.exists(raw));
    }

    @Test
    void rejectsSourceQueryResultThatHasNoImmutableRequest() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        CaseArchiveRepository repository = new CaseArchiveRepository(
                casesRoot, mapper, new AtomicDocumentWriter());
        createInitializedSession(repository);

        SourceQueryId queryId = new SourceQueryId("query-forged");
        SourceQueryResult forged = new SourceQueryResult(
                SchemaVersions.SOURCE_QUERY_RESULT,
                queryId,
                CASE,
                ANALYSIS,
                SourceQueryMode.METHOD,
                SourceQueryCompleteness.COMPLETE,
                new ArtifactReference(
                        "catalog-forged",
                        "METHOD_CATALOG",
                        "analyses/analysis-1/method-catalog.json",
                        "application/json",
                        "0".repeat(64),
                        0),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                NOW);
        Path result = repository.caseRoot(CASE)
                .resolve("analyses/analysis-1/source-queries")
                .resolve(queryId.value())
                .resolve("result.json");
        Files.createDirectories(result.getParent());
        Files.write(result, mapper.writeJson(forged));

        assertEquals(
                AnalysisArchiveSnapshot.Initialization.INVALID,
                new AnalysisArchiveReader(casesRoot, mapper).read(IDENTITY).initialization());
    }

    private void createInitializedSession(CaseArchiveRepository repository) {
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("problem-1"), CASE, ANALYSIS,
                "输出顺序错误", "按优先级排序", "断言失败", TARGET,
                List.of(new SourceAnchor(
                        "a.b.Scheduler", "schedule", "()V",
                        "src/main/java/a/b/Scheduler.java", 1, 20)),
                List.of(), List.of("候选选择是否错误"), NOW);
        new CaseSessionService(
                repository, new CaseDigestReader(repository),
                new OpaqueIdGenerator(() -> "unused"),
                Clock.fixed(NOW, ZoneOffset.UTC)).open(
                CaseSessionRequest.initialized(
                        Optional.empty(), PROJECT, "adapter-1", frame));
    }

    private static ArtifactReference artifact(
            Path file, Path caseRoot, String id, String type) throws Exception {
        return new ArtifactReference(
                id, type, caseRoot.relativize(file).toString().replace('\\', '/'),
                "application/json",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file))),
                Files.size(file));
    }
}
