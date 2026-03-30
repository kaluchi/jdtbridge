package io.github.kaluchi.jdtbridge.ui.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.core.model.IProcess;

import com.google.gson.JsonObject;

import io.github.kaluchi.jdtbridge.ui.Activator;
import io.github.kaluchi.jdtbridge.ui.BridgeConnection;
import io.github.kaluchi.jdtbridge.ui.ProcessUtil;

/**
 * Launch delegate for JDT Bridge Agent configurations.
 * <p>
 * Writes a session config to {@code ~/.jdtbridge/sessions/<id>.json},
 * then calls {@code jdt agent run --session <id>}. The CLI reads the
 * session file and bootstraps the provider from it.
 * <p>
 * Session file is cleaned up when the process terminates.
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
		String workDirRaw = config.getAttribute(ATTR_WORKING_DIR, "");
		String sessionId = config.getName();

		String workDir = "";
		if (workDirRaw != null && !workDirRaw.isBlank()) {
			workDir = VariablesPlugin.getDefault()
					.getStringVariableManager()
					.performStringSubstitution(workDirRaw);
		}

		BridgeConnection bridge = BridgeConnection.find();
		if (bridge == null) {
			throw new CoreException(new Status(IStatus.ERROR,
					Activator.PLUGIN_ID,
					"JDT Bridge is not running."));
		}

		Path sessionFile = writeSessionFile(
				sessionId, provider, agent, workDir, bridge);

		try {
			ProcessBuilder pb = ProcessUtil.command(
					"jdt", "agent", "run",
					"--session", sessionId);
			pb.redirectErrorStream(true);

			Process process = pb.start();
			DebugPlugin.newProcess(launch, process, sessionId);

			// Clean up session file when our process terminates
			registerCleanup(launch, sessionFile);
		} catch (IOException e) {
			// Clean up on failure too
			deleteQuietly(sessionFile);
			throw new CoreException(new Status(IStatus.ERROR,
					Activator.PLUGIN_ID,
					"Failed to launch agent: " + e.getMessage(), e));
		}
	}

	private Path writeSessionFile(String sessionId, String provider,
			String agent, String workDir, BridgeConnection bridge)
			throws CoreException {
		Path sessionsDir = BridgeConnection.resolveHome()
				.resolve("sessions");
		try {
			Files.createDirectories(sessionsDir);
			Path sessionFile = sessionsDir.resolve(sessionId + ".json");

			var obj = new JsonObject();
			obj.addProperty("provider", provider);
			obj.addProperty("agent", agent);
			obj.addProperty("workingDir", workDir);
			obj.addProperty("bridgePort", bridge.port);
			obj.addProperty("bridgeToken", bridge.token);
			obj.addProperty("bridgeHost", "127.0.0.1");

			Files.writeString(sessionFile, obj.toString() + "\n");
			return sessionFile;
		} catch (IOException e) {
			throw new CoreException(new Status(IStatus.ERROR,
					Activator.PLUGIN_ID,
					"Failed to write session file: " + e.getMessage(),
					e));
		}
	}

	private void registerCleanup(ILaunch launch, Path sessionFile) {
		DebugPlugin.getDefault().addDebugEventListener(
				new IDebugEventSetListener() {
					@Override
					public void handleDebugEvents(DebugEvent[] events) {
						for (DebugEvent event : events) {
							if (event.getKind() == DebugEvent.TERMINATE
									&& event.getSource() instanceof IProcess p
									&& p.getLaunch() == launch) {
								deleteQuietly(sessionFile);
								DebugPlugin.getDefault()
										.removeDebugEventListener(this);
								return;
							}
						}
					}
				});
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// best effort
		}
	}
}
