package io.github.kaluchi.jdtbridge.ui.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import io.github.kaluchi.jdtbridge.ui.Activator;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		store.setDefault(PreferenceConstants.TERMINAL_COMMAND,
				PreferenceConstants.defaultTerminalCommand());

		// Local socket
		store.setDefault(PreferenceConstants.LOCAL_PORT, 0);
		store.setDefault(PreferenceConstants.LOCAL_REGENERATE_TOKEN, true);

		// Remote socket
		store.setDefault(PreferenceConstants.REMOTE_ENABLED, false);
		store.setDefault(PreferenceConstants.REMOTE_PORT, 0);
		store.setDefault(PreferenceConstants.REMOTE_REGENERATE_TOKEN, false);
	}
}
