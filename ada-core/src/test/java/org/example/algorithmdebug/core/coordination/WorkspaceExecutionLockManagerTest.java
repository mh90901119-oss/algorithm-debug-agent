package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.junit.jupiter.api.Test;

class WorkspaceExecutionLockManagerTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void onlyTargetExecutionAcquiresTheOsLock() {
        AtomicInteger acquisitions = new AtomicInteger();
        WorkspaceExecutionLockManager manager = new WorkspaceExecutionLockManager(
                (projectId, timeout) -> {
                    assertEquals(PROJECT_ID, projectId);
                    assertEquals(ACQUIRE_TIMEOUT, timeout);
                    acquisitions.incrementAndGet();
                    return () -> { };
                }, ACQUIRE_TIMEOUT);

        manager.acquire(ActionSideEffect.READ_ONLY, PROJECT_ID).close();
        manager.acquire(ActionSideEffect.CASE_WRITE, PROJECT_ID).close();
        manager.acquire(ActionSideEffect.TARGET_EXECUTION, PROJECT_ID).close();

        assertEquals(1, acquisitions.get());
    }

    @Test
    void invalidTimeoutIsRejectedAtCompositionTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceExecutionLockManager(
                        (projectId, timeout) -> () -> { },
                        WorkspaceExecutionLockManager.MAX_ACQUIRE_TIMEOUT.plusNanos(1)));
    }
}
