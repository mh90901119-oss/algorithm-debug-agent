package org.example.algorithmdebug.core.coordination;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.OperationId;
import org.example.algorithmdebug.contracts.coordination.OperationReceipt;

/**
 * 所有模型可调用分析动作唯一共享的授权、幂等、互斥、执行和校验模板。
 *
 * <p>本类不包含 Maven、CodePath 或 JDWP 具体实现；具体动作只存在于 immutable binding 中。</p>
 */
public final class AnalysisCoordinator {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final String REVISION_PROVENANCE_PREFIX = "analysis-revision-";
    private final AnalysisActionRegistry registry;
    private final StateSource stateSource;
    private final OperationIdempotencyService idempotency;
    private final WorkspaceExecutionLockManager lockManager;
    private final DecisionSink decisionSink;
    private final RequestHasher requestHasher;
    private final Clock clock;
    private final Supplier<String> decisionIdSupplier;
    private final CoordinatedResultFactory results;
    private final AnalysisPolicyExecutor policies;

    /**
     * 创建无具体工具知识的统一协调器。
     *
     * @param registry 不可变动作绑定表
     * @param stateSource 控制状态投影端口
     * @param idempotency 操作幂等服务
     * @param lockManager 目标 JVM OS 锁管理器
     * @param decisionSink 追加式决策归档端口
     * @param requestHasher 规范请求哈希端口
     * @param clock 可替换时钟
     * @param decisionIdSupplier 决策 ID 生成器
     */
    public AnalysisCoordinator(
            AnalysisActionRegistry registry,
            StateSource stateSource,
            OperationIdempotencyService idempotency,
            WorkspaceExecutionLockManager lockManager,
            DecisionSink decisionSink,
            RequestHasher requestHasher,
            Clock clock,
            Supplier<String> decisionIdSupplier) {
        this.registry = requireNonNull(registry, "registry");
        this.stateSource = requireNonNull(stateSource, "stateSource");
        this.idempotency = requireNonNull(idempotency, "idempotency");
        this.lockManager = requireNonNull(lockManager, "lockManager");
        this.decisionSink = requireNonNull(decisionSink, "decisionSink");
        this.requestHasher = requireNonNull(requestHasher, "requestHasher");
        this.clock = requireNonNull(clock, "clock");
        this.decisionIdSupplier = requireNonNull(decisionIdSupplier, "decisionIdSupplier");
        this.results = new CoordinatedResultFactory();
        this.policies = new AnalysisPolicyExecutor();
    }

    /**
     * 使用新的未取消令牌执行一次动作。
     *
     * @param request typed 动作请求
     * @return 拒绝、失败或成功的统一结构化结果
     */
    public CoordinatedToolResult<?> execute(AnalysisActionRequest<?> request) {
        return execute(request, ActionCancellation.active());
    }

    /** @return Registry 当前按名称稳定排序的完整动作集合 */
    public List<AnalysisActionType> registeredActionTypes() {
        return registry.bindings().stream()
                .map(AnalysisActionBinding::actionType)
                .toList();
    }

    /**
     * 只读投影当前控制状态，不执行 Action、Policy、Handler 或决策归档。
     *
     * <p>该入口仅供 Resource/状态展示使用；所有模型请求的动作仍必须调用 {@link #execute}。</p>
     *
     * @param identity 要读取的 Analysis 身份
     * @return 经过身份一致性校验的当前控制视图
     */
    public AnalysisControlView currentControlView(AnalysisIdentity identity) {
        return project(requireNonNull(identity, "identity"));
    }

    /**
     * 执行一次动作；所有写操作和目标执行都不可绕过 Policy、幂等日志和后置校验。
     *
     * @param request typed 动作请求
     * @param cancellation 由宿主生命周期传播的取消令牌
     * @return 拒绝、失败或成功的统一结构化结果
     */
    public CoordinatedToolResult<?> execute(
            AnalysisActionRequest<?> request, ActionCancellation cancellation) {
        AnalysisActionRequest<?> checkedRequest = requireNonNull(request, "request");
        ActionCancellation checkedCancellation = requireNonNull(cancellation, "cancellation");
        AnalysisActionBinding<?, ?> binding = registry.require(checkedRequest.actionType());
        return executeTyped(binding, checkedRequest, checkedCancellation);
    }

