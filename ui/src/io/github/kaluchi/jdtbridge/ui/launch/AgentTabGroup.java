package io.github.kaluchi.jdtbridge.ui.launch;

import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.CommonTab;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;

/**
 * Tab group for the JDT Bridge Agent launch configuration.
 * Shows agent settings tab + standard Eclipse common tab.
 */
public class AgentTabGroup extends AbstractLaunchConfigurationTabGroup {

	@Override
	public void createTabs(ILaunchConfigurationDialog dialog,
			String mode) {
		setTabs(new ILaunchConfigurationTab[] {
				new AgentTab(),
				new CommonTab(),
		});
	}
}
