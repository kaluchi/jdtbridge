package io.github.kaluchi.jdtbridge.support;

import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.Launch;
import org.eclipse.eclemma.core.launching.ICoverageLaunch;
import org.eclipse.jdt.core.IPackageFragmentRoot;

@SuppressWarnings("restriction")
public class FakeCoverageLaunch extends Launch
		implements ICoverageLaunch {

	private final Set<IPackageFragmentRoot> scope;
	private boolean dumpRequested;
	private boolean dumpReset;

	public FakeCoverageLaunch(ILaunchConfiguration config,
			Set<IPackageFragmentRoot> scope) {
		super(config, "coverage", null);
		this.scope = scope;
		setAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP,
				Long.toString(System.currentTimeMillis()));
	}

	@Override
	public Set<IPackageFragmentRoot> getScope() {
		return scope;
	}

	@Override
	public void requestDump(boolean reset) throws CoreException {
		this.dumpRequested = true;
		this.dumpReset = reset;
	}

	public boolean wasDumpRequested() {
		return dumpRequested;
	}

	public boolean wasDumpReset() {
		return dumpReset;
	}
}