    private <I, O> CoordinatedToolResult<?> executeTyped(
            AnalysisActionBinding<I, O> binding,
            AnalysisActionRequest<?> untypedRequest,
            ActionCancellation cancellation) {
        I payload = binding.requirePayload(untypedRequest.payload());
        AnalysisActionRequest<I> request = castRequest(untypedRequest);
        String inputHash = hashRequest(request);
        AnalysisControlView before = project(request.identity());
        ActionDecision authorization = policies.authorize(binding, before, request);
        archiveDecision(inputHash, authorization);
        if (authorization.decision() == ActionDecisionCode.REJECTED) {
            CoordinatedToolResult<?> replay = replayRejectedSideEffectIfPresent(
                    binding, request, inputHash, before);
            if (replay != null) {
                return replay;
            }
            return results.rejected(authorization, before);
        }

        if (binding.sideEffect() == ActionSideEffect.READ_ONLY) {
            return executeReadOnly(binding, request, payload, cancellation, inputHash, before);
        }
        OperationId operationId = request.operationId().orElseThrow();
        OperationIdempotencyService.Context context = new OperationIdempotencyService.Context(
                operationId, request.identity(), request.actionType(), inputHash);
        OperationIdempotencyService.Inspection initial = inspectOperation(context);
        if (initial.status() != OperationIdempotencyService.Status.AVAILABLE) {
            return results.idempotency(initial, before, request.actionType());
        }

        final WorkspaceExecutionLockManager.LockLease lease;
        try {
            lease = lockManager.acquire(binding.sideEffect(), request.identity().projectId());
        } catch (WorkspaceException lockFailure) {
            if (CoordinationErrorCode.TARGET_EXECUTION_BUSY.name().equals(lockFailure.code())) {
                ActionDecision busy = policies.rejected(
                        binding, request, before, CoordinationErrorCode.TARGET_EXECUTION_BUSY);
                archiveDecision(inputHash, busy);
                return results.rejected(busy, before);
            }
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_STATE_INVALID,
                    "Target execution lock could not be acquired", lockFailure);
        }

