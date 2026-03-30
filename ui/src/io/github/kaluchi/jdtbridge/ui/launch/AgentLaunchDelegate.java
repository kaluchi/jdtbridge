package io.github.kaluchi.jdtbridge.ui.launch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;

import io.github.kaluchi.jdtbridge.ui.Activator;
import io.github.kaluchi.jdtbridge.ui.ProcessUtil;

/**
 * Launch delegate for JDT Bridge Agent configurations.
 * Calls {@code jdt agent run <provider> <agent> --name <id>}
 * and registers the process with Eclipse's launch system.
 */
public class AgentLaunchDelegate implements ILaunchConfigurationDelegate {

	public static final String ATTR_PROVIDER =
			Activator.PLUGIN_ID + ".provider";
	public static final String ATTR_AGENT =
			Activator.PLUGIN_ID + ".agent";
	public static final String ATTR_WORKING_DIR =
			Activator.PLUGIN_ID + ".workingDir";

	@Override
	public void launch(ILaunchConfiguration config, String mode,
			ILaunch launch, IProgressMonitor monitor)
			throws CoreException {

		String provider = config.getAttribute(ATTR_PROVIDER, "local");
		String agent = config.getAttribute(ATTR_AGENT, "claude");
		String workDir = config.getAttribute(ATTR_WORKING_DIR, "");
		String sessionId = config.getName();

		List<String> cmd = new ArrayList<>();
		cmd.addAll(List.of("jdt", "agent", "run", provider, agent,
				"--name", sessionId));

		try {
			ProcessBuilder pb = ProcessUtil.command(
					cmd.toArray(String[]::new));
			pb.redirectErrorStream(true);
			if (workDir != null && !workDir.isBlank()) {
				pb.directory(new File(workDir));
			}

			Process process = pb.start();
			DebugPlugin.newProcess(launch, process, sessionId);
		} catch (IOException e) {
			throw new CoreException(new Status(IStatus.ERROR,
					Activator.PLUGIN_ID,
					"Failed to launch agent: " + e.getMessage(), e));
		}
	}
}
