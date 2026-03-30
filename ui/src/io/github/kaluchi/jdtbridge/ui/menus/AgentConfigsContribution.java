package io.github.kaluchi.jdtbridge.ui.menus;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.actions.CompoundContributionItem;

/**
 * Dynamic menu contribution that lists saved JDT Bridge Agent
 * launch configurations. Each item runs the config on click.
 */
public class AgentConfigsContribution extends CompoundContributionItem {

	private static final String LAUNCH_TYPE_ID =
			"io.github.kaluchi.jdtbridge.ui.agentLaunchType";
	private static final ILog LOG =
			Platform.getLog(AgentConfigsContribution.class);

	@Override
	protected ContributionItem[] getContributionItems() {
		ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = mgr
				.getLaunchConfigurationType(LAUNCH_TYPE_ID);
		if (type == null) {
			return new ContributionItem[0];
		}

		try {
			ILaunchConfiguration[] configs = mgr
					.getLaunchConfigurations(type);
			if (configs.length == 0) {
				return new ContributionItem[0];
			}

			ContributionItem[] items = new ContributionItem[configs.length];
			for (int i = 0; i < configs.length; i++) {
				items[i] = new AgentConfigItem(configs[i]);
			}
			return items;
		} catch (CoreException e) {
			LOG.error("Failed to list agent configurations", e);
			return new ContributionItem[0];
		}
	}

	private static class AgentConfigItem extends ContributionItem {

		private final ILaunchConfiguration config;

		AgentConfigItem(ILaunchConfiguration config) {
			this.config = config;
		}

		@Override
		public void fill(Menu menu, int index) {
			MenuItem item = new MenuItem(menu, SWT.PUSH, index);
			item.setText(config.getName());
			item.addListener(SWT.Selection, e -> {
				try {
					config.launch(ILaunchManager.RUN_MODE, null, true);
				} catch (CoreException ex) {
					LOG.error("Failed to launch " + config.getName(), ex);
				}
			});
		}
	}
}
