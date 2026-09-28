package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.core.coordination.ActionCancellation;
import org.junit.jupiter.api.Test;

class McpServerLifecycleTest {
    private static final Duration SHORT_SHUTDOWN_TIMEOUT = Duration.ofMillis(100);

    @Test
    void shutdownRejectsNewCallsCancelsActiveCallsAndWaitsForLease() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ofSeconds(1), 2,
                () -> closeOrder.add("runtime"),
                () -> closeOrder.add("server"));
        lifecycle.start();
        ActionCancellation cancellation = ActionCancellation.active();
        CountDownLatch cancelled = new CountDownLatch(1);
        cancellation.onCancellation(timeout -> cancelled.countDown());
        McpServerLifecycle.CallLease lease =
                lifecycle.beginCall("request-1", cancellation);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<McpServerLifecycle.ShutdownResult> shutdown =
                    executor.submit(lifecycle::shutdown);
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone());
            assertThrows(RejectedExecutionException.class, () -> lifecycle.beginCall(
                    "request-2", ActionCancellation.active()));

            lease.close();
            McpServerLifecycle.ShutdownResult result = shutdown.get(1, TimeUnit.SECONDS);

            assertTrue(result.callsDrained());
            assertEquals(1, result.cancelledCallCount());
            assertEquals(McpServerLifecycle.State.CLOSED, result.finalState());
            assertEquals(List.of("runtime", "server"), closeOrder);
        }
    }

    @Test
    void zeroWaitBudgetReportsUndrainedButStillClosesResources() {
        List<String> closeOrder = new ArrayList<>();
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 1,
                () -> closeOrder.add("runtime"),
                () -> closeOrder.add("server"));
        lifecycle.start();
        ActionCancellation cancellation = ActionCancellation.active();
        lifecycle.beginCall("request-1", cancellation);

        McpServerLifecycle.ShutdownResult result = lifecycle.shutdown();

        assertFalse(result.callsDrained());
        assertTrue(cancellation.isCancellationRequested());
        assertEquals(List.of("runtime", "server"), closeOrder);
        assertEquals(result, lifecycle.shutdown());
    }

    @Test
    void targetedCancellationOnlyTouchesMatchingCoordinatorToken() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 2, () -> { }, () -> { });
        lifecycle.start();
        ActionCancellation first = ActionCancellation.active();
        ActionCancellation second = ActionCancellation.active();
        try (McpServerLifecycle.CallLease ignoredFirst = lifecycle.beginCall("first", first);
             McpServerLifecycle.CallLease ignoredSecond = lifecycle.beginCall("second", second)) {
            assertTrue(lifecycle.cancel("first"));
            assertTrue(first.isCancellationRequested());
            assertFalse(second.isCancellationRequested());
            assertFalse(lifecycle.cancel("missing"));
        }
        lifecycle.close();
    }

    @Test
    void stringAndNumericRequestIdsRemainDistinct() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 2, () -> { }, () -> { });
        lifecycle.start();
        ActionCancellation numeric = ActionCancellation.active();
        ActionCancellation text = ActionCancellation.active();
        try (McpServerLifecycle.CallLease ignoredNumeric = lifecycle.beginCall(
                     McpRequestId.fromWire(1), numeric);
             McpServerLifecycle.CallLease ignoredText = lifecycle.beginCall(
                     McpRequestId.fromWire("1"), text)) {
            assertTrue(lifecycle.cancel(McpRequestId.fromWire(1)));
            assertTrue(numeric.isCancellationRequested());
            assertFalse(text.isCancellationRequested());
        }
        lifecycle.close();
    }

    @Test
    void cancellationBeforeHandlerBindingIsNotLost() throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ofSeconds(1), 1, () -> { }, () -> { });
        lifecycle.start();
        McpRequestId requestId = McpRequestId.fromWire(7);
        lifecycle.observeCall(requestId);
        assertTrue(lifecycle.cancel(requestId));
        CountDownLatch callback = new CountDownLatch(1);
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> callback.countDown());

        try (McpServerLifecycle.CallLease ignored =
                     lifecycle.beginCall(requestId, cancellation)) {
            assertTrue(cancellation.isCancellationRequested());
            assertTrue(callback.await(1, TimeUnit.SECONDS));
        }
        lifecycle.close();
    }

    @Test
    void sdkResponseCleanupCannotReleaseAHandlerOwnedLease() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 1, () -> { }, () -> { });
        lifecycle.start();
        McpRequestId requestId = McpRequestId.fromWire("claimed");
        try (McpServerLifecycle.CallLease ignored = lifecycle.beginCall(
                requestId, ActionCancellation.active())) {
            lifecycle.completeUnclaimedCall(requestId);
            assertEquals(1, lifecycle.activeCallCount());
        }
        assertEquals(0, lifecycle.activeCallCount());
        lifecycle.close();
    }

    @Test
    void cancellationCallbackKeepsItsRequestSlotUntilTerminationFinishes() throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 1, () -> { }, () -> { });
        lifecycle.start();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch holdFirst = new CountDownLatch(1);
        ActionCancellation first = ActionCancellation.active();
        first.onCancellation(timeout -> {
            firstStarted.countDown();
            try {
                holdFirst.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        McpServerLifecycle.CallLease firstLease = lifecycle.beginCall("first", first);
        assertTrue(lifecycle.cancel("first"));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        firstLease.close();

        try {
            assertThrows(RejectedExecutionException.class, () -> lifecycle.beginCall(
                    "second", ActionCancellation.active()));
        } finally {
            holdFirst.countDown();
        }
        lifecycle.close();
    }

    @Test
    void cancellationTokenCannotBeReusedWhileItsTerminationCallbackOwnsTheSlot()
            throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ofSeconds(1), 2, () -> { }, () -> { });
        lifecycle.start();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        McpServerLifecycle.CallLease lease = lifecycle.beginCall("first", cancellation);
        assertTrue(lifecycle.cancel("first"));
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        lease.close();

        try {
            assertThrows(RejectedExecutionException.class,
                    () -> lifecycle.beginCall("second", cancellation));
        } finally {
            releaseCallback.countDown();
        }
        lifecycle.close();
    }

    @Test
    void alreadyCancelledTokenCannotBeReusedAfterItsCallbackCompletes() throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ofSeconds(1), 2, () -> { }, () -> { });
        lifecycle.start();
        CountDownLatch callbackCompleted = new CountDownLatch(1);
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> callbackCompleted.countDown());
        try (McpServerLifecycle.CallLease ignored =
                     lifecycle.beginCall("first", cancellation)) {
            assertTrue(lifecycle.cancel("first"));
            assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        }

        assertThrows(RejectedExecutionException.class,
                () -> lifecycle.beginCall("second", cancellation));
        lifecycle.close();
    }

    @Test
    void completedRequestCannotReuseAnUncancelledOneShotToken() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 2, () -> { }, () -> { });
        lifecycle.start();
        ActionCancellation cancellation = ActionCancellation.active();
        try (McpServerLifecycle.CallLease ignored =
                     lifecycle.beginCall("first", cancellation)) {
            assertFalse(cancellation.isCancellationRequested());
        }

        assertThrows(RejectedExecutionException.class,
                () -> lifecycle.beginCall("second", cancellation));
        lifecycle.close();
    }

    @Test
    void rejectsSharingOneActiveCancellationTokenAcrossRequests() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 2, () -> { }, () -> { });
        lifecycle.start();
        ActionCancellation shared = ActionCancellation.active();

        try (McpServerLifecycle.CallLease ignored = lifecycle.beginCall("first", shared)) {
            assertThrows(RejectedExecutionException.class,
                    () -> lifecycle.beginCall("second", shared));
        }
        lifecycle.close();
    }

    @Test
    void enforcesUniqueRequestIdsAndActiveCallBudget() {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 1, () -> { }, () -> { });
        lifecycle.start();
        try (McpServerLifecycle.CallLease ignored = lifecycle.beginCall(
                "request-1", ActionCancellation.active())) {
            assertThrows(RejectedExecutionException.class, () -> lifecycle.beginCall(
                    "request-1", ActionCancellation.active()));
            assertThrows(RejectedExecutionException.class, () -> lifecycle.beginCall(
                    "request-2", ActionCancellation.active()));
        }
        lifecycle.close();
    }

    @Test
    void serverCloserStillRunsWhenRuntimeCloseFails() {
        List<String> closeOrder = new ArrayList<>();
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ZERO, 1,
                () -> {
                    closeOrder.add("runtime");
                    throw new IllegalStateException("runtime close failed");
                },
                () -> closeOrder.add("server"));
        lifecycle.start();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, lifecycle::shutdown);

        assertEquals("MCP_SERVER_CLOSE_FAILED", failure.getMessage());
        assertEquals(List.of("runtime", "server"), closeOrder);
        assertEquals(McpServerLifecycle.State.CLOSED, lifecycle.state());
        IllegalStateException repeated = assertThrows(
                IllegalStateException.class, lifecycle::shutdown);
        assertEquals("MCP_SERVER_CLOSE_FAILED", repeated.getMessage());
        assertEquals(List.of("runtime", "server"), closeOrder);
    }

    @Test
    void slowTerminationCallbackCannotExtendServerShutdownBudget() throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                SHORT_SHUTDOWN_TIMEOUT, 1, () -> { }, () -> { });
        lifecycle.start();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                callbackInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        lifecycle.beginCall("request-1", cancellation);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<McpServerLifecycle.ShutdownResult> shutdown =
                    executor.submit(lifecycle::shutdown);
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            try {
                McpServerLifecycle.ShutdownResult result =
                        shutdown.get(1, TimeUnit.SECONDS);
                assertFalse(result.callsDrained());
                assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            } catch (TimeoutException failure) {
                throw new AssertionError(
                        "termination callback exceeded the server shutdown budget", failure);
            } finally {
                releaseCallback.countDown();
            }
        }
    }

    @Test
    void lateTerminationHandlerStillUsesManagedExecutorAndShutdownBudget()
            throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                SHORT_SHUTDOWN_TIMEOUT, 1, () -> { }, () -> { });
        lifecycle.start();
        ActionCancellation cancellation = ActionCancellation.active();
        lifecycle.beginCall("request-1", cancellation);
        assertTrue(lifecycle.cancel("request-1"));
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        CountDownLatch holdCallback = new CountDownLatch(1);
        cancellation.onCancellation(timeout -> {
            callbackStarted.countDown();
            try {
                holdCallback.await();
            } catch (InterruptedException interrupted) {
                callbackInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            McpServerLifecycle.ShutdownResult result = lifecycle.shutdown();
            assertFalse(result.callsDrained());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            holdCallback.countDown();
        }
    }

    @Test
    void runningTerminationCallbackMakesShutdownReportUndrainedAfterLeaseCloses()
            throws Exception {
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                SHORT_SHUTDOWN_TIMEOUT, 1, () -> { }, () -> { });
        lifecycle.start();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        CountDownLatch holdCallback = new CountDownLatch(1);
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> {
            callbackStarted.countDown();
            try {
                holdCallback.await();
            } catch (InterruptedException interrupted) {
                callbackInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        McpServerLifecycle.CallLease lease = lifecycle.beginCall("request-1", cancellation);
        assertTrue(lifecycle.cancel("request-1"));
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        lease.close();

        try {
            McpServerLifecycle.ShutdownResult result = lifecycle.shutdown();
            assertFalse(result.callsDrained());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            holdCallback.countDown();
        }
    }

    @Test
    void concurrentShutdownWaitsForOwnerAndReusesOneResult() throws Exception {
        CountDownLatch runtimeCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseRuntimeClose = new CountDownLatch(1);
        AtomicInteger runtimeCloses = new AtomicInteger();
        AtomicInteger serverCloses = new AtomicInteger();
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                Duration.ofSeconds(1), 1,
                () -> {
                    runtimeCloses.incrementAndGet();
                    runtimeCloseStarted.countDown();
                    releaseRuntimeClose.await();
                },
                serverCloses::incrementAndGet);
        lifecycle.start();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<McpServerLifecycle.ShutdownResult> owner =
                    executor.submit(lifecycle::shutdown);
            assertTrue(runtimeCloseStarted.await(1, TimeUnit.SECONDS));
            Future<McpServerLifecycle.ShutdownResult> follower =
                    executor.submit(lifecycle::shutdown);
            assertFalse(follower.isDone());

            releaseRuntimeClose.countDown();
            McpServerLifecycle.ShutdownResult ownerResult = owner.get(1, TimeUnit.SECONDS);
            McpServerLifecycle.ShutdownResult followerResult =
                    follower.get(1, TimeUnit.SECONDS);

            assertEquals(ownerResult, followerResult);
            assertEquals(1, runtimeCloses.get());
            assertEquals(1, serverCloses.get());
        } finally {
            releaseRuntimeClose.countDown();
        }
    }
}
