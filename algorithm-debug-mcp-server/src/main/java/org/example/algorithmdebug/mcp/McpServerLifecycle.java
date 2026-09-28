package org.example.algorithmdebug.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.example.algorithmdebug.core.coordination.ActionCancellation;

/** 管理 MCP 活动调用、取消传播和有界资源关闭。 */
public final class McpServerLifecycle implements AutoCloseable {
    private static final String NOT_ACCEPTING = "MCP server is not accepting new calls";
    private static final String DUPLICATE_REQUEST = "MCP requestId is already active";
    private static final String ACTIVE_CALL_LIMIT = "MCP active call limit was reached";
    private static final String SHARED_CANCELLATION =
            "MCP cancellation token is already active";
    private static final String CANCELLED_TOKEN =
            "MCP cancellation token was already cancelled";
    private static final String REUSED_CANCELLATION =
            "MCP cancellation token is bound to another action";
    private static final String CLOSE_FAILURE = "MCP_SERVER_CLOSE_FAILED";
    private static final String SHUTDOWN_WAIT_TIMEOUT = "MCP_SERVER_SHUTDOWN_WAIT_TIMEOUT";
    private static final String SHUTDOWN_WAIT_INTERRUPTED =
            "MCP_SERVER_SHUTDOWN_WAIT_INTERRUPTED";
    private static final String CANCELLATION_THREAD_PREFIX = "ada-mcp-cancel-";
    private static final String CANCELLATION_CALLBACK_FAILURE =
            "MCP_CANCELLATION_CALLBACK_FAILED";
    private static final String CANCELLATION_CALLBACK_LIMIT =
            "MCP cancellation callback limit was reached";
    private static final int CANCELLATION_THREAD_SEQUENCE_START = 0;

    private final Object monitor = new Object();
    private final Duration shutdownTimeout;
    private final int maximumActiveCalls;
    private final AutoCloseable runtimeCloser;
    private final Runnable serverCloser;
    private final LongSupplier nanoTime;
    private final WaitStrategy waitStrategy;
    private final ExecutorService cancellationExecutor;
    private final Set<Future<?>> cancellationTasks = ConcurrentHashMap.newKeySet();
    private final Map<McpRequestId, ActiveCall> activeCalls = new LinkedHashMap<>();
    private final Map<ActionCancellation, ActiveCall> activeCancellations =
            new IdentityHashMap<>();
    private int occupiedSlots;
    private State state = State.NEW;
    private ShutdownResult completedShutdown;
    private IllegalStateException completedShutdownFailure;

    /**
     * 创建生产生命周期。
     *
     * @param shutdownTimeout 活动调用释放预算，可为零以立即强制关闭
     * @param maximumActiveCalls 活动调用预算
     * @param runtimeCloser 共享 Runtime 关闭资源
     * @param serverCloser SDK Server 关闭动作
     */
    public McpServerLifecycle(
            Duration shutdownTimeout,
            int maximumActiveCalls,
            AutoCloseable runtimeCloser,
            Runnable serverCloser) {
        this(
                shutdownTimeout, maximumActiveCalls, runtimeCloser, serverCloser,
                System::nanoTime,
                (lock, nanos) -> TimeUnit.NANOSECONDS.timedWait(lock, nanos));
    }

