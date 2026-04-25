package io.github.kaluchi.jdtbridge.coverage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionListener;
import org.eclipse.eclemma.core.ISessionManager;
import org.eclipse.eclemma.core.analysis.IJavaCoverageListener;
import org.eclipse.eclemma.core.analysis.IJavaModelCoverage;
import org.eclipse.eclemma.core.launching.ICoverageLaunch;

import io.github.kaluchi.jdtbridge.LaunchAttrs;
import io.github.kaluchi.jdtbridge.Log;

/**
 * Holds the bridge-side index from {@code coverageId} to
 * {@link CoverageRun}, fed by Eclipse listeners.
 * <p>
 * Listens to:
 * <ul>
 *   <li>{@link ILaunchesListener2} — pre-creates a {@link CoverageRun}
 *       at {@code launchesAdded} for each {@link ICoverageLaunch};
 *       flips {@code terminated} on {@code launchesTerminated}.</li>
 *   <li>{@link ISessionListener} — classifies new sessions as
 *       {@code live} (when an existing live run matches) or defers
 *       to {@link #classifyDeferred} for {@code merged}/{@code imported}
 *       resolution.</li>
 *   <li>{@link IJavaCoverageListener} — flips
 *       {@link CoverageRun#analysisLoading} /
 *       {@link CoverageRun#analysisReady} on the active run.</li>
 * </ul>
 * <p>
 * The bundle that owns the EclEmma classes referenced here
 * ({@link CoverageTools} et al.) is required at runtime — this
 * class is loaded only after {@link CoverageBridge#isAvailable()}
 * confirms the bundle is present.
 */
