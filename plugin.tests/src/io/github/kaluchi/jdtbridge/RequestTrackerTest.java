package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-session producer/consumer queue.
 * No Eclipse runtime — pure {@link java.util.concurrent} types.
 */
public class RequestTrackerTest {

    private RequestTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RequestTracker();
    }

    @Nested
    class LogRequest {

        @Test
        void formatsBracketBridgeLine() {
            tracker.logRequest("S", "GET", "/q", 200, 15);
            assertEquals("[BRIDGE] GET /q (200, 15ms)\n",
                    tracker.drain("S"));
        }

        @Test
        void appendsAcrossCalls() {
            tracker.logRequest("S", "GET", "/a", 200, 1);
            tracker.logRequest("S", "POST", "/b", 500, 2);
            assertEquals(
                    "[BRIDGE] GET /a (200, 1ms)\n"
                            + "[BRIDGE] POST /b (500, 2ms)\n",
                    tracker.drain("S"));
        }

        @Test
        void nullSessionIsNoop() {
            tracker.logRequest(null, "GET", "/q", 200, 1);
            assertEquals("", tracker.drain(null));
        }

        @Test
        void emptySessionIsNoop() {
            tracker.logRequest("", "GET", "/q", 200, 1);
            assertEquals("", tracker.drain(""));
        }
    }

    @Nested
    class LogTelemetry {

        @Test
        void enqueuesRawText() {
            tracker.logTelemetry("S", "raw line\n");
            assertEquals("raw line\n", tracker.drain("S"));
        }

        @Test
        void multipleEntriesConcatenate() {
            tracker.logTelemetry("S", "a");
            tracker.logTelemetry("S", "b");
            tracker.logTelemetry("S", "c");
            assertEquals("abc", tracker.drain("S"));
        }

        @Test
        void nullSessionIsNoop() {
            tracker.logTelemetry(null, "x");
            assertEquals("", tracker.drain(null));
        }

        @Test
        void emptySessionIsNoop() {
            tracker.logTelemetry("", "x");
            assertEquals("", tracker.drain(""));
        }
    }

    @Nested
    class Drain {

        @Test
        void unknownSessionReturnsEmptyString() {
            assertEquals("", tracker.drain("never-seen"));
        }

        @Test
        void drainEmptiesTheQueue() {
            tracker.logTelemetry("S", "x");
            tracker.drain("S");
            assertEquals("", tracker.drain("S"));
        }

        @Test
        void drainOneSessionDoesNotAffectAnother() {
            tracker.logTelemetry("A", "alpha");
            tracker.logTelemetry("B", "beta");
            assertEquals("alpha", tracker.drain("A"));
            assertEquals("beta", tracker.drain("B"));
        }

        @Test
        void mixedRequestAndTelemetryPreserveOrder() {
            tracker.logTelemetry("S", "first\n");
            tracker.logRequest("S", "GET", "/x", 200, 5);
            tracker.logTelemetry("S", "third\n");
            assertEquals(
                    "first\n[BRIDGE] GET /x (200, 5ms)\nthird\n",
                    tracker.drain("S"));
        }
    }

    @Nested
    class ClearSession {

        @Test
        void removesQueuedEntries() {
            tracker.logTelemetry("S", "to be cleared");
            tracker.clearSession("S");
            assertEquals("", tracker.drain("S"));
        }

        @Test
        void unknownSessionIsNoop() {
            tracker.clearSession("never-existed");
            assertEquals("", tracker.drain("never-existed"));
        }

        @Test
        void doesNotAffectOtherSessions() {
            tracker.logTelemetry("A", "keep");
            tracker.logTelemetry("B", "drop");
            tracker.clearSession("B");
            assertEquals("keep", tracker.drain("A"));
            assertEquals("", tracker.drain("B"));
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentProducersDoNotLoseEntries()
                throws InterruptedException {
            int producers = 8;
            int perProducer = 200;
            ExecutorService pool =
                    Executors.newFixedThreadPool(producers);
            CountDownLatch ready = new CountDownLatch(producers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(producers);

            for (int i = 0; i < producers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int j = 0; j < perProducer; j++) {
                            tracker.logTelemetry("S", "x");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdown();

            int expected = producers * perProducer;
            assertEquals(expected, tracker.drain("S").length());
        }
    }
}
