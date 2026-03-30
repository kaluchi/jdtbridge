package io.github.kaluchi.jdtbridge.ui.preferences;

public class PreferenceConstants {

	public static final String TERMINAL_COMMAND = "terminalCommand";

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