final class CoverageTracker
        implements ISessionListener, ILaunchesListener2,
                   IJavaCoverageListener {

    private final CoverageAnalyzer analyzer;

    private final ConcurrentHashMap<String, CoverageRun> runs =
            new ConcurrentHashMap<>();

    /** Reverse index from session identity → owning run's
     *  {@code coverageId}. Identity-based ({@link ICoverageSession}
     *  has no {@code equals} override, see spec). */
    private final ConcurrentHashMap<ICoverageSession, String>
            sessionToRunId = new ConcurrentHashMap<>();

    /** Sessions awaiting merged/imported classification — entries
     *  are added in {@link #sessionAdded} when the live-match fails
     *  and removed in {@link #classifyDeferred}. The {@code burst}
     *  list is appended in {@link #sessionRemoved} for tracked
     *  sessions removed before classification settles. */
    private final ConcurrentHashMap<ICoverageSession,
            PendingClassification> pending = new ConcurrentHashMap<>();

    /** {@link Job#belongsTo(Object)} family for the deferred
     *  classification jobs — exposed so tests can
     *  {@code Job.getJobManager().join(CLASSIFY_FAMILY, ...)}. */
    static final Object CLASSIFY_FAMILY = new Object();

    private final CoverageEventBus events = new CoverageEventBus();

    /** Test/inspection accessor — lets tests drive listener
     *  callbacks without going through real EclEmma listeners. */
    CoverageEventBus eventBus() {
        return events;
    }

    /** Streaming-side hook. {@code on*} methods are called
     *  synchronously from the listener thread. Implementations
     *  must be non-blocking and exception-safe. */
    interface CoverageEventListener {
        void onDumped(CoverageRun run, int dumpIndex,
                long dumpTimestamp);
        void onAnalysisLoading(CoverageRun run);
        void onAnalysisReady(CoverageRun run);
        void onTerminated(CoverageRun run);
        void onFailed(CoverageRun run, String reason);
    }

    void addCoverageListener(String coverageId,
            CoverageEventListener listener) {
        events.subscribe(coverageId, listener);
    }

    void removeCoverageListener(String coverageId,
            CoverageEventListener listener) {
        events.unsubscribe(coverageId, listener);
    }

    private void fireDumped(CoverageRun run) {
        int dumpIndex = run.sessions.size();
        long dumpTimestamp = run.dumpedAt.isEmpty()
                ? System.currentTimeMillis()
                : run.dumpedAt.get(run.dumpedAt.size() - 1);
        events.fire(run.coverageId,
                l -> l.onDumped(run, dumpIndex, dumpTimestamp));
    }

    private void fireAnalysisLoading(CoverageRun run) {
        events.fire(run.coverageId, l -> l.onAnalysisLoading(run));
    }

    private void fireAnalysisReady(CoverageRun run) {
        events.fire(run.coverageId, l -> l.onAnalysisReady(run));
    }

    private void fireTerminated(CoverageRun run) {
        events.fire(run.coverageId, l -> l.onTerminated(run));
    }

    private void fireFailed(CoverageRun run, String reason) {
        events.fire(run.coverageId, l -> l.onFailed(run, reason));
    }

    /** Per-millisecond collision counter for {@code merged:}/{@code
     *  imported:} coverage IDs. Bumped when a generated ID was
     *  already issued (i.e. two events landed in the same
     *  millisecond). */
    private final AtomicLong collisionSeq = new AtomicLong();

    private volatile String activeCoverageId;
    private volatile boolean started;

    /** Production wiring — creates a fresh, dedicated
     *  {@link CoverageAnalyzer} for cache invalidation. */
    CoverageTracker() {
        this(new CoverageAnalyzer());
    }

    /** Test/router wiring — pass an explicit analyzer so the
     *  invalidation hook is observable. */
    CoverageTracker(CoverageAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        ILaunchManager mgr = launchManager();
        if (mgr != null) {
            mgr.addLaunchListener(this);
            // Retroactively pick up live coverage launches that
            // started before the listener was attached.
            for (ILaunch launch : mgr.getLaunches()) {
                if (launch instanceof ICoverageLaunch
                        && !launch.isTerminated()) {
                    registerLiveLaunch(launch);
                }
            }
        }
        ISessionManager sm = CoverageTools.getSessionManager();
        sm.addSessionListener(this);
        // Retroactively classify sessions already in the manager.
        for (ICoverageSession s : sm.getSessions()) {
            classifyImmediately(s);
        }
        ICoverageSession active = sm.getActiveSession();
        if (active != null) {
            activeCoverageId = sessionToRunId.get(active);
        }
        CoverageTools.addJavaCoverageListener(this);
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        ILaunchManager mgr = launchManager();
        if (mgr != null) {
            mgr.removeLaunchListener(this);
        }
        CoverageTools.getSessionManager().removeSessionListener(this);
        CoverageTools.removeJavaCoverageListener(this);
        runs.clear();
        sessionToRunId.clear();
        pending.clear();
        events.clear();
        activeCoverageId = null;
    }

    /** Test/inspection accessor — read-only snapshot keyed by
     *  {@code coverageId}. */
    java.util.Map<String, CoverageRun> snapshot() {
        return java.util.Map.copyOf(runs);
    }

    String activeCoverageId() {
        return activeCoverageId;
    }

    /** Swap {@link #activeCoverageId} and clear analysis flags on
     *  the previously-active run. The previous run is no longer the
     *  analysis target, so its {@code analysisLoading}/
     *  {@code analysisReady} bits would be stale once EclEmma starts
     *  re-loading for the new session. */
    private void setActiveCoverageId(String newId) {
        String previous = activeCoverageId;
        activeCoverageId = newId;
        if (previous == null || previous.equals(newId)) {
            return;
        }
        CoverageRun stale = runs.get(previous);
        if (stale != null) {
            stale.analysisLoading = false;
            stale.analysisReady = false;
        }
    }

    CoverageRun byCoverageId(String coverageId) {
        if (coverageId == null) {
            return null;
        }
        // Strip optional :N dump suffix when looking up the run —
        // the suffix addresses a session within the run.
        int colon = coverageId.lastIndexOf(':');
        if (colon > 0) {
            String tail = coverageId.substring(colon + 1);
            if (tail.matches("\\d+")) {
                CoverageRun viaSuffix = runs.get(coverageId);
                if (viaSuffix != null) {
                    return viaSuffix;
                }
                String head = coverageId.substring(0, colon);
                CoverageRun viaHead = runs.get(head);
                if (viaHead != null) {
                    return viaHead;
                }
            }
        }
        return runs.get(coverageId);
    }

    // -- ILaunchesListener2 --

    @Override
    public void launchesAdded(ILaunch[] launches) {
        for (ILaunch launch : launches) {
            if (launch instanceof ICoverageLaunch) {
                registerLiveLaunch(launch);
            }
        }
    }

    @Override
    public void launchesChanged(ILaunch[] launches) {
        // Refresh process metadata when it lands later — currently
        // a no-op; CoverageRun reads launch state on demand.
    }

    @Override
    public void launchesTerminated(ILaunch[] launches) {
        long now = System.currentTimeMillis();
        for (ILaunch launch : launches) {
            for (CoverageRun run : runs.values()) {
                if (run.launch == launch) {
                    run.terminated = true;
                    if (run.terminatedAt == null) {
                        run.terminatedAt = now;
                    }
                    fireTerminated(run);
                }
            }
        }
    }

    @Override
    public void launchesRemoved(ILaunch[] launches) {
        // Intentionally keep tracker state — runs survive removal
        // from the launch manager. Mirrors LaunchTracker.
    }

    private void registerLiveLaunch(ILaunch launch) {
        ILaunchConfiguration cfg = launch.getLaunchConfiguration();
        if (cfg == null) {
            return;
        }
        String configId = cfg.getName();
        Long timestamp = parseLaunchTimestamp(launch);
        if (timestamp == null) {
            // No timestamp attribute — bridge requires one to
            // form a stable coverageId, so we skip silently.
            return;
        }
        String coverageId = configId + ":" + timestamp;
        if (runs.containsKey(coverageId)) {
            return;
        }
        String configType = null;
        String configTypeId = null;
        try {
            ILaunchConfigurationType type = cfg.getType();
            if (type != null) {
                configType = type.getName();
                configTypeId = type.getIdentifier();
            }
        } catch (CoreException e) {
            Log.warn("Failed reading launch type for " + configId, e);
        }
        Set<org.eclipse.jdt.core.IPackageFragmentRoot> scope =
                ((ICoverageLaunch) launch).getScope();
        CoverageRun run = CoverageRun.live(coverageId, configId,
                configType, configTypeId, launch, timestamp, scope);
        runs.put(coverageId, run);
    }

    // -- ISessionListener --

    @Override
    public void sessionAdded(ICoverageSession session) {
        classifyImmediately(session);
    }

    @Override
    public void sessionRemoved(ICoverageSession session) {
        analyzer.invalidate(session);
        String coverageId = sessionToRunId.remove(session);
        if (coverageId != null) {
            CoverageRun run = runs.get(coverageId);
            if (run != null) {
                int idx = run.sessions.indexOf(session);
                if (idx >= 0) {
                    run.sessions.remove(idx);
                    if (idx < run.dumpedAt.size()) {
                        run.dumpedAt.remove(idx);
                    }
                }
                if (run.sessions.isEmpty()) {
                    runs.remove(coverageId);
                }
            }
        }
        // Append removed coverageId to any in-flight pending
        // classification — distinguishes merged from imported.
        if (coverageId != null) {
            for (PendingClassification p : pending.values()) {
                p.removedCoverageIds.add(coverageId);
            }
        }
    }

    @Override
    public void sessionActivated(ICoverageSession session) {
        if (session == null) {
            setActiveCoverageId(null);
            return;
        }
        String coverageId = sessionToRunId.get(session);
        if (coverageId != null) {
            setActiveCoverageId(coverageId);
        }
        // If the session isn't classified yet, finalizePending will
        // call setActiveCoverageId once it lands.
    }

    private void classifyImmediately(ICoverageSession session) {
        CoverageRun liveRun = matchLiveRun(session);
        if (liveRun != null) {
            appendLiveDump(liveRun, session);
            return;
        }
        // Defer — sessionRemoved bursts that arrive synchronously
        // after this call distinguish merged from imported.
        PendingClassification pc = new PendingClassification(session);
        pending.put(session, pc);
        Job job = new Job("jdtbridge coverage classify") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                return finalizePending(session);
            }

            @Override
            public boolean belongsTo(Object family) {
                return family == CLASSIFY_FAMILY;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Synchronously finalize every pending classification. Useful
     *  in tests and on read paths that need a fully-settled view. */
    void flushPending() {
        for (ICoverageSession s : new ArrayList<>(pending.keySet())) {
            finalizePending(s);
        }
    }

    private CoverageRun matchLiveRun(ICoverageSession session) {
        ILaunchConfiguration cfg = session.getLaunchConfiguration();
        if (cfg == null) {
            return null;
        }
        String configId = cfg.getName();
        for (CoverageRun run : runs.values()) {
            if (run.kind == CoverageRun.Kind.LIVE
                    && configId.equals(run.configId)
                    && !run.terminated) {
                return run;
            }
        }
        return null;
    }

    private void appendLiveDump(CoverageRun run,
            ICoverageSession session) {
        run.sessions.add(session);
        run.dumpedAt.add(System.currentTimeMillis());
        run.dataReceived = true;
        run.description = session.getDescription();
        sessionToRunId.put(session, run.coverageId);
        fireDumped(run);
    }

    private IStatus finalizePending(ICoverageSession session) {
        // Lock barrier — wait for the merger's synchronized
        // dispatch to release before reading removedCoverageIds.
        CoverageTools.getSessionManager().getSessions();

        PendingClassification pc = pending.remove(session);
        if (pc == null) {
            return Status.OK_STATUS;
        }
        long now = System.currentTimeMillis();
        boolean isMerged = pc.removedCoverageIds.size() >= 2;
        String coverageId = uniqueId(isMerged ? "merged" : "imported",
                now);
        Set<org.eclipse.jdt.core.IPackageFragmentRoot> scope =
                new HashSet<>(session.getScope());
        String description = session.getDescription();
        CoverageRun run;
        if (isMerged) {
            ILaunchConfiguration cfg = session.getLaunchConfiguration();
            String configId = cfg != null ? cfg.getName() : null;
            String configType = null;
            String configTypeId = null;
            if (cfg != null) {
                try {
                    if (cfg.getType() != null) {
                        configType = cfg.getType().getName();
                        configTypeId = cfg.getType().getIdentifier();
                    }
                } catch (CoreException e) {
                    Log.warn("Failed reading merged config type", e);
                }
            }
            run = CoverageRun.merged(coverageId, configId, configType,
                    configTypeId, scope, description,
                    new ArrayList<>(pc.removedCoverageIds));
        } else {
            run = CoverageRun.imported(coverageId, scope, description);
        }
        run.sessions.add(session);
        run.dumpedAt.add(now);
        runs.put(coverageId, run);
        sessionToRunId.put(session, coverageId);
        fireDumped(run);
        if (session.equals(
                CoverageTools.getSessionManager().getActiveSession())) {
            setActiveCoverageId(coverageId);
            applyCoverageState(run,
                    CoverageTools.getJavaModelCoverage());
        }
        return Status.OK_STATUS;
    }

    /** Generate {@code "<prefix>:<millis>"} or, on intra-millisecond
     *  collision with a previously-issued ID, append {@code "#<seq>"}. */
    private String uniqueId(String prefix, long millis) {
        String base = prefix + ":" + millis;
        if (!runs.containsKey(base) && !pendingHas(base)) {
            return base;
        }
        long seq;
        String candidate;
        do {
            seq = collisionSeq.incrementAndGet();
            candidate = base + "#" + seq;
        } while (runs.containsKey(candidate));
        return candidate;
    }

    private boolean pendingHas(String coverageId) {
        for (PendingClassification p : pending.values()) {
            if (coverageId.equals(p.assignedCoverageId)) {
                return true;
            }
        }
        return false;
    }

    // -- IJavaCoverageListener --

    @Override
    public void coverageChanged() {
        ICoverageSession active =
                CoverageTools.getSessionManager().getActiveSession();
        if (active == null) return;
        String coverageId = sessionToRunId.get(active);
        if (coverageId == null) return;
        CoverageRun run = runs.get(coverageId);
        if (run == null) return;
        applyCoverageState(run,
                CoverageTools.getJavaModelCoverage());
    }

    private void applyCoverageState(CoverageRun run,
            IJavaModelCoverage coverage) {
        if (coverage == IJavaModelCoverage.LOADING) {
            run.analysisLoading = true;
            run.analysisReady = false;
            fireAnalysisLoading(run);
        } else if (coverage == null) {
            run.analysisLoading = false;
            run.analysisReady = false;
            if (!run.terminated) {
                fireFailed(run, "analysis-cancelled");
            }
        } else {
            run.analysisLoading = false;
            run.analysisReady = true;
            fireAnalysisReady(run);
        }
    }

    // -- helpers --

    private static ILaunchManager launchManager() {
        return LaunchAttrs.launchManager();
    }

    private static Long parseLaunchTimestamp(ILaunch launch) {
        return LaunchAttrs.launchTimestamp(launch);
    }

    /** State held while a non-live {@code sessionAdded} waits for
     *  classification. Mutated only inside listener callbacks (single
     *  thread per dispatch) — no lock needed for the lists. */
    private static final class PendingClassification {
        final ICoverageSession session;
        final long createdAtMillis = System.currentTimeMillis();
        final List<String> removedCoverageIds = new ArrayList<>();
        volatile String assignedCoverageId;

        PendingClassification(ICoverageSession session) {
            this.session = session;
        }
    }
}
