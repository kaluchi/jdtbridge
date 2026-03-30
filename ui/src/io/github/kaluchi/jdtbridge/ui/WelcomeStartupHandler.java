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

import io.github.kaluchi.jdtbridge.ui.commands.OpenTerminalHandler;
import io.github.kaluchi.jdtbridge.ui.preferences.PreferenceConstants;

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
			if (isCliInstalled()) {
				return Status.OK_STATUS;
			}
			Display.getDefault().asyncExec(this::showSetupDialog);
		} catch (Exception e) {
			LOG.warn("Welcome check failed", e);
		}
		return Status.OK_STATUS;
	}

	private boolean isCliInstalled() {
		try {
			ProcessBuilder pb = ProcessUtil.command("jdt", "--version");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(),
							StandardCharsets.UTF_8))) {
				reader.lines().forEach(line -> {}); // drain
			}
			return process.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private void showSetupDialog() {
		boolean openTerminal = MessageDialog.openQuestion(null,
				"JDT Bridge Setup",
				"The JDT Bridge CLI is not installed.\n\n"
						+ "Install it with:\n"
						+ "  npm install -g @kaluchi/jdtbridge\n\n"
						+ "Open a terminal to install now?");
		if (openTerminal) {
			try {
				new ProcessBuilder(
						OpenTerminalHandler.buildCommand(
								PreferenceConstants.defaultTerminalCommand(),
								org.eclipse.core.resources.ResourcesPlugin
										.getWorkspace().getRoot()
										.getLocation().toFile()))
						.start();
			} catch (Exception e) {
				LOG.error("Failed to open terminal for setup", e);
			}
		}
	}
}
