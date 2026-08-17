package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshotStatus;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextSnapshotBuilderTest {

    @TempDir
    Path temporaryDirectory;

    private Path moduleRoot;
    private Path source;
    private Path input;

    @BeforeEach
    void setUp() throws Exception {
        moduleRoot = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(moduleRoot.resolve("pom.xml"), "<project/>");
        source = moduleRoot.resolve("src/main/java/a/b/Algorithm.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a.b; class Algorithm { int value = 1; }");
        Path test = moduleRoot.resolve("src/test/java/a/b/ScheduleTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package a.b; class ScheduleTest { }");
        input = moduleRoot.resolve("input/case.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{\"jobs\":1}");
    }

    @Test
    void shouldChangeFingerprintWhenAllowlistedSourceChanges() throws Exception {
        ContextSnapshotBuilder builder = new ContextSnapshotBuilder();
        ContextSnapshot first = builder.build(request(new ContextId("context-1")));
        Files.writeString(source, "package a.b; class Algorithm { int value = 2; }");

        ContextSnapshot second = builder.build(request(new ContextId("context-2")));

        assertNotEquals(first.sourceSnapshot().sha256(), second.sourceSnapshot().sha256());
        assertNotEquals(first.fingerprintSha256(), second.fingerprintSha256());
        assertEquals(SnapshotCompleteness.COMPLETE, second.completeness());
    }

    @Test
    void shouldIgnoreGeneratedTargetDirectory() throws Exception {
        ContextSnapshotBuilder builder = new ContextSnapshotBuilder();
        ContextSnapshot first = builder.build(request(new ContextId("context-1")));
        Path generated = moduleRoot.resolve("target/classes/a/b/Algorithm.class");
        Files.createDirectories(generated.getParent());
        Files.write(generated, new byte[]{1, 2, 3});

        ContextSnapshot second = builder.build(request(new ContextId("context-2")));

        assertEquals(first.fingerprintSha256(), second.fingerprintSha256());
    }

    @Test
    void shouldMarkSnapshotIncompleteWhenFileBudgetIsExceeded() {
        ContextSnapshotBuilder builder = new ContextSnapshotBuilder(
                1, 512L * 1024 * 1024, 16L * 1024 * 1024,
                Duration.ofSeconds(10), System::nanoTime);

        ContextSnapshot snapshot = builder.build(request(new ContextId("context-1")));

        assertEquals(SnapshotCompleteness.INCOMPLETE, snapshot.completeness());
        assertFalseEmpty(snapshot.warnings());
    }

    @Test
    void shouldRecordMissingInputAsObservedFact() throws Exception {
        Files.delete(input);
        ContextSnapshotRequest request = request(new ContextId("context-1"))
                .withInput(ContextInputProbe.missing("input/case.json", "input not found"));

        ContextSnapshot snapshot = new ContextSnapshotBuilder().build(request);

        assertEquals(InputSnapshotStatus.MISSING, snapshot.inputSnapshot().status());
        assertEquals(SnapshotCompleteness.COMPLETE, snapshot.completeness());
    }

    @Test
    void shouldRejectModuleOutsideDeclaredRepositoryRoot() throws Exception {
        Path unrelatedRepository = Files.createDirectory(temporaryDirectory.resolve("unrelated"));

        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshotRequest(
                new CaseId("case-1"), new ContextId("context-1"),
                new ProjectId("project-1"), new TargetTest("a.b.ScheduleTest", "case1"),
                moduleRoot, unrelatedRepository, "UNAVAILABLE", "21.0.4",
                "wafer-demo", "0.2.0",
                ContextInputProbe.present(input, "input/case.json"),
                Instant.parse("2026-08-16T00:00:00Z")));
    }

    private ContextSnapshotRequest request(ContextId contextId) {
        return new ContextSnapshotRequest(
                new CaseId("case-1"), contextId, new ProjectId("project-1"),
                new TargetTest("a.b.ScheduleTest", "case1"),
                moduleRoot, temporaryDirectory, "UNAVAILABLE", "21.0.4",
                "wafer-demo", "0.2.0",
                ContextInputProbe.present(input, "input/case.json"),
                Instant.parse("2026-08-16T00:00:00Z"));
    }

    private static void assertFalseEmpty(java.util.List<String> values) {
        assertFalse(values.isEmpty());
    }
}
