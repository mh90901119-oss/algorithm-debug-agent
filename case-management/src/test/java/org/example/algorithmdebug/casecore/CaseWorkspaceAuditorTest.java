package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseWorkspaceAuditorTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final CaseId CASE_ID = new CaseId("case-1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsKnownControlFilesAndVerifiedArtifacts() throws Exception {
        Fixture fixture = fixture();

        var audit = new CaseWorkspaceAuditor().audit(
                fixture.workspace(), PROJECT_ID, CASE_ID);

        assertTrue(audit.passed());
        assertTrue(audit.issues().isEmpty());
        assertTrue(audit.expectedArtifacts().contains("artifacts/gantt-1.json"));
        assertTrue(audit.expectedArtifacts().contains("gantt/result.json"));
    }

    @Test
    void acceptsCaseWithoutOptionalOpenCodeInteractionLog() throws Exception {
        Fixture fixture = fixture();
        Files.delete(fixture.caseRoot().resolve("interaction.jsonl"));

        var audit = new CaseWorkspaceAuditor().audit(
                fixture.workspace(), PROJECT_ID, CASE_ID);

        assertTrue(audit.passed());
        assertTrue(audit.issues().isEmpty());
        assertFalse(audit.expectedArtifacts().contains("interaction.jsonl"));
    }

    @Test
    void reportsCorruptionInvalidLogEmptyDirectoryAndUntrackedFile() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.gantt(), "{\"changed\":true}");
        Files.writeString(fixture.caseRoot().resolve("interaction.jsonl"), "not-json\n");
        Files.createDirectory(fixture.caseRoot().resolve("unused"));
        Files.writeString(fixture.caseRoot().resolve("orphan.txt"), "orphan");

        var audit = new CaseWorkspaceAuditor().audit(
                fixture.workspace(), PROJECT_ID, CASE_ID);

        assertFalse(audit.passed());
        assertTrue(audit.issues().stream().anyMatch(issue ->
                issue.code().startsWith("ARTIFACT_")
                        && issue.relativePath().equals("gantt/result.json")));
        assertTrue(audit.issues().stream().anyMatch(issue ->
                issue.code().equals("INTERACTION_LOG_INVALID")));
        assertTrue(audit.issues().stream().anyMatch(issue ->
                issue.code().equals("EMPTY_DIRECTORY")
                        && issue.relativePath().equals("unused")));
        assertTrue(audit.issues().stream().anyMatch(issue ->
                issue.code().equals("UNTRACKED_FILE")
                        && issue.relativePath().equals("orphan.txt")));
    }

    @Test
    void acceptsFailedCollectionDiagnosticControlsWithoutArtifactRegistrations() throws Exception {
        Fixture fixture = fixture();
        Path collection = fixture.caseRoot().resolve("collections/collection-1");
        Files.createDirectories(collection.resolve("logs"));
        Files.createDirectories(collection.resolve("validation"));
        Files.writeString(collection.resolve("collection-request.json"), "{}");
        Files.writeString(collection.resolve("manifest.json"), "{}");
        Files.writeString(collection.resolve("logs/stdout.log"), "launcher output");
        Files.writeString(collection.resolve("logs/stderr.log"), "launcher failure");
        Files.writeString(collection.resolve("validation/baseline-check.json"), "{}");

        var audit = new CaseWorkspaceAuditor().audit(
                fixture.workspace(), PROJECT_ID, CASE_ID);

        assertTrue(audit.passed());
        assertTrue(audit.issues().isEmpty());
    }

    private Fixture fixture() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path casesRoot = workspace.resolve("projects/project-1/cases");
        Path caseRoot = Files.createDirectories(casesRoot.resolve("case-1"));
        Files.writeString(caseRoot.resolve("case.json"), "{}");
        Files.writeString(caseRoot.resolve("interaction.jsonl"), "{\"event\":\"case.opened\"}\n");
        Path gantt = caseRoot.resolve("gantt/result.json");
        Files.createDirectories(gantt.getParent());
        Files.writeString(gantt, "{\"tasks\":[1,2,3]}");
        var reference = new CaseArtifactAccess(casesRoot).describe(
                CASE_ID, "gantt-1", "GANTT", "application/json", gantt);
        var registration = new CaseArtifactRegistration(
                SchemaVersions.CASE_ARTIFACT_REGISTRATION, CASE_ID, reference, Instant.EPOCH);
        Path registrations = Files.createDirectory(caseRoot.resolve("artifacts"));
        Files.write(registrations.resolve("gantt-1.json"),
                new BoundedDocumentMapper().writeJson(registration));
        return new Fixture(workspace, caseRoot, gantt);
    }

    private record Fixture(Path workspace, Path caseRoot, Path gantt) {
    }
}
