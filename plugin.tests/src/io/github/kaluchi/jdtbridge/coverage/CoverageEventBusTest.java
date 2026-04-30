package io.github.kaluchi.jdtbridge.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Direct unit tests for the per-{@code coverageId} listener bus.
 * Behaviour-only — no EclEmma classes touched; uses {@link
 * CountingListener} that no-ops every {@code on*} method.
 */
public class CoverageEventBusTest {

    private CoverageEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new CoverageEventBus();
    }

    @Nested
    class SubscribeFire {

        @Test
        void fireWithoutSubscribersIsNoop() {
            assertFalse(bus.hasListeners("X:1"));
        }

        @Test
        void singleSubscriberReceivesEvent() {
            CountingListener l = new CountingListener();
            bus.subscribe("X:1", l);
            bus.fire("X:1", x -> x.onAnalysisLoading(null));
            assertEquals(1, l.loading.get());
        }

        @Test
        void allEventTypesDelivered() {
            CountingListener l = new CountingListener();
            bus.subscribe("X:1", l);
            bus.fire("X:1", x -> x.onDumped(null, 0, 0L));
            bus.fire("X:1", x -> x.onAnalysisLoading(null));
            bus.fire("X:1", x -> x.onAnalysisReady(null));
            bus.fire("X:1", x -> x.onTerminated(null));
            bus.fire("X:1", x -> x.onFailed(null, "test"));
            assertEquals(1, l.dumped.get());
            assertEquals(1, l.loading.get());
            assertEquals(1, l.ready.get());
            assertEquals(1, l.terminated.get());
            assertEquals(1, l.failed.get());
            assertEquals("test", l.failedReasons.get(0));
        }

        @Test
        void multipleSubscribersAllFire() {
            CountingListener a = new CountingListener();
            CountingListener b = new CountingListener();
            CountingListener c = new CountingListener();
            bus.subscribe("X:1", a);
            bus.subscribe("X:1", b);
            bus.subscribe("X:1", c);
            bus.fire("X:1", l -> l.onTerminated(null));
            assertEquals(1, a.terminated.get());
            assertEquals(1, b.terminated.get());
            assertEquals(1, c.terminated.get());
        }

        @Test
        void differentCoverageIdsAreIsolated() {
            CountingListener a = new CountingListener();
            CountingListener b = new CountingListener();
            bus.subscribe("A:1", a);
            bus.subscribe("B:1", b);
            bus.fire("A:1", l -> l.onTerminated(null));
            assertEquals(1, a.terminated.get());
            assertEquals(0, b.terminated.get());
        }
    }

    @Nested
    class Unsubscribe {

        @Test
        void unsubscribeStopsFurtherDelivery() {
            CountingListener l = new CountingListener();
            bus.subscribe("X:1", l);
            bus.fire("X:1", x -> x.onTerminated(null));
            assertEquals(1, l.terminated.get());
            bus.unsubscribe("X:1", l);
            assertFalse(bus.hasListeners("X:1"));
        }

        @Test
        void unsubscribeUnknownIsNoop() {
            CountingListener never = new CountingListener();
            bus.unsubscribe("Bogus:9", never);
            // No exception, no delivery
            assertEquals(0, never.terminated.get());
        }

        @Test
        void unsubscribeOneOfManyKeepsRest() {
            CountingListener a = new CountingListener();
            CountingListener b = new CountingListener();
            bus.subscribe("X:1", a);
            bus.subscribe("X:1", b);
            bus.unsubscribe("X:1", a);
            bus.fire("X:1", l -> l.onTerminated(null));
            assertEquals(0, a.terminated.get());
            assertEquals(1, b.terminated.get());
        }
    }

    @Nested
    class ExceptionIsolation {

        @Test
        void thrownByOneListenerDoesNotBlockOthers() {
            CountingListener after = new CountingListener();
            bus.subscribe("X:1", new CountingListener() {
                @Override public void onTerminated(CoverageRun r) {
                    throw new RuntimeException("intentional");
                }
            });
            bus.subscribe("X:1", after);
            bus.fire("X:1", l -> l.onTerminated(null));
            assertEquals(1, after.terminated.get());
        }
    }

    @Nested
    class Clear {

        @Test
        void clearDropsAllSubscribers() {
            CountingListener a = new CountingListener();
            CountingListener b = new CountingListener();
            bus.subscribe("A:1", a);
            bus.subscribe("B:1", b);
            bus.clear();
            assertFalse(bus.hasListeners("A:1"));
            assertFalse(bus.hasListeners("B:1"));
        }
    }

    /** Listener that just counts how many times each callback
     *  was invoked. CoverageRun arg is ignored — bus
     *  contract is just "deliver call-by-call". */
    private static class CountingListener
            implements CoverageTracker.CoverageEventListener {
        final AtomicInteger dumped = new AtomicInteger();
        final AtomicInteger loading = new AtomicInteger();
        final AtomicInteger ready = new AtomicInteger();
        final AtomicInteger terminated = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final List<String> failedReasons = new ArrayList<>();

        @Override public void onDumped(CoverageRun r, int i, long t) {
            dumped.incrementAndGet();
        }
        @Override public void onAnalysisLoading(CoverageRun r) {
            loading.incrementAndGet();
        }
        @Override public void onAnalysisReady(CoverageRun r) {
            ready.incrementAndGet();
        }
        @Override public void onTerminated(CoverageRun r) {
            terminated.incrementAndGet();
        }
        @Override public void onFailed(CoverageRun r, String reason) {
            failed.incrementAndGet();
            failedReasons.add(reason);
        }
    }
}
