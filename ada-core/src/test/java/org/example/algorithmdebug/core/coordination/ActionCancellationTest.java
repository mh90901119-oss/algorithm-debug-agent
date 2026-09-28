package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
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
}