    McpServerLifecycle(
            Duration shutdownTimeout,
            int maximumActiveCalls,
            AutoCloseable runtimeCloser,
            Runnable serverCloser,
            LongSupplier nanoTime,
            WaitStrategy waitStrategy) {
        if (shutdownTimeout == null || shutdownTimeout.isNegative()
                || shutdownTimeout.compareTo(McpServerLimits.MAX_SHUTDOWN_TIMEOUT) > 0
                || maximumActiveCalls <= 0
                || maximumActiveCalls > McpServerLimits.MAX_ACTIVE_CALLS
                || runtimeCloser == null || serverCloser == null
                || nanoTime == null || waitStrategy == null) {
            throw new IllegalArgumentException("MCP lifecycle configuration is invalid");
        }
        this.shutdownTimeout = shutdownTimeout;
        this.maximumActiveCalls = maximumActiveCalls;
        this.runtimeCloser = runtimeCloser;
        this.serverCloser = serverCloser;
        this.nanoTime = nanoTime;
        this.waitStrategy = waitStrategy;
        this.cancellationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(CANCELLATION_THREAD_PREFIX, CANCELLATION_THREAD_SEQUENCE_START)
                        .factory());
    }

    /** 进入接收请求状态；只能启动一次。 */
    public void start() {
        synchronized (monitor) {
            if (state != State.NEW) {
                throw new IllegalStateException("MCP lifecycle can only be started once");
            }
            state = State.RUNNING;
        }
    }

    /**
     * 登记一次活动调用。调用者必须在请求结束时关闭返回的 lease。
     *
     * @param requestId 当前连接内唯一的请求标识
     * @param cancellation 传给 Coordinator 的同一个取消令牌
     * @return 幂等释放 lease
     */
    public CallLease beginCall(String requestId, ActionCancellation cancellation) {
        return beginCall(McpRequestId.fromWire(requestId), cancellation);
    }

    /**
     * 把传输层已观察到的工具调用与 Coordinator 使用的独立取消令牌绑定。
     * 取消通知先于处理器到达时，绑定动作会立即把既有取消意图传播给令牌。
     *
     * @param requestId 保留 wire 类型的请求标识
     * @param cancellation 传给 Coordinator 的同一个取消令牌
     * @return 幂等释放 lease
     */
    public CallLease beginCall(McpRequestId requestId, ActionCancellation cancellation) {
        McpRequestId checkedId = checkedRequestId(requestId);
        if (cancellation == null) {
            throw new IllegalArgumentException("cancellation must not be null");
        }
        synchronized (monitor) {
            if (state != State.RUNNING) {
                throw new RejectedExecutionException(NOT_ACCEPTING);
            }
            ActiveCall activeCall = activeCalls.get(checkedId);
            if (activeCall == null) {
                ensureCapacity();
            } else if (activeCall.cancellation != null) {
                throw new RejectedExecutionException(DUPLICATE_REQUEST);
            }
            if (activeCancellations.containsKey(cancellation)) {
                throw new RejectedExecutionException(SHARED_CANCELLATION);
            }
            if (cancellation.isCancellationRequested()) {
                throw new RejectedExecutionException(CANCELLED_TOKEN);
            }
            if (!cancellation.tryClaimForAction()) {
                throw new RejectedExecutionException(REUSED_CANCELLATION);
            }
            if (activeCall == null) {
                activeCall = new ActiveCall();
                activeCalls.put(checkedId, activeCall);
                occupiedSlots++;
            }
            activeCancellations.put(cancellation, activeCall);
            activeCall.cancellation = cancellation;
            if (activeCall.cancellationRequested) {
                requestCancellationAsync(activeCall, cancellation);
            }
        }
        return new CallLease(checkedId);
    }

    /**
     * 在 SDK 异步分派前占住每个 JSON-RPC requestId，统一限制 SDK 内部待处理请求；
     * 工具调用还借此保留紧随请求到达的取消意图。
     *
     * @param requestId wire 请求标识
     */
    public void observeCall(McpRequestId requestId) {
        McpRequestId checkedId = checkedRequestId(requestId);
        synchronized (monitor) {
            if (state != State.RUNNING) {
                throw new RejectedExecutionException(NOT_ACCEPTING);
            }
            if (activeCalls.containsKey(checkedId)) {
                throw new RejectedExecutionException(DUPLICATE_REQUEST);
            }
            ensureCapacity();
            activeCalls.put(checkedId, new ActiveCall());
            occupiedSlots++;
        }
    }

    /** 把客户端单请求取消传播到该请求交给 Coordinator 的令牌。 */
    public boolean cancel(String requestId) {
        return cancel(McpRequestId.fromWire(requestId));
    }

    /** 把 wire 取消通知传播到对应请求；尚未进入处理器的请求会保留取消意图。 */
    public boolean cancel(McpRequestId requestId) {
        McpRequestId checkedId = checkedRequestId(requestId);
        synchronized (monitor) {
            if (state != State.RUNNING) {
                return false;
            }
            ActiveCall activeCall = activeCalls.get(checkedId);
            if (activeCall == null) {
                return false;
            }
            activeCall.cancellationRequested = true;
            if (activeCall.cancellation != null) {
                requestCancellationAsync(activeCall, activeCall.cancellation);
            }
        }
        return true;
    }

    /**
     * SDK 写出并刷新终态响应后，清理非工具请求或未被工具处理器领取的传输占位。
     *
     * @param requestId 响应信封中的类型化 requestId
     */
    public void completeUnclaimedCall(McpRequestId requestId) {
        McpRequestId checkedId = checkedRequestId(requestId);
        synchronized (monitor) {
            ActiveCall activeCall = activeCalls.get(checkedId);
            if (activeCall != null && activeCall.cancellation == null) {
                activeCalls.remove(checkedId);
                markRequestComplete(activeCall);
                monitor.notifyAll();
            }
        }
    }

    /**
     * 执行幂等关闭：拒绝新请求、取消、等待、关闭 Runtime，最后关闭 SDK Server。
     *
     * @return 是否在预算内释放活动调用的结构化结果
     */
    public ShutdownResult shutdown() {
        final List<ActionCancellation> cancellations;
        final long deadline = saturatedAdd(nanoTime.getAsLong(), shutdownTimeout.toNanos());
        synchronized (monitor) {
            if (completedShutdownFailure != null) {
                throw completedShutdownFailure;
            }
            if (completedShutdown != null) {
                return completedShutdown;
            }
            if (state == State.CLOSING) {
                return awaitConcurrentShutdown();
            }
            state = State.CLOSING;
            List<ActionCancellation> selected = new ArrayList<>();
            for (ActiveCall activeCall : activeCalls.values()) {
                if (activeCall.cancellation != null) {
                    selected.add(activeCall.cancellation);
                    activeCall.cancellationRequested = true;
                    requestCancellationAsync(activeCall, activeCall.cancellation);
                }
            }
            cancellations = List.copyOf(selected);
        }
        boolean drained = awaitDrain(deadline);
        stopCancellationExecutor(deadline);

        List<Throwable> closeFailures = new ArrayList<>();
        try {
            runtimeCloser.close();
        } catch (Exception failure) {
            closeFailures.add(failure);
        }
        try {
            serverCloser.run();
        } catch (RuntimeException failure) {
            closeFailures.add(failure);
        }

        ShutdownResult result = new ShutdownResult(
                State.CLOSED, drained, cancellations.size());
        IllegalStateException combined = combinedCloseFailure(closeFailures);
        synchronized (monitor) {
            state = State.CLOSED;
            completedShutdown = result;
            completedShutdownFailure = combined;
            monitor.notifyAll();
        }
        if (combined != null) {
            throw combined;
        }
        return result;
    }

    /** @return 当前生命周期状态 */
    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    /** @return 当前未释放的活动调用数 */
    public int activeCallCount() {
        synchronized (monitor) {
            return activeCalls.size();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private boolean awaitDrain(long deadline) {
        synchronized (monitor) {
            while (occupiedSlots > 0) {
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    waitStrategy.await(monitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void requestCancellationAsync(
            ActiveCall activeCall, ActionCancellation cancellation) {
        try {
            cancellation.requestCancellation(
                    command -> submitCancellationCallback(activeCall, command));
        } catch (RejectedExecutionException failure) {
            System.err.println(CANCELLATION_CALLBACK_FAILURE);
        }
    }

    private void submitCancellationCallback(ActiveCall activeCall, Runnable command) {
        synchronized (monitor) {
            if (activeCall.cancellationTaskRunning || activeCall.slotReleased) {
                throw new RejectedExecutionException(CANCELLATION_CALLBACK_LIMIT);
            }
            activeCall.cancellationTaskRunning = true;
            FutureTask<Void> task = new FutureTask<>(
                    () -> runCancellationCallback(command), null) {
                @Override
                protected void done() {
                    cancellationTasks.remove(this);
                    cancellationCallbackCompleted(activeCall);
                }
            };
            cancellationTasks.add(task);
            try {
                cancellationExecutor.execute(task);
            } catch (RuntimeException submissionFailure) {
                task.cancel(false);
                throw submissionFailure;
            }
        }
    }

    private void stopCancellationExecutor(long deadline) {
        synchronized (monitor) {
            cancellationExecutor.shutdown();
        }
        boolean terminated = false;
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining > 0) {
            try {
                terminated = cancellationExecutor.awaitTermination(
                        remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!terminated) {
            cancellationTasks.forEach(task -> task.cancel(true));
            cancellationExecutor.shutdownNow();
        }
    }

    private static void runCancellationCallback(Runnable command) {
        try {
            command.run();
        } catch (RuntimeException failure) {
            // 终止回调失败不能污染 stdout，也不能阻塞其余请求的有界关闭。
            System.err.println(CANCELLATION_CALLBACK_FAILURE);
        }
    }

    private void release(McpRequestId requestId) {
        synchronized (monitor) {
            ActiveCall released = activeCalls.remove(requestId);
            if (released != null) {
                markRequestComplete(released);
            }
            monitor.notifyAll();
        }
    }

    private void ensureCapacity() {
        if (occupiedSlots >= maximumActiveCalls) {
            throw new RejectedExecutionException(ACTIVE_CALL_LIMIT);
        }
    }

    private void cancellationCallbackCompleted(ActiveCall activeCall) {
        synchronized (monitor) {
            activeCall.cancellationTaskRunning = false;
            releaseSlotIfComplete(activeCall);
            monitor.notifyAll();
        }
    }

    private void markRequestComplete(ActiveCall activeCall) {
        activeCall.requestComplete = true;
        releaseSlotIfComplete(activeCall);
    }

    private void releaseSlotIfComplete(ActiveCall activeCall) {
        if (activeCall.requestComplete && !activeCall.cancellationTaskRunning
                && !activeCall.slotReleased) {
            activeCall.slotReleased = true;
            if (activeCall.cancellation != null
                    && activeCancellations.get(activeCall.cancellation) == activeCall) {
                activeCancellations.remove(activeCall.cancellation);
            }
            occupiedSlots--;
        }
    }

    private ShutdownResult awaitConcurrentShutdown() {
        long deadline = saturatedAdd(nanoTime.getAsLong(), shutdownTimeout.toNanos());
        while (completedShutdown == null && completedShutdownFailure == null) {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                throw new IllegalStateException(SHUTDOWN_WAIT_TIMEOUT);
            }
            try {
                waitStrategy.await(monitor, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(SHUTDOWN_WAIT_INTERRUPTED, interrupted);
            }
        }
        if (completedShutdownFailure != null) {
            throw completedShutdownFailure;
        }
        return completedShutdown;
    }

    private static IllegalStateException combinedCloseFailure(List<Throwable> closeFailures) {
        if (closeFailures.isEmpty()) {
            return null;
        }
        IllegalStateException combined = new IllegalStateException(
                CLOSE_FAILURE, closeFailures.getFirst());
        closeFailures.stream().skip(1).forEach(combined::addSuppressed);
        return combined;
    }

    private static McpRequestId checkedRequestId(McpRequestId requestId) {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        return requestId;
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    /** MCP Server 生命周期状态。 */
    public enum State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED
    }

    /** 一次完成的关闭结果。 */
    public record ShutdownResult(
            State finalState, boolean callsDrained, int cancelledCallCount) {
        /** 校验状态与计数。 */
        public ShutdownResult {
            if (finalState != State.CLOSED || cancelledCallCount < 0) {
                throw new IllegalArgumentException("Shutdown result is invalid");
            }
        }
    }

    /** 活动请求结束时释放的幂等句柄。 */
    public final class CallLease implements AutoCloseable {
        private final McpRequestId requestId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CallLease(McpRequestId requestId) {
            this.requestId = requestId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(requestId);
            }
        }
    }

    private static final class ActiveCall {
        private ActionCancellation cancellation;
        private boolean cancellationRequested;
        private boolean cancellationTaskRunning;
        private boolean requestComplete;
        private boolean slotReleased;
    }

    @FunctionalInterface
    interface WaitStrategy {
        void await(Object monitor, long timeoutNanos) throws InterruptedException;
    }
}
