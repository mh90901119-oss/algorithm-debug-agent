package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationJournalTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final OperationId OPERATION_ID = new OperationId("operation-1");
    private static final String INPUT_HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @TempDir Path temporaryDirectory;
    private Path casesRoot;
    private OperationJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        journal = new OperationJournal(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    @Test
    void operationAllowsStartedAndExactlyOneTerminalDocument() {
        assertTrue(journal.findState(IDENTITY, OPERATION_ID).isEmpty());
        Path started = journal.start(
                IDENTITY, OPERATION_ID, AnalysisActionType.RUN_TEST, INPUT_HASH, NOW);
        Path completed = journal.complete(receipt(ActionOutcome.SUCCEEDED, Optional.empty()), NOW.plusSeconds(1));

        OperationJournal.State state = journal.requireState(IDENTITY, OPERATION_ID);

        assertTrue(Files.isRegularFile(started));
        assertTrue(Files.isRegularFile(completed));
        assertTrue(journal.findState(IDENTITY, OPERATION_ID).isPresent());
        assertEquals(OperationJournal.TerminalKind.COMPLETED, state.terminal().orElseThrow().kind());
        assertEquals(INPUT_HASH, state.started().inputSha256());
    }

    @Test
    void secondTerminalDocumentIsRejected() {
        journal.start(IDENTITY, OPERATION_ID, AnalysisActionType.RUN_TEST, INPUT_HASH, NOW);
        journal.complete(receipt(ActionOutcome.SUCCEEDED, Optional.empty()), NOW.plusSeconds(1));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> journal.fail(
                        receipt(ActionOutcome.FAILED, Optional.of(
                                CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED)),
                        NOW.plusSeconds(2)));

        assertEquals("OPERATION_JOURNAL_CONFLICT", failure.code());
    }

    @Test
    void terminalMustMatchStartedIdentityActionAndInputHash() {
        journal.start(IDENTITY, OPERATION_ID, AnalysisActionType.RUN_TEST, INPUT_HASH, NOW);
        OperationReceipt mismatched = new OperationReceipt(
                SchemaVersions.OPERATION_RECEIPT,
                OPERATION_ID,
                IDENTITY,
                AnalysisActionType.CODEPATH_COLLECT,
                ActionOutcome.FAILED,
                INPUT_HASH,
                false,
                Optional.empty(),
                Optional.of(CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> journal.fail(mismatched, NOW.plusSeconds(1)));

        assertEquals("OPERATION_JOURNAL_CONFLICT", failure.code());
    }

    @Test
    void uncertainTerminalUsesDedicatedDocumentAndErrorCode() {
        journal.start(IDENTITY, OPERATION_ID, AnalysisActionType.RUN_TEST, INPUT_HASH, NOW);
        Path uncertain = journal.markUncertain(
                receipt(ActionOutcome.FAILED, Optional.of(CoordinationErrorCode.OPERATION_UNCERTAIN)),
                NOW.plusSeconds(1));

        assertEquals("uncertain.json", uncertain.getFileName().toString());
        assertEquals(
                OperationJournal.TerminalKind.UNCERTAIN,
                journal.requireState(IDENTITY, OPERATION_ID).terminal().orElseThrow().kind());
    }

    @Test
    void orphanTerminalWithoutStartedDocumentIsRejectedAsConflict() throws Exception {
        Path orphan = CaseArchiveLayout.of(casesRoot, IDENTITY.caseId())
                .operationCompleted(IDENTITY.analysisId(), OPERATION_ID);
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "{}");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> journal.findState(IDENTITY, OPERATION_ID));

        assertEquals("OPERATION_JOURNAL_CONFLICT", failure.code());
    }

    private static OperationReceipt receipt(
            ActionOutcome outcome, Optional<CoordinationErrorCode> errorCode) {
        return new OperationReceipt(
                SchemaVersions.OPERATION_RECEIPT,
                OPERATION_ID,
                IDENTITY,
                AnalysisActionType.RUN_TEST,
                outcome,
                INPUT_HASH,
                false,
                Optional.empty(),
                errorCode);
    }
}
