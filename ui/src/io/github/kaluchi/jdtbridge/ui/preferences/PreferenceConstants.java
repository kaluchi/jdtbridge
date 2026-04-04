package io.github.kaluchi.jdtbridge.ui.preferences;

import io.github.kaluchi.jdtbridge.ServerPreferences;

public class PreferenceConstants {

	public static final String TERMINAL_COMMAND = "terminalCommand";

	/** Bind address key — delegated to plugin's ServerPreferences. */
	public static final String HTTP_BIND_ADDRESS =
			ServerPreferences.HTTP_BIND_ADDRESS;

	/** Fixed port key — delegated to plugin's ServerPreferences. */
	public static final String HTTP_FIXED_PORT =
			ServerPreferences.HTTP_FIXED_PORT;

	public static final String BIND_LOOPBACK =
			ServerPreferences.BIND_LOOPBACK;
	public static final String BIND_ALL =
			ServerPreferences.BIND_ALL;

	public static String defaultTerminalCommand() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			return "wt.exe";
		} else if (os.contains("mac")) {
			return "open -a Terminal";
		} else {
			return "x-terminal-emulator";
		}
	}
}
