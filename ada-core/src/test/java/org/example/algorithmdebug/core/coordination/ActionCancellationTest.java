package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActionCancellationTest {
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(4);

    @Test
    void cancellationArrivingBeforeHandlerRegistrationIsDispatchedExactlyOnce() {
        ActionCancellation cancellation = new ActionCancellation(TERMINATION_TIMEOUT);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Duration> observedTimeout = new AtomicReference<>();

        cancellation.requestCancellation();
        cancellation.requestCancellation();
        cancellation.onCancellation(timeout -> {
            calls.incrementAndGet();
            observedTimeout.set(timeout);
        });
        cancellation.requestCancellation();

        assertEquals(1, calls.get());
        assertEquals(TERMINATION_TIMEOUT, observedTimeout.get());
        assertTrue(cancellation.isCancellationRequested());
        assertThrows(CancellationException.class, cancellation::throwIfCancellationRequested);
    }

    @Test
    void secondTerminationHandlerIsRejected() {
        ActionCancellation cancellation = ActionCancellation.active();
        cancellation.onCancellation(timeout -> { });

        assertThrows(
                IllegalStateException.class,
                () -> cancellation.onCancellation(timeout -> { }));
    }

    @Test
    void executorBackedCancellationMarksRequestedBeforeTerminationCallbackRuns() {
        ActionCancellation cancellation = ActionCancellation.active();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        cancellation.onCancellation(timeout -> calls.incrementAndGet());

        cancellation.requestCancellation(dispatched::set);

        assertTrue(cancellation.isCancellationRequested());
        assertEquals(0, calls.get());
        dispatched.get().run();
        assertEquals(1, calls.get());
        cancellation.requestCancellation(command -> {
            throw new AssertionError("termination callback was dispatched twice");
        });
    }

    @Test
    void executorSelectedByEarlyCancellationAlsoRunsALateHandler() {
        ActionCancellation cancellation = ActionCancellation.active();
        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        cancellation.requestCancellation(dispatched::set);
        cancellation.onCancellation(timeout -> calls.incrementAndGet());

        assertEquals(0, calls.get());
        dispatched.get().run();
        assertEquals(1, calls.get());
    }

    @Test
    void rejectedCallbackSubmissionCanBeRetriedWithoutLosingCancellation() {
        ActionCancellation cancellation = ActionCancellation.active();
        AtomicInteger calls = new AtomicInteger();
        cancellation.onCancellation(timeout -> calls.incrementAndGet());

        assertThrows(RejectedExecutionException.class, () -> cancellation.requestCancellation(
                command -> {
                    throw new RejectedExecutionException("executor unavailable");
                }));
        cancellation.requestCancellation(Runnable::run);

        assertTrue(cancellation.isCancellationRequested());
        assertEquals(1, calls.get());
    }

    @Test
    void throwingInlineHandlerIsNeverDispatchedTwice() {
        ActionCancellation cancellation = ActionCancellation.active();
        AtomicInteger calls = new AtomicInteger();
        cancellation.onCancellation(timeout -> {
            calls.incrementAndGet();
            throw new IllegalStateException("termination failed");
        });

        assertThrows(IllegalStateException.class, cancellation::requestCancellation);
        cancellation.requestCancellation();

        assertEquals(1, calls.get());
    }

    @Test
    void oneCancellationTokenCanBeClaimedForOnlyOneAction() {
        ActionCancellation cancellation = ActionCancellation.active();

        assertTrue(cancellation.tryClaimForAction());
        assertFalse(cancellation.tryClaimForAction());
    }
}
