package io.github.kaluchi.jdtbridge.ui.commands;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;

/**
 * Opens Run Configurations dialog with a new JDT Bridge Agent
 * configuration pre-created.
 */
public class NewAgentHandler extends AbstractHandler {

	private static final String LAUNCH_TYPE_ID =
			"io.github.kaluchi.jdtbridge.ui.agentLaunchType";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		try {
			ILaunchManager manager = DebugPlugin.getDefault()
					.getLaunchManager();
			ILaunchConfigurationType type = manager
					.getLaunchConfigurationType(LAUNCH_TYPE_ID);
			if (type == null) {
				return null;
			}

			ILaunchConfigurationWorkingCopy wc = type.newInstance(
					null, manager.generateLaunchConfigurationName(
							"agent"));
			DebugUITools.openLaunchConfigurationDialog(
					null, wc, "org.eclipse.debug.ui.launchGroup.run",
					null);
		} catch (Exception e) {
			throw new ExecutionException(
					"Failed to open agent configuration", e);
		}
		return null;
	}
}