        try (lease) {
            AnalysisControlView lockedBefore = project(request.identity());
            ActionDecision lockedAuthorization = policies.authorize(
                    binding, lockedBefore, request);
            if (!lockedAuthorization.equals(authorization)) {
                archiveDecision(inputHash, lockedAuthorization);
            }
            if (lockedAuthorization.decision() == ActionDecisionCode.REJECTED) {
                return results.rejected(lockedAuthorization, lockedBefore);
            }
            OperationIdempotencyService.Inspection claimed = claimOperation(context);
            if (claimed.status() != OperationIdempotencyService.Status.STARTED) {
                return results.idempotency(claimed, lockedBefore, request.actionType());
            }
            return executeClaimed(
                    binding, request, payload, cancellation, inputHash, context, lockedBefore);
        }
    }

    private <I, O> CoordinatedToolResult<?> replayRejectedSideEffectIfPresent(
            AnalysisActionBinding<I, O> binding,
            AnalysisActionRequest<I> request,
            String inputHash,
            AnalysisControlView before) {
        if (binding.sideEffect() == ActionSideEffect.READ_ONLY
                || request.operationId().isEmpty()) {
            return null;
        }
        OperationIdempotencyService.Context context =
                new OperationIdempotencyService.Context(
                        request.operationId().orElseThrow(), request.identity(),
                        request.actionType(), inputHash);
        OperationIdempotencyService.Inspection inspection = inspectOperation(context);
        return inspection.status() == OperationIdempotencyService.Status.AVAILABLE
                ? null
                : results.idempotency(inspection, before, request.actionType());
    }

    private <I, O> CoordinatedToolResult<?> executeReadOnly(
            AnalysisActionBinding<I, O> binding,
            AnalysisActionRequest<I> request,
            I payload,
            ActionCancellation cancellation,
            String inputHash,
            AnalysisControlView before) {
        try {
            cancellation.throwIfCancellationRequested();
            O output = binding.handler().execute(request.target(), payload, cancellation);
            AnalysisActionBinding.ResultMetadata metadata = describeResult(binding, output);
            AnalysisControlView after = project(request.identity());
            ActionDecision verification = policies.verify(
                    binding, before, request, output, after);
            archiveDecision(inputHash, verification);
            if (verification.decision() == ActionDecisionCode.REJECTED) {
                return results.postconditionFailed(
                        output, metadata, after, request.actionType());
            }
            return results.succeeded(output, metadata, after, request.actionType());
        } catch (java.util.concurrent.CancellationException cancelled) {
            return results.cancelled(before, request.actionType());
        } catch (ExecutionFailure failure) {
            throw failure;
        } catch (RuntimeException handlerFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.ACTION_EXECUTION_FAILED,
                    "Read-only action handler failed", handlerFailure);
        }
    }

    private <I, O> CoordinatedToolResult<?> executeClaimed(
            AnalysisActionBinding<I, O> binding,
            AnalysisActionRequest<I> request,
            I payload,
            ActionCancellation cancellation,
            String inputHash,
            OperationIdempotencyService.Context context,
            AnalysisControlView lockedBefore) {
        final O output;
        try {
            cancellation.throwIfCancellationRequested();
            output = binding.handler().execute(request.target(), payload, cancellation);
        } catch (java.util.concurrent.CancellationException cancelled) {
            var receipt = failOperation(
                    context, CoordinationErrorCode.ACTION_CANCELLED, cancelled);
            AnalysisControlView afterCancellation = project(request.identity());
            return results.cancelled(receipt, afterCancellation);
        } catch (RuntimeException handlerFailure) {
            failOperation(context, CoordinationErrorCode.ACTION_EXECUTION_FAILED, handlerFailure);
            throw new ExecutionFailure(
                    CoordinationErrorCode.ACTION_EXECUTION_FAILED,
                    "Analysis action handler failed", handlerFailure);
        }

        final AnalysisActionBinding.ResultMetadata metadata;
        try {
            metadata = describeResult(binding, output);
        } catch (RuntimeException invalidResult) {
            markUncertain(context, invalidResult);
            throw new ExecutionFailure(
                    CoordinationErrorCode.OPERATION_UNCERTAIN,
                    "Action completed but its result could not be described",
                    invalidResult);
        }
        final AnalysisControlView after;
        final ActionDecision verification;
        try {
            after = project(request.identity());
            verification = policies.verify(
                    binding, lockedBefore, request, output, after);
        } catch (RuntimeException unverifiable) {
            markUncertain(context, unverifiable);
            throw new ExecutionFailure(
                    CoordinationErrorCode.OPERATION_UNCERTAIN,
                    "Action completed but its postcondition could not be determined",
                    unverifiable);
        }

        if (verification.decision() == ActionDecisionCode.REJECTED) {
            failKnownOperation(
                    context, CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
            archiveDecision(inputHash, verification);
            return results.postconditionFailed(
                    output, metadata, after, request.actionType());
        }

        completeOperation(context, metadata.resultArtifactId());
        archiveDecision(inputHash, verification);
        return results.succeeded(output, metadata, after, request.actionType());
    }

    private OperationReceipt failOperation(
            OperationIdempotencyService.Context context,
            CoordinationErrorCode code,
            RuntimeException primaryFailure) {
        try {
            return idempotency.fail(context, code, clock.instant());
        } catch (RuntimeException journalFailure) {
            primaryFailure.addSuppressed(journalFailure);
            throw new ExecutionFailure(
                    CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                    "Failed to archive action terminal", primaryFailure);
        }
    }

    private void failKnownOperation(
            OperationIdempotencyService.Context context,
            CoordinationErrorCode code) {
        try {
            idempotency.fail(context, code, clock.instant());
        } catch (RuntimeException journalFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                    "Failed to archive known action failure",
                    journalFailure);
        }
    }

    private void completeOperation(
            OperationIdempotencyService.Context context,
            Optional<String> resultArtifactId) {
        try {
            idempotency.complete(context, resultArtifactId, clock.instant());
        } catch (RuntimeException journalFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.OPERATION_JOURNAL_CONFLICT,
                    "Failed to archive successful action terminal",
                    journalFailure);
        }
    }

    private void markUncertain(
            OperationIdempotencyService.Context context, RuntimeException primaryFailure) {
        try {
            idempotency.uncertain(context, clock.instant());
        } catch (RuntimeException journalFailure) {
            primaryFailure.addSuppressed(journalFailure);
        }
    }

    private <I, O> AnalysisActionBinding.ResultMetadata describeResult(
            AnalysisActionBinding<I, O> binding, O output) {
        try {
            return binding.describe(output);
        } catch (RuntimeException mappingFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_STATE_INVALID,
                    "Action result metadata is invalid",
                    mappingFailure);
        }
    }

    private OperationIdempotencyService.Inspection inspectOperation(
            OperationIdempotencyService.Context context) {
        try {
            return idempotency.inspect(context);
        } catch (OperationIdempotencyService.IdempotencyFailure failure) {
            throw new ExecutionFailure(
                    failure.code(), "Operation journal inspection failed", failure);
        }
    }

    private OperationIdempotencyService.Inspection claimOperation(
            OperationIdempotencyService.Context context) {
        try {
            return idempotency.claim(context, clock.instant());
        } catch (OperationIdempotencyService.IdempotencyFailure failure) {
            throw new ExecutionFailure(
                    failure.code(), "Operation journal claim failed", failure);
        }
    }

    private AnalysisControlView project(AnalysisIdentity identity) {
        final AnalysisControlView view;
        try {
            view = requireNonNull(stateSource.project(identity), "control view");
        } catch (ExecutionFailure failure) {
            throw failure;
        } catch (RuntimeException projectionFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_STATE_INVALID,
                    "Analysis control state projection failed",
                    projectionFailure);
        }
        if (!identity.equals(view.identity())) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH,
                    "Projected control view belongs to another Analysis",
                    null);
        }
        return view;
    }

    private void archiveDecision(String inputHash, ActionDecision decision) {
        try {
            String decisionId = requireText(decisionIdSupplier.get(), "decisionId");
            decisionSink.append(
                    decisionId,
                    inputHash,
                    List.of(REVISION_PROVENANCE_PREFIX + decision.revision()),
                    clock.instant(),
                    decision);
        } catch (RuntimeException archiveFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_STATE_INVALID,
                    "Coordination decision could not be archived",
                    archiveFailure);
        }
    }

    private String hashRequest(AnalysisActionRequest<?> request) {
        try {
            return requireInputHash(requestHasher.sha256(request));
        } catch (RuntimeException hashingFailure) {
            throw new ExecutionFailure(
                    CoordinationErrorCode.COORDINATION_STATE_INVALID,
                    "Analysis action request could not be deterministically hashed",
                    hashingFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <I> AnalysisActionRequest<I> castRequest(
            AnalysisActionRequest<?> request) {
        return (AnalysisActionRequest<I>) request;
    }

    private static String requireInputHash(String value) {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("RequestHasher must return lowercase SHA-256 hex");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank without surrounding whitespace");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    /** 控制状态只读投影端口。 */
    @FunctionalInterface
    public interface StateSource {
        /** @return 从不可变归档重建的当前控制视图 */
        AnalysisControlView project(AnalysisIdentity identity);
    }

    /** 规范化动作请求哈希端口；实现必须确定性输出小写 SHA-256。 */
    @FunctionalInterface
    public interface RequestHasher {
        /** @return 请求的规范小写 SHA-256 */
        String sha256(AnalysisActionRequest<?> request);
    }

    /** Coordinator 决策追加归档端口。 */
    @FunctionalInterface
    public interface DecisionSink {
        /** 追加一条带输入哈希和状态修订 provenance 的 Policy 决策。 */
        void append(
                String decisionId,
                String inputSha256,
                List<String> provenance,
                Instant decidedAt,
                ActionDecision decision);
    }

    /** 带稳定错误码并保留原始 cause 的协调器基础设施/执行异常。 */
    public static final class ExecutionFailure extends RuntimeException {
        private final CoordinationErrorCode code;

        /**
         * 创建结构化协调器异常。
         *
         * @param code 稳定错误码
         * @param message 内部诊断说明
         * @param cause 原始失败原因，可为空
         */
        public ExecutionFailure(
                CoordinationErrorCode code, String message, Throwable cause) {
            super(requireText(message, "message"), cause);
            this.code = requireNonNull(code, "code");
        }

        /** @return 稳定错误码 */
        public CoordinationErrorCode code() {
            return code;
        }
    }
}
