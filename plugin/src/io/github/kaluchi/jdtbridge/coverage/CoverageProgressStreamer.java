package io.github.kaluchi.jdtbridge.coverage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

/**
 * JSONL streaming for {@code GET /coverage/session/stream}.
 * Emits the current snapshot, then forwards live transitions
 * captured by {@link CoverageTracker.CoverageEventListener} until
 * the run hits a terminal state ({@code terminated &&
 * !analysisLoading}) or the client disconnects.
 */
public final class CoverageProgressStreamer {

    /** Thrown when the underlying socket signals the client has
     *  gone away. The HTTP server's stream-handling code maps this
     *  back to a normal disconnect. Mirrors
     *  {@code TestProgressStreamer.StreamClosedException}. */
    public static final class StreamClosedException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        StreamClosedException(IOException cause) {
            super(cause);
        }
    }

    private CoverageProgressStreamer() {
    }

    /**
     * Stream events for {@code coverageId} to {@code out} until the
     * run reaches a terminal state. Returns normally when the
     * stream ends; throws {@link StreamClosedException} when the
     * client disconnects mid-stream.
     */
    public static void stream(OutputStream out, String coverageId,
            CoverageTracker tracker) {
        if (coverageId == null || coverageId.isBlank()) {
            writeLine(out, errorEvent("missing-coverageId"));
            return;
        }
        CoverageRun run = tracker.byCoverageId(coverageId);
        if (run == null) {
            writeLine(out, errorEvent("coverage-not-found"));
            return;
        }
        // Always emit the snapshot first.
        writeLine(out, snapshotEvent(run));

        // Already-terminal: nothing more to wait for.
        if (isTerminal(run)) {
            writeLine(out, terminatedEvent(run));
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        CoverageTracker.CoverageEventListener listener =
                new CoverageTracker.CoverageEventListener() {
            @Override
            public void onDumped(CoverageRun r, int dumpIndex,
                    long dumpTimestamp) {
                writeLine(out, dumpedEvent(r, dumpIndex,
                        dumpTimestamp));
            }

            @Override
            public void onAnalysisLoading(CoverageRun r) {
                writeLine(out, analysisEvent(r,
                        "analysisLoading"));
            }

            @Override
            public void onAnalysisReady(CoverageRun r) {
                writeLine(out, analysisEvent(r,
                        "analysisReady"));
                if (isTerminal(r)) {
                    writeLine(out, terminatedEvent(r));
                    done.countDown();
                }
            }

            @Override
            public void onTerminated(CoverageRun r) {
                writeLine(out, terminatedEvent(r));
                done.countDown();
            }
        };

        tracker.addCoverageListener(run.coverageId, listener);
        try {
            // Re-check terminal state after subscription — covers
            // the race where the run terminated between snapshot
            // and listener registration.
            CoverageRun snapshot = tracker.byCoverageId(
                    run.coverageId);
            if (snapshot != null && isTerminal(snapshot)) {
                writeLine(out, terminatedEvent(snapshot));
                return;
            }
            done.await(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tracker.removeCoverageListener(run.coverageId, listener);
        }
    }

    private static boolean isTerminal(CoverageRun run) {
        return run.terminated && !run.analysisLoading;
    }

    private static String snapshotEvent(CoverageRun run) {
        var obj = new JsonObject();
        obj.addProperty("event", "snapshot");
        obj.addProperty("coverageId", run.coverageId);
        obj.addProperty("coverageSessionKind", run.kind.wireName());
        obj.addProperty("terminated", run.terminated);
        obj.addProperty("dataReceived", run.dataReceived);
        obj.addProperty("analysisLoading", run.analysisLoading);
        obj.addProperty("analysisReady", run.analysisReady);
        obj.addProperty("dumpCount", run.dumpCount());
        return obj.toString();
    }

    private static String dumpedEvent(CoverageRun run,
            int dumpIndex, long dumpTimestamp) {
        var obj = new JsonObject();
        obj.addProperty("event", "dumped");
        obj.addProperty("coverageId", run.coverageId);
        obj.addProperty("dumpIndex", dumpIndex);
        obj.addProperty("dumpTimestamp", dumpTimestamp);
        return obj.toString();
    }

    private static String analysisEvent(CoverageRun run,
            String eventName) {
        var obj = new JsonObject();
        obj.addProperty("event", eventName);
        obj.addProperty("coverageId", run.coverageId);
        obj.addProperty("dumpIndex", run.dumpCount());
        return obj.toString();
    }

    private static String terminatedEvent(CoverageRun run) {
        var obj = new JsonObject();
        obj.addProperty("event", "terminated");
        obj.addProperty("coverageId", run.coverageId);
        if (run.terminatedAt != null) {
            obj.addProperty("terminatedAt", run.terminatedAt);
        }
        obj.addProperty("dataReceived", run.dataReceived);
        return obj.toString();
    }

    private static String errorEvent(String reason) {
        var obj = new JsonObject();
        obj.addProperty("event", "failed");
        obj.addProperty("reason", reason);
        return obj.toString();
    }

    private static void writeLine(OutputStream out, String json) {
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new StreamClosedException(e);
        } catch (UncheckedIOException e) {
            throw new StreamClosedException(
                    e.getCause() instanceof IOException io
                            ? io : new IOException(e));
        }
    }
}
