package io.github.kaluchi.jdtbridge.support;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Deadline-bounded poll for asynchronous Eclipse state — sessions
 * landing in the model, launches terminating, jobs draining. The
 * poll parks for 50 ms between checks so a hot CI runner does not
 * burn a core on Thread.onSpinWait while waiting on I/O-paced
 * events.
 */
public final class TestAwait {

    private static final long PARK_NANOS =
            TimeUnit.MILLISECONDS.toNanos(50);

    private TestAwait() {
    }

    /** Park-poll {@code condition} every 50 ms until it returns
     *  true or {@code timeoutMs} elapses. Throws AssertionError with
     *  {@code timeoutMessage} on timeout — never returns false. */
    public static void pollUntil(long timeoutMs,
            BooleanSupplier condition, String timeoutMessage) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(PARK_NANOS);
        }
        throw new AssertionError(timeoutMessage);
    }
}
