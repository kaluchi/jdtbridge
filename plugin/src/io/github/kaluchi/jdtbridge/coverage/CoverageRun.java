package io.github.kaluchi.jdtbridge.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.jdt.core.IPackageFragmentRoot;

/**
 * Bridge-side view of one coverage run. Exactly one entry per
 * {@code coverageId} in {@link CoverageTracker#runs}.
 * <p>
 * For {@link Kind#LIVE} a run is created at
 * {@code launchesAdded(CoverageLaunch)}, accumulates one
 * {@link ICoverageSession} per {@code requestDump}, and stays around
 * until every session is explicitly removed.
 * <p>
 * For {@link Kind#MERGED} and {@link Kind#IMPORTED} a run holds
 * exactly one session and is created at the {@code sessionAdded}
 * that produced it.
 */
@SuppressWarnings("restriction")
final class CoverageRun {

    enum Kind {
        LIVE("live"),
        MERGED("merged"),
        IMPORTED("imported");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    final String coverageId;
    final Kind kind;
    final String configId;
    final String configType;
    final String configTypeId;
    /** Live launch handle — {@code null} for merged/imported. */
    final ILaunch launch;
    /** {@code ATTR_LAUNCH_TIMESTAMP} parsed to long for live;
     *  {@code null} for merged/imported. */
    final Long launchTimestamp;
    /** Wall-clock millis at which this run was first seen by the
     *  bridge. For live runs that's {@code launchesAdded}; for
     *  merged/imported it's {@code sessionAdded}. */
    final long createdAtMillis;

    /** {@link IPackageFragmentRoot} set defining the run's scope.
     *  Stable over the run's lifetime. */
    final Set<IPackageFragmentRoot> coverageScope;

    /** All sessions that belong to this run, latest last. Live runs
     *  grow this list per dump; merged/imported runs hold exactly
     *  one element. */
	final List<ICoverageSession> sessions = new CopyOnWriteArrayList<>();
    /** Wall-clock millis per session in {@link #sessions}, same
     *  index. */
    final List<Long> dumpedAt = new CopyOnWriteArrayList<>();

    /** Coverage IDs of inputs consumed by a {@code merged} run.
     *  Empty for live/imported. */
    final List<String> consumedCoverageIds = new ArrayList<>();

    /** Latest {@link ICoverageSession#getDescription()} — live runs
     *  refresh on every dump, merged/imported keep the original. */
	volatile String description;

    volatile boolean terminated;
    volatile boolean dataReceived;
    volatile boolean analysisLoading;
    volatile boolean analysisReady;
    /** Wall-clock millis the run terminated; {@code null} while
     *  still running. */
    volatile Long terminatedAt;

    private CoverageRun(String coverageId, Kind kind, String configId,
            String configType, String configTypeId, ILaunch launch,
            Long launchTimestamp, long createdAtMillis,
            Set<IPackageFragmentRoot> coverageScope, String description) {
        this.coverageId = coverageId;
        this.kind = kind;
        this.configId = configId;
        this.configType = configType;
        this.configTypeId = configTypeId;
        this.launch = launch;
        this.launchTimestamp = launchTimestamp;
        this.createdAtMillis = createdAtMillis;
        this.coverageScope = coverageScope;
        this.description = description;
    }

    static CoverageRun live(String coverageId, String configId,
            String configType, String configTypeId, ILaunch launch,
            long launchTimestamp,
            Set<IPackageFragmentRoot> coverageScope) {
        long now = System.currentTimeMillis();
        CoverageRun run = new CoverageRun(coverageId, Kind.LIVE, configId,
                configType, configTypeId, launch, launchTimestamp, now,
                coverageScope, null);
        run.terminated = false;
        run.dataReceived = false;
        return run;
    }

    static CoverageRun merged(String coverageId, String configId,
            String configType, String configTypeId,
            Set<IPackageFragmentRoot> coverageScope, String description,
            List<String> consumedCoverageIds) {
        long now = System.currentTimeMillis();
        CoverageRun run = new CoverageRun(coverageId, Kind.MERGED, configId,
                configType, configTypeId, null, null, now,
                coverageScope, description);
        run.terminated = true;
        run.dataReceived = true;
        run.terminatedAt = now;
        run.consumedCoverageIds.addAll(consumedCoverageIds);
        return run;
    }

    static CoverageRun imported(String coverageId,
            Set<IPackageFragmentRoot> coverageScope, String description) {
        long now = System.currentTimeMillis();
        CoverageRun run = new CoverageRun(coverageId, Kind.IMPORTED, null,
                null, null, null, null, now,
                coverageScope, description);
        run.terminated = true;
        run.dataReceived = true;
        run.terminatedAt = now;
        return run;
    }

    int dumpCount() {
        return sessions.size();
    }

    /** Resolves the {@link ICoverageSession} for a {@code :N} dump
     *  suffix on the coverage ID, where {@code N} is 1-based.
     *  Without a suffix returns the latest dump (or the only
     *  session for merged/imported). */
	ICoverageSession resolveSession(Integer dumpIndex) {
        if (sessions.isEmpty()) {
            return null;
        }
        if (dumpIndex == null) {
            return sessions.get(sessions.size() - 1);
        }
        int zero = dumpIndex - 1;
        if (zero < 0 || zero >= sessions.size()) {
            return null;
        }
        return sessions.get(zero);
    }
}
