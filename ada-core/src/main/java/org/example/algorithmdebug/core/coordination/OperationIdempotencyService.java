package org.example.algorithmdebug.core.coordination;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.example.algorithmdebug.casecore.OperationJournal;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;

/** 将追加式 Operation Journal 解释为确定性的幂等执行状态。 */
public final class OperationIdempotencyService {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final OperationJournal journal;

    /** @param journal 追加式操作日志 */
    public OperationIdempotencyService(OperationJournal journal) {
        this.journal = requireNonNull(journal, "journal");
    }

    /**
     * 只读检查 operationId；不会创建 STARTED，也不会把不确定操作变回可执行。
     *
     * @param context 操作稳定身份和输入哈希
     * @return 互斥的幂等状态
     */
    public Inspection inspect(Context context) {
        Context checked = requireNonNull(context, "context");
        final Optional<OperationJournal.State> optionalState;
        try {
            optionalState = journal.findState(checked.identity(), checked.operationId());
        } catch (WorkspaceException invalidJournal) {
            throw new IdempotencyFailure(
                    CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                    "Operation journal is unreadable or inconsistent",
                    invalidJournal);
        }
        if (optionalState.isEmpty()) {
            return Inspection.available();
        }
        OperationJournal.State state = optionalState.orElseThrow();
        if (state.started().actionType() != checked.actionType()
                || !state.started().inputSha256().equals(checked.inputSha256())) {
            return Inspection.conflict();
        }
        if (state.terminal().isEmpty()) {
            return Inspection.inProgress();
        }
        OperationJournal.TerminalDocument terminal = state.terminal().orElseThrow();
        OperationReceipt replay = replay(terminal.receipt());
        if (terminal.kind() == OperationJournal.TerminalKind.UNCERTAIN) {
            return Inspection.uncertain(replay);
        }
        return Inspection.replayable(replay);
    }

    /**
     * 原子声明新操作。若另一个调用抢先创建，则返回重新读取后的稳定状态而不重试动作。
     *
     * @param context 操作稳定身份和输入哈希
     * @param startedAt 启动时间
     * @return STARTED、已有终态或冲突状态
     */
    public Inspection claim(Context context, Instant startedAt) {
        Context checked = requireNonNull(context, "context");
        Instant checkedStartedAt = requireNonNull(startedAt, "startedAt");
        Inspection current = inspect(checked);
        if (current.status() != Status.AVAILABLE) {
            return current;
        }
        try {
            journal.start(
                    checked.identity(), checked.operationId(), checked.actionType(),
                    checked.inputSha256(), checkedStartedAt);
            return Inspection.started();
        } catch (WorkspaceException concurrentOrInvalid) {
            Inspection raced;
            try {
                raced = inspect(checked);
            } catch (IdempotencyFailure readFailure) {
                concurrentOrInvalid.addSuppressed(readFailure);
                throw new IdempotencyFailure(
                        CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                        "Operation could not be claimed or recovered",
                        concurrentOrInvalid);
            }
            if (raced.status() == Status.AVAILABLE) {
                throw new IdempotencyFailure(
                        CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                        "Operation start failed without a recoverable state",
                        concurrentOrInvalid);
            }
            return raced;
        }
    }

    /**
     * 追加成功终态并返回非回放回执。
     *
     * @param context 操作上下文
     * @param resultArtifactId 可选主结果 Artifact ID
     * @param completedAt 完成时间
     * @return 新写入的成功回执
     */
    public OperationReceipt complete(
            Context context, Optional<String> resultArtifactId, Instant completedAt) {
        Context checked = requireNonNull(context, "context");
        OperationReceipt receipt = new OperationReceipt(
                SchemaVersions.OPERATION_RECEIPT,
                checked.operationId(), checked.identity(), checked.actionType(),
                ActionOutcome.SUCCEEDED, checked.inputSha256(), false,
                requireNonNull(resultArtifactId, "resultArtifactId"), Optional.empty());
        journal.complete(receipt, requireNonNull(completedAt, "completedAt"));
        return receipt;
    }

    /**
     * 追加已知失败终态并返回非回放回执。
     *
     * @param context 操作上下文
     * @param errorCode 独立失败语义，不得为 OPERATION_UNCERTAIN
     * @param failedAt 失败时间
     * @return 新写入的失败回执
     */
    public OperationReceipt fail(
            Context context, CoordinationErrorCode errorCode, Instant failedAt) {
        Context checked = requireNonNull(context, "context");
        CoordinationErrorCode checkedCode = requireNonNull(errorCode, "errorCode");
        if (checkedCode == CoordinationErrorCode.OPERATION_UNCERTAIN) {
            throw new IllegalArgumentException("Use uncertain for OPERATION_UNCERTAIN");
        }
        OperationReceipt receipt = failedReceipt(checked, checkedCode);
        journal.fail(receipt, requireNonNull(failedAt, "failedAt"));
        return receipt;
    }

