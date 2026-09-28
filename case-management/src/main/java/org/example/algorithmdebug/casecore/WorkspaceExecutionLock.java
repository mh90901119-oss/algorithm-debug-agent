package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.example.algorithmdebug.contracts.ProjectId;

/** 使用项目级 OS 文件锁阻止多个 Agent 进程同时启动同一目标执行。 */
public final class WorkspaceExecutionLock {
    private static final String CONTROL_DIRECTORY = ".control";
    private static final String LOCK_FILE = "target-execution.lock";
    private static final String BUSY_CODE = "TARGET_EXECUTION_BUSY";
    private static final String FAILURE_CODE = "TARGET_EXECUTION_LOCK_FAILED";
    private static final Duration MAX_WAIT = Duration.ofMinutes(1);
    private static final long RETRY_NANOS = Duration.ofMillis(10).toNanos();

    private final Path projectsRoot;
    private final LongSupplier nanoClock;
    private final Pause pause;

    /** @param projectsRoot Workspace 中已存在的 projects 根目录 */
    public WorkspaceExecutionLock(Path projectsRoot) {
        this(projectsRoot, System::nanoTime, LockSupport::parkNanos);
    }

    WorkspaceExecutionLock(Path projectsRoot, LongSupplier nanoClock, Pause pause) {
        Path root = requireDirectory(projectsRoot, "projectsRoot");
        this.projectsRoot = root;
        this.nanoClock = requireNonNull(nanoClock, "nanoClock");
        this.pause = requireNonNull(pause, "pause");
    }

    /**
     * 在有界时间内尝试获取指定项目的目标执行锁。
     *
     * @param projectId 目标项目
     * @param timeout 最大等待时间，零表示只尝试一次
     * @return 必须关闭的锁句柄
     * @throws WorkspaceException 超时繁忙或文件系统锁失败
     */
    public Handle tryAcquire(ProjectId projectId, Duration timeout) {
        ProjectId checkedProjectId = requireNonNull(projectId, "projectId");
        Duration checkedTimeout = requireTimeout(timeout);
        Path projectRoot = requireDirectory(
                projectsRoot.resolve(checkedProjectId.value()).normalize(), "projectRoot");
        if (!projectRoot.startsWith(projectsRoot)) {
            throw new IllegalArgumentException("projectId escapes projectsRoot");
        }
        Path controlRoot = projectRoot.resolve(CONTROL_DIRECTORY).normalize();
        Path lockPath = controlRoot.resolve(LOCK_FILE).normalize();
        if (!lockPath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Lock path escapes projectRoot");
        }

        FileChannel channel = null;
        try {
            if (!Files.exists(controlRoot, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(controlRoot);
                } catch (java.nio.file.FileAlreadyExistsException concurrentCreation) {
                    // 另一个进程可能刚创建控制目录；下方仍会以 NOFOLLOW_LINKS 验证。
                }
            }
            if (Files.isSymbolicLink(controlRoot)
                    || !Files.isDirectory(controlRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException(FAILURE_CODE, "Project control path is not a regular directory");
            }
            if (Files.isSymbolicLink(lockPath)) {
                throw new WorkspaceException(FAILURE_CODE, "Target execution lock must not be a symbolic link");
            }
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            long started = nanoClock.getAsLong();
            long timeoutNanos = checkedTimeout.toNanos();
            while (true) {
                FileLock lock = attempt(channel);
                if (lock != null) {
                    return new Handle(lockPath, channel, lock);
                }
                long elapsed = elapsedSince(started, nanoClock.getAsLong());
                if (elapsed >= timeoutNanos) {
                    closeAfterFailure(channel, null);
                    throw new WorkspaceException(
                            BUSY_CODE, "Target execution lock is already owned: " + checkedProjectId.value());
                }
                pause.pause(Math.min(RETRY_NANOS, timeoutNanos - elapsed));
            }
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            closeAfterFailure(channel, failure);
            throw new WorkspaceException(
                    FAILURE_CODE, "Failed to acquire target execution lock", failure);
        } catch (RuntimeException failure) {
            closeAfterFailure(channel, failure);
            throw failure;
        }
    }

    private static FileLock attempt(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException busy) {
            return null;
        }
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.compareTo(MAX_WAIT) > 0) {
            throw new IllegalArgumentException("timeout must be between zero and " + MAX_WAIT);
        }
        return timeout;
    }

    private static long elapsedSince(long started, long current) {
        long elapsed = current - started;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private static Path requireDirectory(Path path, String field) {
        if (path == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(field + " must be an existing regular directory");
        }
        return normalized;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static void closeAfterFailure(FileChannel channel, Throwable primary) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (primary != null) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    /** 持有 OS FileLock 的幂等关闭句柄。 */
    public static final class Handle implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Handle(Path path, FileChannel channel, FileLock lock) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
        }

        /** @return 锁文件的规范绝对路径 */
        public Path path() {
            return path;
        }

        /** @return 当前句柄是否仍持有有效 OS 锁 */
        public synchronized boolean isOpen() {
            return !closed && lock.isValid() && channel.isOpen();
        }

        /** 先释放 FileLock 再关闭 Channel；重复关闭安全。 */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw new WorkspaceException(
                        FAILURE_CODE, "Failed to release target execution lock", failure);
            }
        }
    }

    @FunctionalInterface
    interface Pause {
        void pause(long nanos);
    }
}
