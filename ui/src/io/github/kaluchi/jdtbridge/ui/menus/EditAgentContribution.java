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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import io.github.kaluchi.jdtbridge.ui.Activator;

/**
 * Dynamic menu contribution that shows an "Edit Agent..." cascade submenu
 * listing all saved agent configurations for editing. Only visible when
 * at least one agent configuration exists. Includes a leading separator
 * to visually separate from the agent launch items above.
 */
public class EditAgentContribution extends CompoundContributionItem {

	private static final String LAUNCH_TYPE_ID =
			"io.github.kaluchi.jdtbridge.ui.agentLaunchType";
	private static final String RUN_GROUP_ID =
			"org.eclipse.debug.ui.launchGroup.run";
	private static final ILog LOG =
			Platform.getLog(EditAgentContribution.class);

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
			return new ContributionItem[] {
					new SeparatorItem(),
					new EditAgentCascade(configs)
			};
		} catch (CoreException e) {
			LOG.error("Failed to list agent configurations", e);
			return new ContributionItem[0];
		}
	}

	/** SWT separator menu item. */
	private static class SeparatorItem extends ContributionItem {

		@Override
		public void fill(Menu menu, int index) {
			new MenuItem(menu, SWT.SEPARATOR, index);
		}

		@Override
		public boolean isSeparator() {
			return true;
		}
	}

	/** CASCADE menu item that opens a submenu with agent configs. */
	private static class EditAgentCascade extends ContributionItem {

		private final ILaunchConfiguration[] configs;

		EditAgentCascade(ILaunchConfiguration[] configs) {
			this.configs = configs;
		}

		@Override
		public void fill(Menu menu, int index) {
			MenuItem cascade = new MenuItem(menu, SWT.CASCADE, index);
			cascade.setText("Edit && Run Agent...");

			Menu submenu = new Menu(menu);
			cascade.setMenu(submenu);

			for (int i = 0; i < configs.length; i++) {
				ILaunchConfiguration config = configs[i];
				MenuItem item = new MenuItem(submenu, SWT.PUSH);
				item.setText("&" + (i + 1) + " " + config.getName());

				ImageDescriptor agentIcon =
						Activator.imageDescriptorFromPlugin(
								Activator.PLUGIN_ID, "icons/agent.svg");
				if (agentIcon != null) {
					Image img = agentIcon.createImage();
					item.setImage(img);
					item.addDisposeListener(e -> img.dispose());
				}

				item.addListener(SWT.Selection,
						e -> openEditDialog(config));
			}
		}

		private void openEditDialog(ILaunchConfiguration config) {
			Shell shell = PlatformUI.getWorkbench()
					.getActiveWorkbenchWindow().getShell();
			DebugUITools.openLaunchConfigurationDialog(
					shell, config, RUN_GROUP_ID, null);
		}
	}
}
