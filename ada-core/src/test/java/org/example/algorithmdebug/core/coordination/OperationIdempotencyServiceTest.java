package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.OperationJournal;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationIdempotencyServiceTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final String INPUT_HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");

    @TempDir Path temporaryDirectory;
    private OperationIdempotencyService service;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        service = new OperationIdempotencyService(new OperationJournal(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter()));
    }

    @Test
    void completedOperationReturnsReplayedReceiptWithoutAnotherStart() {
        OperationIdempotencyService.Context context = context("operation-completed", INPUT_HASH);

        assertEquals(OperationIdempotencyService.Status.AVAILABLE, service.inspect(context).status());
        assertEquals(OperationIdempotencyService.Status.STARTED, service.claim(context, NOW).status());
        service.complete(context, Optional.of("artifact-1"), NOW.plusSeconds(1));

        OperationIdempotencyService.Inspection replay = service.inspect(context);

        assertEquals(OperationIdempotencyService.Status.REPLAYABLE, replay.status());
        assertEquals(ActionOutcome.SUCCEEDED, replay.receipt().orElseThrow().outcome());
        assertTrue(replay.receipt().orElseThrow().replayed());
        assertEquals(OperationIdempotencyService.Status.REPLAYABLE, service.claim(context, NOW).status());
    }

    @Test
    void inProgressAndUncertainOperationsAreNeverAvailableForAutomaticRetry() {
        OperationIdempotencyService.Context context = context("operation-uncertain", INPUT_HASH);
        service.claim(context, NOW);

        assertEquals(OperationIdempotencyService.Status.IN_PROGRESS, service.inspect(context).status());

        service.uncertain(context, NOW.plusSeconds(1));
        OperationIdempotencyService.Inspection uncertain = service.inspect(context);

        assertEquals(OperationIdempotencyService.Status.UNCERTAIN, uncertain.status());
        assertEquals(
                CoordinationErrorCode.OPERATION_UNCERTAIN,
                uncertain.receipt().orElseThrow().errorCode().orElseThrow());
    }

    @Test
    void reusingOperationIdWithDifferentInputIsAConflict() {
        OperationIdempotencyService.Context original = context("operation-conflict", INPUT_HASH);
        service.claim(original, NOW);

        OperationIdempotencyService.Inspection conflict = service.inspect(
                context("operation-conflict", "b".repeat(64)));

        assertEquals(OperationIdempotencyService.Status.CONFLICT, conflict.status());
        assertEquals(
                CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                conflict.errorCode().orElseThrow());
    }

    @Test
    void journalRouterUsesTheProjectFromEachAnalysisIdentity() throws Exception {
        Path projectOne = temporaryDirectory.resolve("project-1-cases");
        Path projectTwo = temporaryDirectory.resolve("project-2-cases");
        Files.createDirectories(projectOne);
        Files.createDirectories(projectTwo);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        OperationIdempotencyService routed = new OperationIdempotencyService(context ->
                new OperationJournal(
                        context.identity().projectId().value().equals("project-1")
                                ? projectOne : projectTwo,
                        mapper, writer));
        OperationIdempotencyService.Context first = context(
                IDENTITY, "operation-routed-1", INPUT_HASH);
        AnalysisIdentity secondIdentity = new AnalysisIdentity(
                new ProjectId("project-2"), new CaseId("case-2"),
                new AnalysisId("analysis-2"));
        OperationIdempotencyService.Context second = context(
                secondIdentity, "operation-routed-2", INPUT_HASH);

        routed.claim(first, NOW);
        routed.claim(second, NOW);

        assertTrue(Files.exists(projectOne.resolve(
                "case-1/analyses/analysis-1/operations/operation-routed-1/started.json")));
        assertTrue(Files.exists(projectTwo.resolve(
                "case-2/analyses/analysis-2/operations/operation-routed-2/started.json")));
    }

    private static OperationIdempotencyService.Context context(String operationId, String inputHash) {
        return context(IDENTITY, operationId, inputHash);
    }

    private static OperationIdempotencyService.Context context(
            AnalysisIdentity identity, String operationId, String inputHash) {
        return new OperationIdempotencyService.Context(
                new OperationId(operationId), identity, AnalysisActionType.RUN_TEST, inputHash);
    }
}
