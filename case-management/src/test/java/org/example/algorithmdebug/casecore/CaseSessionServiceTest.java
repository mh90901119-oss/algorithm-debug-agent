package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseSessionServiceTest {

    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private Path moduleRoot;
    private Path source;
    private Path input;
    private CaseArchiveRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        moduleRoot = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(moduleRoot.resolve("pom.xml"), "<project/>");
        source = moduleRoot.resolve("src/main/java/a/b/Algorithm.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a.b; class Algorithm { int value = 1; }");
        input = moduleRoot.resolve("input/case.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}");
    }

    @Test
    void shouldCreateCaseContextAndAnalysisWithoutRunningMaven() {
        CaseSessionService service = service("1", "1", "1");

        CaseOpenResult result = service.open(request(Optional.empty(), "问题一"));

        assertTrue(result.caseCreated());
        assertTrue(result.contextChanged());
        assertEquals("case-1", result.caseId().value());
        assertEquals("context-1", result.contextId().value());
        assertEquals("analysis-1", result.analysisId().value());
        assertEquals(0, result.digest().runCount());
        assertEquals("问题一", repository.requireCase(result.caseId()).initialQuestion());
    }

    @Test
    void shouldReuseCompleteContextAndAppendAnalysis() {
        CaseSessionService service = service(
                "1", "1", "1", "unused", "2");
        CaseOpenResult first = service.open(request(Optional.empty(), "问题一"));

        CaseOpenResult second = service.open(request(Optional.of(first.caseId()), "继续追问"));

        assertEquals(first.contextId(), second.contextId());
        assertTrue(!second.contextChanged());
        assertNotEquals(first.analysisId(), second.analysisId());
        assertEquals(1, second.digest().contextCount());
        assertEquals(2, second.digest().analysisCount());
    }

    @Test
    void shouldAppendContextWhenSourceChanges() throws Exception {
        CaseSessionService service = service(
                "1", "1", "1", "2", "2");
        CaseOpenResult first = service.open(request(Optional.empty(), "问题一"));
        Files.writeString(source, "package a.b; class Algorithm { int value = 2; }");

        CaseOpenResult second = service.open(request(Optional.of(first.caseId()), "代码改了以后呢"));

        assertNotEquals(first.contextId(), second.contextId());
        assertTrue(second.contextChanged());
        assertEquals(2, second.digest().contextCount());
    }

    @Test
    void shouldRejectDifferentTargetTestForExistingCase() {
        CaseSessionService service = service("1", "1", "1");
        CaseOpenResult first = service.open(request(Optional.empty(), "问题一"));
        CaseSessionRequest mismatch = new CaseSessionRequest(
                Optional.of(first.caseId()), new ProjectId("project-1"),
                new TargetTest("a.b.ScheduleTest", "case2"), "另一个测试",
                moduleRoot, temporaryDirectory, "UNAVAILABLE", "21.0.4",
                "wafer-demo", "0.2.0", ContextInputProbe.present(input, "input/case.json"));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> service.open(mismatch));

        assertEquals("CASE_TARGET_TEST_MISMATCH", failure.code());
    }

    @Test
    void shouldNeverReuseIncompleteContext() throws Exception {
        ContextSnapshotBuilder limitedBuilder = new ContextSnapshotBuilder(
                0, 512L * 1024 * 1024, 16L * 1024 * 1024,
                java.time.Duration.ofSeconds(10), System::nanoTime);
        CaseSessionService service = serviceWithBuilder(limitedBuilder,
                "1", "1", "1", "2", "2");
        CaseOpenResult first = service.open(request(Optional.empty(), "问题一"));

        CaseOpenResult second = service.open(request(Optional.of(first.caseId()), "继续"));

        assertEquals(SnapshotCompleteness.INCOMPLETE,
                repository.requireContext(first.caseId(), first.contextId()).completeness());
        assertNotEquals(first.contextId(), second.contextId());
        assertTrue(second.contextChanged());
    }

    private CaseSessionRequest request(Optional<CaseId> caseId, String question) {
        return new CaseSessionRequest(
                caseId, new ProjectId("project-1"),
                new TargetTest("a.b.ScheduleTest", "case1"), question,
                moduleRoot, temporaryDirectory, "UNAVAILABLE", "21.0.4",
                "wafer-demo", "0.2.0", ContextInputProbe.present(input, "input/case.json"));
    }

    private CaseSessionService service(String... ids) {
        return serviceWithBuilder(new ContextSnapshotBuilder(), ids);
    }

    private CaseSessionService serviceWithBuilder(ContextSnapshotBuilder builder, String... ids) {
        ArrayDeque<String> values = new ArrayDeque<>(List.of(ids));
        return new CaseSessionService(
                repository,
                new CaseDigestReader(repository),
                builder,
                new OpaqueIdGenerator(values::removeFirst),
                Clock.fixed(TIME, ZoneOffset.UTC));
    }
}
