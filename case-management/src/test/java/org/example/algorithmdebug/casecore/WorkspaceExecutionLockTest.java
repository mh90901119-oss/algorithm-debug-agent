package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceExecutionLockTest {
    private static final ProjectId PROJECT_ONE = new ProjectId("project-1");
    private static final ProjectId PROJECT_TWO = new ProjectId("project-2");

    @TempDir Path temporaryDirectory;
    private Path projectsRoot;

    @BeforeEach
    void setUp() throws Exception {
        projectsRoot = temporaryDirectory.resolve("projects");
        Files.createDirectories(projectsRoot.resolve(PROJECT_ONE.value()));
        Files.createDirectories(projectsRoot.resolve(PROJECT_TWO.value()));
    }

    @Test
    void twoIndependentLockInstancesAllowOnlyOneOwner() {
        WorkspaceExecutionLock first = new WorkspaceExecutionLock(projectsRoot);
        WorkspaceExecutionLock second = new WorkspaceExecutionLock(projectsRoot);

        try (WorkspaceExecutionLock.Handle ignored = first.tryAcquire(PROJECT_ONE, Duration.ZERO)) {
            WorkspaceException failure = assertThrows(
                    WorkspaceException.class,
                    () -> second.tryAcquire(PROJECT_ONE, Duration.ZERO));

            assertEquals("TARGET_EXECUTION_BUSY", failure.code());
        }
    }

    @Test
    void closingHandleReleasesOperatingSystemLock() throws Exception {
        WorkspaceExecutionLock first = new WorkspaceExecutionLock(projectsRoot);
        WorkspaceExecutionLock second = new WorkspaceExecutionLock(projectsRoot);
        WorkspaceExecutionLock.Handle handle = first.tryAcquire(PROJECT_ONE, Duration.ZERO);

        handle.close();
        handle.close();

        try (WorkspaceExecutionLock.Handle acquired = second.tryAcquire(PROJECT_ONE, Duration.ZERO)) {
            assertTrue(acquired.isOpen());
            assertEquals(
                    projectsRoot.resolve("project-1/.control/target-execution.lock").toAbsolutePath(),
                    acquired.path());
            assertEquals(0, Files.size(acquired.path()));
        }
    }

    @Test
    void differentProjectsDoNotBlockEachOther() {
        WorkspaceExecutionLock locks = new WorkspaceExecutionLock(projectsRoot);

        try (WorkspaceExecutionLock.Handle first = locks.tryAcquire(PROJECT_ONE, Duration.ZERO);
             WorkspaceExecutionLock.Handle second = locks.tryAcquire(PROJECT_TWO, Duration.ZERO)) {
            assertTrue(first.isOpen());
            assertTrue(second.isOpen());
        }
    }

    @Test
    void positiveTimeoutUsesInjectedMonotonicTimeWithoutRealSleep() {
        AtomicLong nanos = new AtomicLong();
        WorkspaceExecutionLock owner = new WorkspaceExecutionLock(projectsRoot);
        WorkspaceExecutionLock contender = new WorkspaceExecutionLock(
                projectsRoot, nanos::get, nanos::addAndGet);

        try (WorkspaceExecutionLock.Handle ignored = owner.tryAcquire(PROJECT_ONE, Duration.ZERO)) {
            WorkspaceException failure = assertThrows(
                    WorkspaceException.class,
                    () -> contender.tryAcquire(PROJECT_ONE, Duration.ofMillis(25)));

            assertEquals("TARGET_EXECUTION_BUSY", failure.code());
            assertTrue(nanos.get() >= Duration.ofMillis(25).toNanos());
        }
    }
}
