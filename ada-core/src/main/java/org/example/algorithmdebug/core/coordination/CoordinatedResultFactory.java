package org.example.algorithmdebug.core.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;

/** 只负责把协调器内部 typed 状态映射成统一有界 Tool Result。 */
final class CoordinatedResultFactory {
    private static final String REJECTED_MESSAGE =
            "Analysis action was rejected by deterministic policy";
    private static final String IDEMPOTENCY_MESSAGE =
            "Analysis action was not executed because of operation state";
    private static final String POSTCONDITION_MESSAGE =
            "Analysis action failed deterministic postcondition verification";
    private static final String CANCELLED_MESSAGE =
            "Analysis action was cancelled and bounded termination was requested";
    private static final String OPERATION_REPLAYED_CODE = "OPERATION_REPLAYED";

    CoordinatedToolResult<?> rejected(
            ActionDecision decision, AnalysisControlView source) {
        CoordinationErrorCode reason = decision.reasonCodes().getFirst();
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.REJECTED,
                reason.name(),
                REJECTED_MESSAGE,
                decision,
                List.of(),
                actionControl(
                        source, decision.requestedAction(),
                        ActionDecisionCode.REJECTED, decision.reasonCodes()));
    }

    CoordinatedToolResult<?> idempotency(
            OperationIdempotencyService.Inspection inspection,
            AnalysisControlView source,
            AnalysisActionType actionType) {
        if (inspection.status() == OperationIdempotencyService.Status.REPLAYABLE) {
            OperationReceipt receipt = inspection.receipt().orElseThrow();
            String code = receipt.errorCode().map(Enum::name)
                    .orElse(OPERATION_REPLAYED_CODE);
            return new CoordinatedToolResult<>(
                    SchemaVersions.COORDINATED_TOOL_RESULT,
                    receipt.outcome(),
                    code,
                    IDEMPOTENCY_MESSAGE,
                    receipt,
                    List.of(),
                    actionControl(
                            source, actionType, ActionDecisionCode.ALLOWED, List.of()));
        }
        if (inspection.status() == OperationIdempotencyService.Status.UNCERTAIN) {
            return failedReceipt(
                    inspection.receipt().orElseThrow(),
                    CoordinationErrorCode.OPERATION_UNCERTAIN,
                    IDEMPOTENCY_MESSAGE,
                    source);
        }
        CoordinationErrorCode code = inspection.errorCode().orElse(
                CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT);
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.REJECTED,
                code.name(),
                IDEMPOTENCY_MESSAGE,
                inspection,
                List.of(),
                actionControl(
                        source, actionType, ActionDecisionCode.REJECTED, List.of(code)));
    }

    CoordinatedToolResult<?> cancelled(
            OperationReceipt receipt, AnalysisControlView source) {
        return failedReceipt(
                receipt, CoordinationErrorCode.ACTION_CANCELLED,
                CANCELLED_MESSAGE, source);
    }

    CoordinatedToolResult<?> cancelled(
            AnalysisControlView source, AnalysisActionType actionType) {
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.FAILED,
                CoordinationErrorCode.ACTION_CANCELLED.name(),
                CANCELLED_MESSAGE,
                CoordinationErrorCode.ACTION_CANCELLED,
                List.of(),
                actionControl(
                        source, actionType, ActionDecisionCode.ALLOWED, List.of()));
    }

    <O> CoordinatedToolResult<O> postconditionFailed(
            O output,
            AnalysisActionBinding.ResultMetadata metadata,
            AnalysisControlView after,
            AnalysisActionType actionType) {
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.FAILED,
                CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED.name(),
                POSTCONDITION_MESSAGE,
                output,
                metadata.artifacts(),
                actionControl(
                        after, actionType, ActionDecisionCode.ALLOWED, List.of()));
    }

    <O> CoordinatedToolResult<O> succeeded(
            O output,
            AnalysisActionBinding.ResultMetadata metadata,
            AnalysisControlView after,
            AnalysisActionType actionType) {
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.SUCCEEDED,
                metadata.code(),
                metadata.message(),
                output,
                metadata.artifacts(),
                actionControl(
                        after, actionType, ActionDecisionCode.ALLOWED, List.of()));
    }

    private CoordinatedToolResult<?> failedReceipt(
            OperationReceipt receipt,
            CoordinationErrorCode code,
            String message,
            AnalysisControlView source) {
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.FAILED,
                code.name(),
                message,
                receipt,
                List.of(),
                actionControl(
                        source, receipt.actionType(), ActionDecisionCode.ALLOWED, List.of()));
    }

    private static AnalysisControlView actionControl(
            AnalysisControlView source,
            AnalysisActionType actionType,
            ActionDecisionCode decision,
            List<CoordinationErrorCode> reasons) {
        return new AnalysisControlView(
                source.schemaVersion(), source.policyVersion(), source.identity(), source.revision(),
                actionType, decision, reasons,
                source.satisfiedObligationIds(), source.remainingObligationIds(),
                source.contradictionIds(), source.openGapIds(),
                source.supportedHypothesisIds(), source.refutedHypothesisIds(),
                source.unevaluatedPredicateIds(), source.allowedActions(),
                source.terminalEligibility());
    }
}
