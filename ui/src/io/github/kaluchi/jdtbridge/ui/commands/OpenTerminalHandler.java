package io.github.kaluchi.jdtbridge.ui.commands;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.kaluchi.jdtbridge.ui.Activator;
import io.github.kaluchi.jdtbridge.ui.preferences.PreferenceConstants;

public class OpenTerminalHandler extends AbstractHandler {

	private static final ILog LOG = Platform.getLog(OpenTerminalHandler.class);

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		File dir = resolveDirectory(event);
		String terminalCmd = Activator.getDefault().getPreferenceStore()
				.getString(PreferenceConstants.TERMINAL_COMMAND);

		try {
			List<String> cmd = buildCommand(terminalCmd, dir);
			new ProcessBuilder(cmd).directory(dir).start();
		} catch (IOException e) {
			LOG.error("Failed to open terminal: " + terminalCmd, e);
			MessageDialog.openError(null, "Open Terminal",
					"Failed to launch terminal: " + terminalCmd
							+ "\n\nCheck Window > Preferences > JDT Bridge.");
		}
		return null;
	}

	private File resolveDirectory(ExecutionEvent event) {
		ISelection sel = HandlerUtil.getCurrentSelection(event);
		if (sel instanceof IStructuredSelection structured
				&& !structured.isEmpty()) {
			Object element = structured.getFirstElement();
			IProject project = null;
			if (element instanceof IProject p) {
				project = p;
			} else if (element instanceof IAdaptable adaptable) {
				project = adaptable.getAdapter(IProject.class);
			}
			if (project != null && project.getLocation() != null) {
				return project.getLocation().toFile();
			}
		}
		return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
	}

	public static List<String> buildCommand(String terminalCmd, File dir) {
		List<String> cmd = new ArrayList<>();
		String path = dir.getAbsolutePath();

		if (terminalCmd.contains("wt.exe") || terminalCmd.equals("wt")) {
			cmd.add(terminalCmd);
			cmd.add("-d");
			cmd.add(path);
		} else if (terminalCmd.contains("cmd.exe") || terminalCmd.equals("cmd")) {
			cmd.add("cmd.exe");
			cmd.add("/c");
			cmd.add("start");
			cmd.add("cmd.exe");
			cmd.add("/K");
			cmd.add("cd /d " + path);
		} else {
			// Generic: split on spaces, set working directory via ProcessBuilder
			for (String part : terminalCmd.split("\\s+")) {
				cmd.add(part);
			}
		}
		return cmd;
	}
}
