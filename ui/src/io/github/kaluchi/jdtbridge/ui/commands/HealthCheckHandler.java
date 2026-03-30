package io.github.kaluchi.jdtbridge.ui.commands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import io.github.kaluchi.jdtbridge.ui.ProcessUtil;

public class HealthCheckHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Job job = Job.create("JDT Bridge Health Check", monitor -> {
			try {
				ProcessBuilder pb = ProcessUtil.command(
						"jdt", "setup", "--check");
				pb.redirectErrorStream(true);
				Process process = pb.start();

				String output;
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(),
								StandardCharsets.UTF_8))) {
					output = reader.lines().collect(Collectors.joining("\n"));
				}

				int exitCode = process.waitFor();
				String cleaned = stripAnsi(output);

				Display.getDefault().asyncExec(() -> {
					if (exitCode == 0) {
						MessageDialog.openInformation(null,
								"JDT Bridge Health Check", cleaned);
					} else {
						MessageDialog.openWarning(null,
								"JDT Bridge Health Check", cleaned);
					}
				});
			} catch (java.io.IOException e) {
				Display.getDefault().asyncExec(() -> MessageDialog.openError(
						null, "JDT Bridge Health Check",
						"JDT Bridge CLI not found.\n\n"
								+ "Install with:\n"
								+ "  npm install -g @kaluchi/jdtbridge"));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return Status.OK_STATUS;
		});
		job.setUser(true);
		job.schedule();
		return null;
	}

	static String stripAnsi(String text) {
		return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
	}
}
