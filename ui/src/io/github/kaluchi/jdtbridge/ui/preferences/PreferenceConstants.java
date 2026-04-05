package io.github.kaluchi.jdtbridge.ui.preferences;

import io.github.kaluchi.jdtbridge.ServerPreferences;

public class PreferenceConstants {

	public static final String TERMINAL_COMMAND = "terminalCommand";

	// Local socket
	public static final String LOCAL_PORT = ServerPreferences.LOCAL_PORT;
	public static final String LOCAL_REGENERATE_TOKEN =
			ServerPreferences.LOCAL_REGENERATE_TOKEN;
	public static final String LOCAL_TOKEN = ServerPreferences.LOCAL_TOKEN;

	// Remote socket
	public static final String REMOTE_ENABLED =
			ServerPreferences.REMOTE_ENABLED;
	public static final String REMOTE_PORT = ServerPreferences.REMOTE_PORT;
	public static final String REMOTE_REGENERATE_TOKEN =
			ServerPreferences.REMOTE_REGENERATE_TOKEN;
	public static final String REMOTE_TOKEN =
			ServerPreferences.REMOTE_TOKEN;

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
