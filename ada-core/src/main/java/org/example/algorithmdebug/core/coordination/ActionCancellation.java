package org.example.algorithmdebug.core.coordination;

import java.time.Duration;
import java.util.concurrent.CancellationException;

/** 将 MCP 取消信号转换成一次协作式、有界目标终止请求。 */
public final class ActionCancellation {
    /** 默认留给 Handler 清理目标进程和提交失败 Manifest 的时间。 */
    public static final Duration DEFAULT_TERMINATION_TIMEOUT = Duration.ofSeconds(10);
    /** 单次取消清理允许的硬上限。 */
    public static final Duration MAX_TERMINATION_TIMEOUT = Duration.ofMinutes(1);

    private final Duration terminationTimeout;
    private boolean cancellationRequested;
    private TerminationHandler terminationHandler;
    private boolean terminationDispatched;

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
        synchronized (this) {
            if (terminationHandler != null) {
                throw new IllegalStateException("A termination handler is already registered");
            }
            terminationHandler = handler;
            if (cancellationRequested && !terminationDispatched) {
                terminationDispatched = true;
                dispatch = terminationHandler;
            }
        }
        dispatch(dispatch);
    }

    /** 请求取消；终止回调最多执行一次。 */
    public void requestCancellation() {
        TerminationHandler dispatch = null;
        synchronized (this) {
            cancellationRequested = true;
            if (terminationHandler != null && !terminationDispatched) {
                terminationDispatched = true;
                dispatch = terminationHandler;
            }
        }
        dispatch(dispatch);
    }

    /** @return 是否已经收到取消请求 */
    public synchronized boolean isCancellationRequested() {
        return cancellationRequested;
    }

    /** @return Handler 必须遵守的终止预算 */
    public Duration terminationTimeout() {
        return terminationTimeout;
    }

    /** 在协作检查点抛出标准取消异常。 */
    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Analysis action was cancelled");
        }
    }

    private void dispatch(TerminationHandler handler) {
        if (handler != null) {
            handler.terminate(terminationTimeout);
        }
    }

    /** 接收有界终止预算的底层清理回调。 */
    @FunctionalInterface
    public interface TerminationHandler {
        /** 请求在给定预算内终止目标进程并提交失败 Manifest。 */
        void terminate(Duration timeout);
    }
}
