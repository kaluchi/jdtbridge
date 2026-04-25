package io.github.kaluchi.jdtbridge.coverage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.analysis.IJavaModelCoverage;
import org.eclipse.eclemma.internal.core.analysis.SessionAnalyzer;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.SessionInfo;

/**
 * Bridge-side analysis cache. Wraps EclEmma's internal
 * {@link SessionAnalyzer#processSession} so that
 * {@link IJavaModelCoverage} plus the raw
 * {@link SessionInfo} / {@link ExecutionData} streams are cached
 * keyed by {@link ICoverageSession} identity.
 * <p>
 * EclEmma's {@code JavaCoverageLoader} only ever holds the analysis
 * for the currently-active session, and exposes
 * {@link IJavaModelCoverage} only — not the JaCoCo session-info /
 * execution-data lists. The bridge needs all three on every
 * {@code /coverage/session} request, so it runs its own analyzer
 * pass and keeps the result.
 * <p>
 * Invalidation hooks into {@link CoverageTracker#sessionRemoved}:
 * sessions are immutable, analysis is deterministic given the same
 * scope + execution-data source, so the cache only needs to drop
 * entries for sessions the manager has discarded.
 */
@SuppressWarnings("restriction")
final class CoverageAnalyzer {

    /** Snapshot returned by {@link #ensureAnalyzed}. The three
     *  collections are immutable views — callers must not mutate. */
    static final class CachedAnalysis {
        final IJavaModelCoverage modelCoverage;
        final List<SessionInfo> jacocoSessionInfos;
        final Collection<ExecutionData> jacocoExecData;
        final long computedAtMillis;

        CachedAnalysis(IJavaModelCoverage modelCoverage,
                List<SessionInfo> infos,
                Collection<ExecutionData> data,
                long computedAtMillis) {
            this.modelCoverage = modelCoverage;
            this.jacocoSessionInfos = infos;
            this.jacocoExecData = data;
            this.computedAtMillis = computedAtMillis;
        }
    }

    /** Identity-keyed: {@link ICoverageSession#equals} is identity
     *  too, but using {@link IdentityHashMap} makes that explicit
     *  and eliminates the equals/hashCode round-trip. */
    private final Map<ICoverageSession, CachedAnalysis> cache =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Return a cached analysis if present, otherwise run
     *  {@link SessionAnalyzer#processSession} synchronously and
     *  cache the result.
     *  <p>
     *  May throw {@link CoreException} when the underlying analyzer
     *  fails (typically on a workspace with stale class files or
     *  unresolved package fragment roots). */
    CachedAnalysis ensureAnalyzed(ICoverageSession session)
            throws CoreException {
        CachedAnalysis hit = cache.get(session);
        if (hit != null) {
            return hit;
        }
        synchronized (session) {
            hit = cache.get(session);
            if (hit != null) {
                return hit;
            }
            SessionAnalyzer analyzer = new SessionAnalyzer();
            IJavaModelCoverage modelCoverage =
                    analyzer.processSession(session,
                            new NullProgressMonitor());
            List<SessionInfo> infos = new ArrayList<>(
                    analyzer.getSessionInfos());
            Collection<ExecutionData> data = new ArrayList<>(
                    analyzer.getExecutionData());
            CachedAnalysis fresh = new CachedAnalysis(modelCoverage,
                    Collections.unmodifiableList(infos),
                    Collections.unmodifiableCollection(data),
                    System.currentTimeMillis());
            cache.put(session, fresh);
            return fresh;
        }
    }

    /** Drop the cache entry for one session. Called by
     *  {@link CoverageTracker#sessionRemoved}. */
    void invalidate(ICoverageSession session) {
        cache.remove(session);
    }

    /** Drop every cache entry. Called when the bridge stops. */
    void clear() {
        cache.clear();
    }

    /** Test/inspection accessor — count of cached sessions. */
    int cacheSize() {
        return cache.size();
    }

    /** Test/inspection accessor — true when this session has a
     *  cached analysis. */
    boolean isCached(ICoverageSession session) {
        return cache.containsKey(session);
    }
}
