package io.github.kaluchi.jdtbridge.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

import io.github.kaluchi.jdtbridge.ui.ProcessUtil;

/**
 * Replaces the core plugin's browser-based welcome with a native dialog.
 * Checks whether the JDT Bridge CLI is installed and shows a setup prompt
 * if not.
 */
public class WelcomeStartupHandler implements IStartup {

	private static final ILog LOG = Platform.getLog(WelcomeStartupHandler.class);

	@Override
	public void earlyStartup() {
		Job job = Job.create("JDT Bridge welcome check", this::check);
		job.setSystem(true);
		job.schedule(5000);
	}

	private IStatus check(org.eclipse.core.runtime.IProgressMonitor monitor) {
		try {
			String cliVersion = getCliVersion();
			if (cliVersion == null) {
				Display.getDefault().asyncExec(
						() -> showNotInstalledDialog());
				return Status.OK_STATUS;
			}
			var pluginVersion = getPluginVersion();
			if (isOlder(cliVersion, pluginVersion)) {
				String pv = pluginVersion.getMajor() + "."
						+ pluginVersion.getMinor() + "."
						+ pluginVersion.getMicro();
				Display.getDefault().asyncExec(
						() -> showUpdateDialog(
								cliVersion, pv));
			}
		} catch (Exception e) {
			LOG.warn("Welcome check failed", e);
		}
		return Status.OK_STATUS;
	}

	private String getCliVersion() {
		try {
			ProcessBuilder pb = ProcessUtil.command(
					"jdt", "--version");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			String version = null;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(
							process.getInputStream(),
							StandardCharsets.UTF_8))) {
				version = reader.readLine();
			}
			if (process.waitFor() != 0) return null;
			return version != null ? version.trim() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private org.osgi.framework.Version getPluginVersion() {
		var bundle = Platform.getBundle(
				"io.github.kaluchi.jdtbridge");
		if (bundle == null)
			return org.osgi.framework.Version.emptyVersion;
		return bundle.getVersion();
	}

	private org.osgi.framework.Version parseCliVersion(
			String version) {
		try {
			return org.osgi.framework.Version.parseVersion(
					version);
		} catch (IllegalArgumentException e) {
			return org.osgi.framework.Version.emptyVersion;
		}
	}

	private boolean isOlder(String cliVersion,
			org.osgi.framework.Version pluginVersion) {
		var cli = parseCliVersion(cliVersion);
		return cli.compareTo(pluginVersion) < 0;
	}

	private void showNotInstalledDialog() {
		boolean openTerminal = MessageDialog.openQuestion(null,
				"JDT Bridge Setup",
				"The JDT Bridge CLI is not installed.\n\n"
						+ "Install it with:\n"
						+ "  npm install -g @kaluchi/jdtbridge\n\n"
						+ "Open a terminal to install now?");
		if (openTerminal) {
			openInstallTerminal();
		}
	}

	private void showUpdateDialog(String cliVersion,
			String pluginVersion) {
		boolean openTerminal = MessageDialog.openQuestion(null,
				"JDT Bridge Update",
				"CLI version (" + cliVersion
						+ ") is older than plugin ("
						+ pluginVersion + ").\n\n"
						+ "Update with:\n"
						+ "  npm install -g @kaluchi/jdtbridge\n\n"
						+ "Open a terminal to update now?");
		if (openTerminal) {
			openInstallTerminal();
		}
	}

	private void openInstallTerminal() {
		try {
			ProcessUtil.openTerminal(
					"npm install -g @kaluchi/jdtbridge");
		} catch (Exception e) {
			LOG.error("Failed to open terminal for setup", e);
		}
	}
}
