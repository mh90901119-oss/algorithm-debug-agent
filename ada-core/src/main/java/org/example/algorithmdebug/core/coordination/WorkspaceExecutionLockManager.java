package org.example.algorithmdebug.core.coordination;

import java.time.Duration;
import org.example.algorithmdebug.casecore.WorkspaceExecutionLock;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;

/** 根据动作副作用等级统一管理项目级目标 JVM OS 锁。 */
public final class WorkspaceExecutionLockManager {
    /** 默认目标锁等待预算。 */
    public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    /** 目标锁等待预算硬上限，与底层 OS 锁保持一致。 */
    public static final Duration MAX_ACQUIRE_TIMEOUT = Duration.ofMinutes(1);

    private final LockProvider lockProvider;
    private final Duration acquireTimeout;

    /** @param lock 真实 Workspace OS 锁 */
    public WorkspaceExecutionLockManager(WorkspaceExecutionLock lock) {
        this(lock, DEFAULT_ACQUIRE_TIMEOUT);
    }

    /**
     * 用真实 Workspace OS 锁和明确等待预算创建管理器。
     *
     * @param lock 真实 Workspace OS 锁
     * @param acquireTimeout 最大等待预算
     */
    public WorkspaceExecutionLockManager(
            WorkspaceExecutionLock lock, Duration acquireTimeout) {
        this(provider(lock), acquireTimeout);
    }

    WorkspaceExecutionLockManager(LockProvider lockProvider, Duration acquireTimeout) {
        this.lockProvider = requireNonNull(lockProvider, "lockProvider");
        if (acquireTimeout == null || acquireTimeout.isNegative()
                || acquireTimeout.compareTo(MAX_ACQUIRE_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "acquireTimeout must be between zero and " + MAX_ACQUIRE_TIMEOUT);
        }
        this.acquireTimeout = acquireTimeout;
    }

    /**
     * TARGET_EXECUTION 获取排他锁；其他动作返回无副作用 Lease。
     *
     * @param sideEffect 动作副作用等级
     * @param projectId 目标项目
     * @return 必须关闭的锁租约
     */
    public LockLease acquire(ActionSideEffect sideEffect, ProjectId projectId) {
        ActionSideEffect checkedSideEffect = requireNonNull(sideEffect, "sideEffect");
        ProjectId checkedProjectId = requireNonNull(projectId, "projectId");
        if (checkedSideEffect != ActionSideEffect.TARGET_EXECUTION) {
            return LockLease.NO_OP;
        }
        return requireNonNull(
                lockProvider.acquire(checkedProjectId, acquireTimeout), "lock lease");
    }

    /** 可用于 try-with-resources 的锁租约。 */
    @FunctionalInterface
    public interface LockLease extends AutoCloseable {
        LockLease NO_OP = () -> { };

        /** 释放租约；实现必须幂等。 */
        @Override
        void close();
    }

    @FunctionalInterface
    interface LockProvider {
        LockLease acquire(ProjectId projectId, Duration timeout);
    }

    private static LockProvider provider(WorkspaceExecutionLock lock) {
        WorkspaceExecutionLock checkedLock = requireNonNull(lock, "lock");
        return (projectId, timeout) -> {
            WorkspaceExecutionLock.Handle handle = checkedLock.tryAcquire(projectId, timeout);
            return handle::close;
        };
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
