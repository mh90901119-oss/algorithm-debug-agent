package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinationDecisionArchiveTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final String INPUT_HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @TempDir Path temporaryDirectory;
    private CoordinationDecisionArchive archive;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        archive = new CoordinationDecisionArchive(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    @Test
    void appendsAuditableDecisionWithHashAndProvenance() {
        ActionDecision decision = decision();

        Path path = archive.appendDecision(
                "decision-1", INPUT_HASH, List.of("operation:operation-1"), NOW, decision);
        CoordinationDecisionArchive.ArchivedDecision actual = archive.requireDecision(
                IDENTITY, "decision-1");

        assertEquals("decision-1.json", path.getFileName().toString());
        assertEquals(decision, actual.decision());
        assertEquals(INPUT_HASH, actual.inputSha256());
        assertEquals(List.of("operation:operation-1"), actual.provenance());
        assertEquals(64, actual.contentSha256().length());
    }

    @Test
    void duplicateDecisionIdCannotOverwriteHistory() {
        archive.appendDecision(
                "decision-1", INPUT_HASH, List.of("operation:operation-1"), NOW, decision());

        assertThrows(WorkspaceException.class, () -> archive.appendDecision(
                "decision-1", INPUT_HASH, List.of("operation:operation-2"),
                NOW.plusSeconds(1), decision()));
    }

    @Test
    void decisionRequiresNonEmptyProvenance() {
        assertThrows(IllegalArgumentException.class, () -> archive.appendDecision(
                "decision-1", INPUT_HASH, List.of(), NOW, decision()));
    }

    @Test
    void derivesTheApprovedControlArchiveLayout() {
        CaseArchiveLayout layout = CaseArchiveLayout.of(
                temporaryDirectory.resolve("cases"), IDENTITY.caseId());

        assertEquals(
                layout.analysisRoot(IDENTITY.analysisId()).resolve("operations/operation-1/started.json"),
                layout.operationStarted(
                        IDENTITY.analysisId(), new OperationId("operation-1")));
        assertEquals(
                layout.analysisRoot(IDENTITY.analysisId()).resolve("coordination/decision-1.json"),
                layout.coordinationDecision(IDENTITY.analysisId(), "decision-1"));
        assertEquals(
                layout.analysisRoot(IDENTITY.analysisId()).resolve("investigation/events/1-event-1.json"),
                layout.investigationEvent(IDENTITY.analysisId(), 1, "event-1"));
        assertEquals(
                layout.analysisRoot(IDENTITY.analysisId()).resolve("source-queries/query-1/request.json"),
                layout.sourceQueryRequest(IDENTITY.analysisId(), new SourceQueryId("query-1")));
        assertEquals(
                layout.analysisRoot(IDENTITY.analysisId()).resolve("conclusions/conclusion-1/accepted.json"),
                layout.conclusionAccepted(IDENTITY.analysisId(), "conclusion-1"));
    }

    private static ActionDecision decision() {
        return new ActionDecision(
                SchemaVersions.ACTION_DECISION,
                "1.0",
                IDENTITY,
                1,
                AnalysisActionType.RUN_TEST,
                ActionDecisionCode.ALLOWED,
                ActionSideEffect.TARGET_EXECUTION,
                List.of());
    }
}
