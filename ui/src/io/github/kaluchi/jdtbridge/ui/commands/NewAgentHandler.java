package io.github.kaluchi.jdtbridge.ui.commands;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationListener;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.FrameworkUtil;

/**
 * Opens Run Configurations dialog focused on the JDT Bridge Agent
 * launch type. Temporarily collapses all other types in the tree
 * so only agents are visible, then restores expansion on close.
 */
public class NewAgentHandler extends AbstractHandler {

	private static final String LAUNCH_TYPE_ID =
			"io.github.kaluchi.jdtbridge.ui.agentLaunchType";
	private static final String RUN_GROUP =
			"org.eclipse.debug.ui.launchGroup.run";
	private static final String SECTION_NAME =
			IDebugUIConstants.PLUGIN_ID
			+ ".LAUNCH_CONFIGURATIONS_DIALOG_SECTION";
	private static final String EXPANDED_NODES_KEY =
			IDebugUIConstants.PLUGIN_ID + ".EXPANDED_NODES";

	@Override
	public Object execute(ExecutionEvent event)
			throws ExecutionException {
		ILaunchManager manager = DebugPlugin.getDefault()
				.getLaunchManager();
		ILaunchConfigurationType type = manager
				.getLaunchConfigurationType(LAUNCH_TYPE_ID);
		if (type == null) {
			return null;
		}

		IDialogSettings section = getDialogSection();
		String savedExpansion = section != null
				? section.get(EXPANDED_NODES_KEY) : null;

		// Track configs created during dialog lifetime
		Set<ILaunchConfiguration> created =
				ConcurrentHashMap.newKeySet();
		ILaunchConfigurationListener listener =
				new ILaunchConfigurationListener() {
			@Override
			public void launchConfigurationAdded(
					ILaunchConfiguration config) {
				created.add(config);
			}

			@Override
			public void launchConfigurationChanged(
					ILaunchConfiguration config) {
			}

			@Override
			public void launchConfigurationRemoved(
					ILaunchConfiguration config) {
				created.remove(config);
			}
		};
		manager.addLaunchConfigurationListener(listener);

		// Expand only our type, collapse everything else
		if (section != null) {
			section.put(EXPANDED_NODES_KEY, LAUNCH_TYPE_ID);
		}

		try {
			DebugUITools.openLaunchConfigurationDialogOnGroup(
					HandlerUtil.getActiveShell(event),
					new StructuredSelection(type),
					RUN_GROUP);
		} finally {
			manager.removeLaunchConfigurationListener(listener);
			deleteUnnamed(created);
			if (section != null) {
				section.put(EXPANDED_NODES_KEY, savedExpansion);
			}
		}
		return null;
	}

	private static void deleteUnnamed(
			Set<ILaunchConfiguration> configs) {
		for (ILaunchConfiguration config : configs) {
			if (config.getName().isBlank()) {
				try {
					config.delete();
				} catch (CoreException e) {
					// best effort
				}
			}
		}
	}

	private static IDialogSettings getDialogSection() {
		try {
			IDialogSettings root = PlatformUI
					.getDialogSettingsProvider(
							FrameworkUtil.getBundle(
									DebugUITools.class))
					.getDialogSettings();
			return root.getSection(SECTION_NAME);
		} catch (Exception e) {
			return null;
		}
	}
}
