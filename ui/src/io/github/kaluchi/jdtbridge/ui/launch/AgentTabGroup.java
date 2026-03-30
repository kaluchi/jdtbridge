package io.github.kaluchi.jdtbridge.ui.launch;

import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.CommonTab;
import org.eclipse.debug.ui.EnvironmentTab;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;

/**
 * Tab group for the JDT Bridge Agent launch configuration.
 * Agent tab (provider, agent, working dir) + Environment tab
 * (custom env vars) + Common tab (console, encoding).
 */
public class AgentTabGroup extends AbstractLaunchConfigurationTabGroup {

	@Override
	public void createTabs(ILaunchConfigurationDialog dialog,
			String mode) {
		setTabs(new ILaunchConfigurationTab[] {
				new AgentTab(),
				new EnvironmentTab(),
				new CommonTab(),
		});
	}
}
