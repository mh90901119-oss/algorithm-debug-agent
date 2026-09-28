package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.OperationJournal;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisCoordinatorTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final String INPUT_HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration TEST_TERMINATION_TIMEOUT = Duration.ofSeconds(3);

    @TempDir Path temporaryDirectory;
    private OperationJournal journal;
    private OperationIdempotencyService idempotency;
    private List<ActionDecision> archivedDecisions;
    private AtomicInteger decisionSequence;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        journal = new OperationJournal(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        idempotency = new OperationIdempotencyService(journal);
        archivedDecisions = new ArrayList<>();
        decisionSequence = new AtomicInteger();
    }

    @Test
    void rejectedPolicyNeverInvokesHandlerOrWritesStarted() {
        AtomicInteger handlerCalls = new AtomicInteger();
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.CASE_AUDIT,
                ActionSideEffect.CASE_WRITE,
                policy(false, true),
                (target, input, cancellation) -> {
                    handlerCalls.incrementAndGet();
                    return input;
                });
        AnalysisActionRequest<String> request = request(
                AnalysisActionType.CASE_AUDIT, "operation-rejected", "input");

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), noLockManager()).execute(request);

        assertEquals(ActionOutcome.REJECTED, result.outcome());
        assertEquals(CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED.name(), result.code());
        assertEquals(0, handlerCalls.get());
        assertTrue(journal.findState(IDENTITY, new OperationId("operation-rejected")).isEmpty());
        assertEquals(1, archivedDecisions.size());
    }

    @Test
    void sideEffectActionWithoutOperationIdIsRejectedBeforeExecution() {
        AtomicInteger handlerCalls = new AtomicInteger();
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.CASE_AUDIT,
                ActionSideEffect.CASE_WRITE,
                policy(true, true),
                (target, input, cancellation) -> {
                    handlerCalls.incrementAndGet();
                    return input;
                });

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), noLockManager()).execute(
                        requestWithoutOperation(AnalysisActionType.CASE_AUDIT, "input"));

        assertEquals(ActionOutcome.REJECTED, result.outcome());
        assertEquals(CoordinationErrorCode.ACTION_OPERATION_ID_REQUIRED.name(), result.code());
        assertEquals(0, handlerCalls.get());
    }

    @Test
    void targetActionAcquiresLockThenReprojectsAndStartsBeforeExecution() {
        List<String> events = new ArrayList<>();
        AtomicInteger projections = new AtomicInteger();
        AnalysisCoordinator.StateSource stateSource = ignored -> {
            int count = projections.incrementAndGet();
            events.add("project-" + count);
            return control(count);
        };
        WorkspaceExecutionLockManager locks = new WorkspaceExecutionLockManager(
                (projectId, timeout) -> {
                    events.add("lock");
                    return () -> events.add("unlock");
                }, LOCK_TIMEOUT);
        AnalysisActionHandler<String, String> handler = (target, input, cancellation) -> {
            events.add("handler");
            assertEquals(
                    OperationIdempotencyService.Status.IN_PROGRESS,
                    idempotency.inspect(context(
                            "operation-target", AnalysisActionType.RUN_TEST)).status());
            return "result";
        };
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                recordingPolicy(events, true),
                handler);

        CoordinatedToolResult<?> result = coordinator(binding, stateSource, locks).execute(
                request(AnalysisActionType.RUN_TEST, "operation-target", "input"));

        assertEquals(ActionOutcome.SUCCEEDED, result.outcome());
        assertTrue(events.indexOf("lock") < events.indexOf("project-2"));
        assertTrue(events.indexOf("project-2") < events.indexOf("handler"));
        assertTrue(events.indexOf("handler") < events.indexOf("project-3"));
        assertTrue(events.indexOf("project-3") < events.indexOf("unlock"));
        assertEquals(
                OperationJournal.TerminalKind.COMPLETED,
                journal.requireState(IDENTITY, new OperationId("operation-target"))
                        .terminal().orElseThrow().kind());
    }

    @Test
    void completedOperationIdReturnsPriorReceiptWithoutReexecutionOrLocking() {
        OperationIdempotencyService.Context context = context(
                "operation-completed", AnalysisActionType.RUN_TEST);
        idempotency.claim(context, NOW);
        idempotency.complete(context, Optional.of("artifact-1"), NOW.plusSeconds(1));
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger lockCalls = new AtomicInteger();
        WorkspaceExecutionLockManager locks = new WorkspaceExecutionLockManager(
                (projectId, timeout) -> {
                    lockCalls.incrementAndGet();
                    return () -> { };
                }, LOCK_TIMEOUT);
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> {
                    handlerCalls.incrementAndGet();
                    return input;
                });

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), locks).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-completed", "input"));

        assertEquals(ActionOutcome.SUCCEEDED, result.outcome());
        OperationReceipt receipt = (OperationReceipt) result.data();
        assertTrue(receipt.replayed());
        assertEquals(0, handlerCalls.get());
        assertEquals(0, lockCalls.get());
    }

    @Test
    void stateChangeWhileWaitingForTargetLockIsReauthorizedBeforeStarted() {
        AtomicInteger projections = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        AnalysisCoordinator.StateSource source = ignored ->
                projections.incrementAndGet() == 1 ? control(1) : rejectedControl(2);
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> {
                    handlerCalls.incrementAndGet();
                    return input;
                });

        CoordinatedToolResult<?> result = coordinator(binding, source, noLockManager()).execute(
                request(AnalysisActionType.RUN_TEST, "operation-state-changed", "input"));

        assertEquals(ActionOutcome.REJECTED, result.outcome());
        assertEquals(CoordinationErrorCode.COORDINATION_STATE_INVALID.name(), result.code());
        assertEquals(0, handlerCalls.get());
        assertTrue(journal.findState(
                IDENTITY, new OperationId("operation-state-changed")).isEmpty());
    }

    @Test
    void targetLockBusyIsStructuredRejectionWithoutStartedOperation() {
        WorkspaceExecutionLockManager busyLocks = new WorkspaceExecutionLockManager(
                (projectId, timeout) -> {
                    throw new WorkspaceException(
                            CoordinationErrorCode.TARGET_EXECUTION_BUSY.name(), "busy");
                }, LOCK_TIMEOUT);
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> input);

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), busyLocks).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-busy", "input"));

        assertEquals(ActionOutcome.REJECTED, result.outcome());
        assertEquals(CoordinationErrorCode.TARGET_EXECUTION_BUSY.name(), result.code());
        assertTrue(journal.findState(IDENTITY, new OperationId("operation-busy")).isEmpty());
    }

    @Test
    void uncertainOperationIsNeverAutomaticallyRetried() {
        OperationIdempotencyService.Context context = context(
                "operation-uncertain", AnalysisActionType.RUN_TEST);
        idempotency.claim(context, NOW);
        idempotency.uncertain(context, NOW.plusSeconds(1));
        AtomicInteger handlerCalls = new AtomicInteger();
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> {
                    handlerCalls.incrementAndGet();
                    return input;
                });

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), noLockManager()).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-uncertain", "input"));

        assertEquals(ActionOutcome.FAILED, result.outcome());
        assertEquals(CoordinationErrorCode.OPERATION_UNCERTAIN.name(), result.code());
        assertEquals(0, handlerCalls.get());
    }

    @Test
    void handlerFailurePreservesCauseAndWritesFailedTerminal() {
        IllegalStateException rootCause = new IllegalStateException("collector failed");
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.CASE_AUDIT,
                ActionSideEffect.CASE_WRITE,
                policy(true, true),
                (target, input, cancellation) -> {
                    throw rootCause;
                });
        AnalysisActionRequest<String> request = request(
                AnalysisActionType.CASE_AUDIT, "operation-handler-failed", "input");

        AnalysisCoordinator.ExecutionFailure failure = assertThrows(
                AnalysisCoordinator.ExecutionFailure.class,
                () -> coordinator(binding, ignored -> control(1), noLockManager()).execute(request));

        assertSame(rootCause, failure.getCause());
        assertEquals(CoordinationErrorCode.ACTION_EXECUTION_FAILED, failure.code());
        OperationReceipt receipt = journal.requireState(
                IDENTITY, new OperationId("operation-handler-failed"))
                .terminal().orElseThrow().receipt();
        assertEquals(CoordinationErrorCode.ACTION_EXECUTION_FAILED, receipt.errorCode().orElseThrow());
    }

    @Test
    void cancellationRequestsBoundedTerminationAndWritesFailedTerminal() {
        AtomicReference<Duration> requestedTimeout = new AtomicReference<>();
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> {
                    cancellation.onCancellation(requestedTimeout::set);
                    cancellation.requestCancellation();
                    cancellation.throwIfCancellationRequested();
                    return input;
                });
        ActionCancellation cancellation = new ActionCancellation(TEST_TERMINATION_TIMEOUT);

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), noLockManager()).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-cancelled", "input"),
                        cancellation);

        assertEquals(ActionOutcome.FAILED, result.outcome());
        assertEquals(CoordinationErrorCode.ACTION_CANCELLED.name(), result.code());
        assertEquals(TEST_TERMINATION_TIMEOUT, requestedTimeout.get());
        OperationReceipt receipt = journal.requireState(
                IDENTITY, new OperationId("operation-cancelled"))
                .terminal().orElseThrow().receipt();
        assertEquals(CoordinationErrorCode.ACTION_CANCELLED, receipt.errorCode().orElseThrow());
    }

    @Test
    void postconditionFailureReturnsFailedAndNeverArchivesSuccessTerminal() {
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.CASE_AUDIT,
                ActionSideEffect.CASE_WRITE,
                policy(true, false),
                (target, input, cancellation) -> "result");

        CoordinatedToolResult<?> result = coordinator(
                binding, ignored -> control(1), noLockManager()).execute(
                        request(AnalysisActionType.CASE_AUDIT, "operation-post-failed", "input"));

        assertEquals(ActionOutcome.FAILED, result.outcome());
        assertEquals(CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED.name(), result.code());
        OperationJournal.TerminalDocument terminal = journal.requireState(
                IDENTITY, new OperationId("operation-post-failed"))
                .terminal().orElseThrow();
        assertEquals(OperationJournal.TerminalKind.FAILED, terminal.kind());
        assertFalse(terminal.receipt().replayed());
        assertEquals(2, archivedDecisions.size());
    }

    @Test
    void postExecutionProjectionFailureMarksOperationUncertain() {
        AtomicInteger projections = new AtomicInteger();
        AnalysisCoordinator.StateSource source = ignored -> {
            if (projections.incrementAndGet() == 3) {
                throw new IllegalStateException("archive unavailable");
            }
            return control(projections.get());
        };
        AnalysisActionBinding<String, String> binding = binding(
                AnalysisActionType.RUN_TEST,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> "result");

        AnalysisCoordinator.ExecutionFailure failure = assertThrows(
                AnalysisCoordinator.ExecutionFailure.class,
                () -> coordinator(binding, source, noLockManager()).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-projection-failed", "input")));

        assertEquals(CoordinationErrorCode.OPERATION_UNCERTAIN, failure.code());
        OperationJournal.TerminalDocument terminal = journal.requireState(
                IDENTITY, new OperationId("operation-projection-failed"))
                .terminal().orElseThrow();
        assertEquals(OperationJournal.TerminalKind.UNCERTAIN, terminal.kind());
    }

    @Test
    void invalidResultMappingAfterExecutionMarksOperationUncertain() {
        AnalysisActionBinding<String, String> binding = new AnalysisActionBinding<>(
                AnalysisActionType.RUN_TEST,
                String.class,
                ActionSideEffect.TARGET_EXECUTION,
                policy(true, true),
                (target, input, cancellation) -> "result",
                result -> {
                    throw new IllegalArgumentException("invalid result metadata");
                });

        AnalysisCoordinator.ExecutionFailure failure = assertThrows(
                AnalysisCoordinator.ExecutionFailure.class,
                () -> coordinator(binding, ignored -> control(1), noLockManager()).execute(
                        request(AnalysisActionType.RUN_TEST, "operation-mapping-failed", "input")));

        assertEquals(CoordinationErrorCode.OPERATION_UNCERTAIN, failure.code());
        assertEquals(
                OperationJournal.TerminalKind.UNCERTAIN,
                journal.requireState(IDENTITY, new OperationId("operation-mapping-failed"))
                        .terminal().orElseThrow().kind());
    }

    private AnalysisCoordinator coordinator(
            AnalysisActionBinding<String, String> binding,
            AnalysisCoordinator.StateSource stateSource,
            WorkspaceExecutionLockManager lockManager) {
        return new AnalysisCoordinator(
                AnalysisActionRegistry.of(List.of(binding)),
                stateSource,
                idempotency,
                lockManager,
                (decisionId, inputHash, provenance, decidedAt, decision) ->
                        archivedDecisions.add(decision),
                ignored -> INPUT_HASH,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "decision-" + decisionSequence.incrementAndGet());
    }

    private static WorkspaceExecutionLockManager noLockManager() {
        return new WorkspaceExecutionLockManager(
                (projectId, timeout) -> () -> { }, LOCK_TIMEOUT);
    }

    private static AnalysisActionBinding<String, String> binding(
            AnalysisActionType actionType,
            ActionSideEffect sideEffect,
            AnalysisActionPolicy<String, String> policy,
            AnalysisActionHandler<String, String> handler) {
        return new AnalysisActionBinding<>(
                actionType,
                String.class,
                sideEffect,
                policy,
                handler,
                result -> AnalysisActionBinding.ResultMetadata.withoutArtifacts(
                        actionType.name() + "_COMPLETED", "Action completed"));
    }

    private static AnalysisActionPolicy<String, String> policy(
            boolean authorize, boolean verify) {
        return new AnalysisActionPolicy<>() {
            @Override
            public ActionDecision authorize(
                    AnalysisControlView before, AnalysisActionRequest<String> request) {
                return decision(before, request.actionType(), sideEffect(request.actionType()), authorize);
            }

            @Override
            public ActionDecision verify(
                    AnalysisControlView before,
                    AnalysisActionRequest<String> request,
                    String result,
                    AnalysisControlView after) {
                return decision(after, request.actionType(), sideEffect(request.actionType()), verify);
            }
        };
    }

    private static AnalysisActionPolicy<String, String> recordingPolicy(
            List<String> events, boolean verify) {
        return new AnalysisActionPolicy<>() {
            private int authorizationCount;

            @Override
            public ActionDecision authorize(
                    AnalysisControlView before, AnalysisActionRequest<String> request) {
                authorizationCount++;
                events.add("authorize-" + authorizationCount);
                return decision(before, request.actionType(), ActionSideEffect.TARGET_EXECUTION, true);
            }

            @Override
            public ActionDecision verify(
                    AnalysisControlView before,
                    AnalysisActionRequest<String> request,
                    String result,
                    AnalysisControlView after) {
                events.add("verify");
                return decision(
                        after, request.actionType(), ActionSideEffect.TARGET_EXECUTION, verify);
            }
        };
    }

    private static ActionSideEffect sideEffect(AnalysisActionType actionType) {
        return actionType == AnalysisActionType.RUN_TEST
                ? ActionSideEffect.TARGET_EXECUTION
                : ActionSideEffect.CASE_WRITE;
    }

    private static ActionDecision decision(
            AnalysisControlView view,
            AnalysisActionType actionType,
            ActionSideEffect sideEffect,
            boolean allowed) {
        return new ActionDecision(
                SchemaVersions.ACTION_DECISION,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                view.revision(),
                actionType,
                allowed ? ActionDecisionCode.ALLOWED : ActionDecisionCode.REJECTED,
                sideEffect,
                allowed ? List.of() : List.of(CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED));
    }

    private static AnalysisControlView control(long revision) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                revision,
                AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.ALLOWED,
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(AnalysisActionType.CASE_AUDIT, AnalysisActionType.RUN_TEST),
                ConclusionStatus.MISSING_EVIDENCE);
    }

    private static AnalysisControlView rejectedControl(long revision) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                revision,
                AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.REJECTED,
                List.of(CoordinationErrorCode.COORDINATION_STATE_INVALID),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(AnalysisActionType.CASE_INSPECT, AnalysisActionType.ANALYSIS_STATUS),
                ConclusionStatus.MISSING_EVIDENCE);
    }

    private static AnalysisActionRequest<String> request(
            AnalysisActionType actionType, String operationId, String payload) {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                IDENTITY,
                new ActionTarget(
                        "workspace-1", IDENTITY.projectId(), IDENTITY.caseId(),
                        IDENTITY.analysisId(), Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.of(new OperationId(operationId)),
                payload);
    }

    private static AnalysisActionRequest<String> requestWithoutOperation(
            AnalysisActionType actionType, String payload) {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                IDENTITY,
                new ActionTarget(
                        "workspace-1", IDENTITY.projectId(), IDENTITY.caseId(),
                        IDENTITY.analysisId(), Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.empty(),
                payload);
    }

    private static OperationIdempotencyService.Context context(
            String operationId, AnalysisActionType actionType) {
        return new OperationIdempotencyService.Context(
                new OperationId(operationId), IDENTITY, actionType, INPUT_HASH);
    }
}
