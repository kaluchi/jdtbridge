package io.github.kaluchi.jdtbridge.ui.menus;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.actions.CompoundContributionItem;

import io.github.kaluchi.jdtbridge.ui.Activator;

/**
 * Dynamic menu contribution that lists saved JDT Bridge Agent
 * launch configurations. Numbered for keyboard access (Alt+J, 1/2/3).
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
				items[i] = new AgentConfigItem(configs[i], i + 1);
			}
			return items;
		} catch (CoreException e) {
			LOG.error("Failed to list agent configurations", e);
			return new ContributionItem[0];
		}
	}

	private static class AgentConfigItem extends ContributionItem {

		private final ILaunchConfiguration config;
		private final int number;

		AgentConfigItem(ILaunchConfiguration config, int number) {
			this.config = config;
			this.number = number;
		}

		@Override
		public void fill(Menu menu, int index) {
			MenuItem item = new MenuItem(menu, SWT.PUSH, index);
			// &N prefix = mnemonic for keyboard access
			item.setText("&" + number + " " + config.getName());

			ImageDescriptor icon = Activator.imageDescriptorFromPlugin(
					Activator.PLUGIN_ID, "icons/agent.svg");
			if (icon != null) {
				Image img = icon.createImage();
				item.setImage(img);
				item.addDisposeListener(e -> img.dispose());
			}

			item.addListener(SWT.Selection, e ->
					DebugUITools.launch(config,
							ILaunchManager.RUN_MODE));
		}
	}
}
