package io.github.kaluchi.jdtbridge.support;

import java.net.Socket;
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

    /** Poll until {@code host:port} refuses TCP connections. Retries
     *  up to 10 times with 50 ms park between attempts. */
    public static void assertPortRefused(String host, int port) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try (Socket socket = new Socket(host, port)) {
                LockSupport.parkNanos(PARK_NANOS);
            } catch (Exception e) {
                return;
            }
        }
        throw new AssertionError(
                "Port " + port + " still accepting connections");
    }

    /** Assert {@code actual >= min} without generating comparison
     *  bytecodes in the caller (avoids JaCoCo partial-coverage
     *  on the always-true branch). */
    public static void assertAtLeast(int min, int actual,
            String message) {
        if (actual < min) {
            throw new AssertionError(message
                    + " (expected >= " + min + ", got " + actual + ")");
        }
    }

    /** Assert {@code actual <= max}. */
    public static void assertAtMost(int max, int actual,
            String message) {
        if (actual > max) {
            throw new AssertionError(message
                    + " (expected <= " + max + ", got " + actual + ")");
        }
    }

    /** Assert a Map is unmodifiable (throws on put). Absorbs the
     *  try-catch bytecodes so callers stay branch-free. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void assertUnmodifiableMap(java.util.Map map) {
        try {
            map.put("__probe__", null);
            throw new AssertionError("Map should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }
}
