package io.github.kaluchi.jdtbridge.coverage;

import java.util.Map;

import org.eclipse.core.runtime.Platform;

import com.google.gson.JsonObject;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * Single guarded entry point for {@code /coverage/*} HTTP routes.
 * <p>
 * Loaded eagerly when {@code HttpServer} is constructed, but
 * carefully avoids referencing any class that imports
 * {@code org.eclipse.eclemma.*} or {@code org.jacoco.*} at class
 * initialization. The downstream {@link CoverageRouter} is only
 * loaded when {@link #dispatch} is called AND the EclEmma bundle is
 * present — so an Eclipse without EclEmma installed never triggers
 * a {@code NoClassDefFoundError}.
 */
public class CoverageBridge {

    private static final String ECLEMMA_BUNDLE =
            "org.eclipse.eclemma.core";

    private static final boolean AVAILABLE =
            Platform.getBundle(ECLEMMA_BUNDLE) != null;

    /** Lazy-initialized {@link CoverageRouter} — {@code Object} type
     *  keeps the class out of the field signature so it isn't
     *  resolved until {@link #dispatch} is invoked. */
    private volatile Object router;

    /** True when the {@code org.eclipse.eclemma.core} bundle is
     *  installed in the current Eclipse instance. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Activate the EclEmma listeners (session manager, launch
     *  manager, coverage loader). No-op when EclEmma absent. Safe
     *  to call multiple times. */
    public void start() {
        if (!AVAILABLE) {
            return;
        }
        ((CoverageRouter) router()).start();
    }

    /** Deregister the listeners and clear bridge state. */
    public void stop() {
        if (!AVAILABLE) {
            return;
        }
        Object r = router;
        if (r != null) {
            ((CoverageRouter) r).stop();
        }
    }

    /**
     * Dispatch a {@code /coverage/*} request. When EclEmma is
     * absent, returns {@link #notInstalledError()} JSON without
     * touching any EclEmma class.
     */
    public String dispatch(String path, Map<String, String> params,
            String body, ProjectScope scope) throws Exception {
        if (!AVAILABLE) {
            return notInstalledError();
        }
        return ((CoverageRouter) router()).dispatch(
                path, params, body, scope);
    }

    /** {@code GET /coverage/session/stream} — write JSONL events
     *  to {@code out}. Bypasses {@link #dispatch} because the HTTP
     *  layer streams directly to the socket. */
    public void streamSession(java.io.OutputStream out,
            String coverageId) {
        if (!AVAILABLE) {
            try {
                out.write(streamNotInstalledEvent().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            } catch (java.io.IOException e) {
                // peer gone — nothing to do
            }
            return;
        }
        CoverageRouter r = (CoverageRouter) router();
        CoverageProgressStreamer.stream(out, coverageId,
                r.tracker(), r.analyzer());
    }

    /** JSONL line emitted by {@link #streamSession} when EclEmma
     *  is absent — uses the {@code event}/{@code reason} shape
     *  consumers of {@code /coverage/session/stream} expect, so
     *  parsers don't have to special-case the not-installed path. */
    static String streamNotInstalledEvent() {
        var obj = new JsonObject();
        obj.addProperty("event", "failed");
        obj.addProperty("reason", "coverage-not-installed");
        return obj.toString();
    }

    private synchronized Object router() {
        if (router == null) {
            router = new CoverageRouter();
        }
        return router;
    }

    /** JSON returned for any {@code /coverage/*} hit when the
     *  EclEmma bundle is missing. Package-private so tests can
     *  assert the exact shape without invoking {@link #dispatch}. */
    static String notInstalledError() {
        var obj = new JsonObject();
        obj.addProperty("error", "coverage-not-installed");
        obj.addProperty("message",
                "EclEmma plugin (" + ECLEMMA_BUNDLE
                        + ") is not installed in this Eclipse "
                        + "instance.");
        return obj.toString();
    }
}