    /**
     * 追加不确定终态，明确阻止自动重跑。
     *
     * @param context 操作上下文
     * @param recordedAt 判定不确定的时间
     * @return 新写入的不确定回执
     */
    public OperationReceipt uncertain(Context context, Instant recordedAt) {
        Context checked = requireNonNull(context, "context");
        OperationReceipt receipt = failedReceipt(
                checked, CoordinationErrorCode.OPERATION_UNCERTAIN);
        journal.markUncertain(receipt, requireNonNull(recordedAt, "recordedAt"));
        return receipt;
    }

    private static OperationReceipt failedReceipt(
            Context context, CoordinationErrorCode errorCode) {
        return new OperationReceipt(
                SchemaVersions.OPERATION_RECEIPT,
                context.operationId(), context.identity(), context.actionType(),
                ActionOutcome.FAILED, context.inputSha256(), false,
                Optional.empty(), Optional.of(errorCode));
    }

    private static OperationReceipt replay(OperationReceipt receipt) {
        return new OperationReceipt(
                receipt.schemaVersion(), receipt.operationId(), receipt.identity(),
                receipt.actionType(), receipt.outcome(), receipt.inputSha256(), true,
                receipt.resultArtifactId(), receipt.errorCode());
    }

    /**
     * 一次幂等操作的完整稳定身份。
     *
     * @param operationId 操作 ID
     * @param identity Analysis 身份
     * @param actionType 动作类型
     * @param inputSha256 规范请求哈希
     */
    public record Context(
            OperationId operationId,
            AnalysisIdentity identity,
            AnalysisActionType actionType,
            String inputSha256) {
        public Context {
            operationId = requireNonNull(operationId, "operationId");
            identity = requireNonNull(identity, "identity");
            actionType = requireNonNull(actionType, "actionType");
            if (inputSha256 == null || !SHA256_PATTERN.matcher(inputSha256).matches()) {
                throw new IllegalArgumentException("inputSha256 must be lowercase SHA-256 hex");
            }
        }
    }

    /** 幂等检查的互斥状态。 */
    public enum Status {
        AVAILABLE,
        STARTED,
        IN_PROGRESS,
        REPLAYABLE,
        UNCERTAIN,
        CONFLICT
    }

    /**
     * @param status 当前状态
     * @param receipt 已有终态的回放回执
     * @param errorCode 阻止执行的稳定原因
     */
    public record Inspection(
            Status status,
            Optional<OperationReceipt> receipt,
            Optional<CoordinationErrorCode> errorCode) {
        public Inspection {
            status = requireNonNull(status, "status");
            receipt = requireNonNull(receipt, "receipt");
            errorCode = requireNonNull(errorCode, "errorCode");
            boolean receiptRequired = status == Status.REPLAYABLE || status == Status.UNCERTAIN;
            if (receiptRequired != receipt.isPresent()) {
                throw new IllegalArgumentException(
                        "receipt presence does not match idempotency status");
            }
            boolean errorRequired = status == Status.IN_PROGRESS
                    || status == Status.UNCERTAIN || status == Status.CONFLICT;
            if (errorRequired != errorCode.isPresent()) {
                throw new IllegalArgumentException(
                        "errorCode presence does not match idempotency status");
            }
        }

        private static Inspection available() {
            return new Inspection(Status.AVAILABLE, Optional.empty(), Optional.empty());
        }

        private static Inspection started() {
            return new Inspection(Status.STARTED, Optional.empty(), Optional.empty());
        }

        private static Inspection inProgress() {
            return new Inspection(
                    Status.IN_PROGRESS, Optional.empty(),
                    Optional.of(CoordinationErrorCode.OPERATION_IN_PROGRESS));
        }

        private static Inspection replayable(OperationReceipt receipt) {
            return new Inspection(Status.REPLAYABLE, Optional.of(receipt), Optional.empty());
        }

        private static Inspection uncertain(OperationReceipt receipt) {
            return new Inspection(
                    Status.UNCERTAIN, Optional.of(receipt),
                    Optional.of(CoordinationErrorCode.OPERATION_UNCERTAIN));
        }

        private static Inspection conflict() {
            return new Inspection(
                    Status.CONFLICT, Optional.empty(),
                    Optional.of(CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT));
        }
    }

    /** 操作日志基础设施损坏或不可读取，保留底层 Workspace cause。 */
    public static final class IdempotencyFailure extends RuntimeException {
        private final CoordinationErrorCode code;

        IdempotencyFailure(
                CoordinationErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = requireNonNull(code, "code");
        }

        /** @return 稳定协调错误码 */
        public CoordinationErrorCode code() {
            return code;
        }
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
