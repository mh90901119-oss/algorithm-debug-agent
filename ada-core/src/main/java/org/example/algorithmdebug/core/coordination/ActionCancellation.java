package org.example.algorithmdebug.core.coordination;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将 MCP 取消信号转换成一次协作式、有界目标终止请求。 */
public final class ActionCancellation {
    /** 默认留给 Handler 清理目标进程和提交失败 Manifest 的时间。 */
    public static final Duration DEFAULT_TERMINATION_TIMEOUT = Duration.ofSeconds(10);
    /** 单次取消清理允许的硬上限。 */
    public static final Duration MAX_TERMINATION_TIMEOUT = Duration.ofMinutes(1);

    private final Duration terminationTimeout;
    private boolean cancellationRequested;
    private TerminationHandler terminationHandler;
    private Executor terminationExecutor;
    private boolean terminationDispatched;
    private boolean actionClaimed;

    /** @param terminationTimeout Handler 清理目标进程和提交 Manifest 的最大预算 */
    public ActionCancellation(Duration terminationTimeout) {
        if (terminationTimeout == null || terminationTimeout.isZero()
                || terminationTimeout.isNegative()
                || terminationTimeout.compareTo(MAX_TERMINATION_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "terminationTimeout must be positive and no greater than "
                            + MAX_TERMINATION_TIMEOUT);
        }
        this.terminationTimeout = terminationTimeout;
    }

    /** 创建尚未取消的默认令牌。 */
    public static ActionCancellation active() {
        return new ActionCancellation(DEFAULT_TERMINATION_TIMEOUT);
    }

    /**
     * 注册底层进程终止回调；若取消已到达则立即投递一次。
     *
     * @param handler 接收终止预算的回调，同一令牌只能注册一次
     */
    public void onCancellation(TerminationHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        TerminationHandler dispatch = null;
        Executor executor = null;
        synchronized (this) {
            if (terminationHandler != null) {
                throw new IllegalStateException("A termination handler is already registered");
            }
            terminationHandler = handler;
            if (cancellationRequested && !terminationDispatched) {
                terminationDispatched = true;
                dispatch = terminationHandler;
                executor = terminationExecutor;
            }
        }
        submit(dispatch, executor);
    }

    /** 请求取消；终止回调最多执行一次。 */
    public void requestCancellation() {
        requestCancellation(Runnable::run);
    }

    /**
     * 同步冻结取消状态，并通过调用方提供的执行器投递可能阻塞的终止回调。
     *
     * <p>该重载供 MCP Server 关闭使用，使终止回调的实际耗时不能占用 Server 的统一关闭预算。
     * 重复取消不会再次提交回调。</p>
     *
     * @param executor 终止回调执行器
     */
    public void requestCancellation(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        TerminationHandler dispatch = null;
        Executor selectedExecutor = null;
        synchronized (this) {
            cancellationRequested = true;
            if (terminationExecutor == null) {
                terminationExecutor = executor;
            }
            if (terminationHandler != null && !terminationDispatched) {
                terminationDispatched = true;
                dispatch = terminationHandler;
                selectedExecutor = terminationExecutor;
            }
        }
        submit(dispatch, selectedExecutor);
    }

    /** @return 是否已经收到取消请求 */
    public synchronized boolean isCancellationRequested() {
        return cancellationRequested;
    }

    /** @return Handler 必须遵守的终止预算 */
    public Duration terminationTimeout() {
        return terminationTimeout;
    }

    /**
     * 为一个分析动作独占该令牌。令牌保存终止处理器和一次性派发状态，因此不能跨动作复用。
     *
     * @return 首次占用返回 {@code true}；已经属于其他动作时返回 {@code false}
     */
    public synchronized boolean tryClaimForAction() {
        if (actionClaimed) {
            return false;
        }
        actionClaimed = true;
        return true;
    }

    /** 在协作检查点抛出标准取消异常。 */
    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Analysis action was cancelled");
        }
    }

    private void submit(TerminationHandler handler, Executor executor) {
        if (handler != null) {
            AtomicBoolean started = new AtomicBoolean();
            try {
                executor.execute(() -> {
                    started.set(true);
                    handler.terminate(terminationTimeout);
                });
            } catch (RuntimeException submissionFailure) {
                if (!started.get()) {
                    synchronized (this) {
                        if (terminationHandler == handler) {
                            terminationDispatched = false;
                            if (terminationExecutor == executor) {
                                terminationExecutor = null;
                            }
                        }
                    }
                }
                throw submissionFailure;
            }
        }
    }

    /** 接收有界终止预算的底层清理回调。 */
    @FunctionalInterface
    public interface TerminationHandler {
        /** 请求在给定预算内终止目标进程并提交失败 Manifest。 */
        void terminate(Duration timeout);
    }
}
