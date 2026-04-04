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
		store.setDefault(PreferenceConstants.HTTP_BIND_ADDRESS,
				PreferenceConstants.BIND_LOOPBACK);
		store.setDefault(PreferenceConstants.HTTP_FIXED_PORT, 0);
	}
}
