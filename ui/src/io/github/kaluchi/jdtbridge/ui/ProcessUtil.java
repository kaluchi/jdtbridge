package io.github.kaluchi.jdtbridge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for building OS-aware process commands.
 * <p>
 * On Windows, wraps commands with {@code cmd /c} so that npm-linked
 * {@code .cmd} scripts (like {@code jdt}) are found.
 * <p>
 * On macOS/Linux, wraps commands with an interactive shell ({@code -ic})
 * so that the user's full {@code PATH} (e.g. Homebrew, nvm) is available.
 * Eclipse GUI apps on macOS inherit a minimal system PATH that does not
 * include {@code /opt/homebrew/bin} or {@code ~/.nvm/} paths.
 * Using {@code -ic} (not {@code -lc}) because most users configure PATH
 * in {@code .zshrc}/{@code .bashrc} (interactive), not in
 * {@code .zprofile}/{@code .bash_profile} (login).
 */
public class ProcessUtil {

	private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
			.toLowerCase().contains("win");
	private static final boolean IS_MAC = System.getProperty("os.name", "")
			.toLowerCase().contains("mac");

	public static ProcessBuilder command(String... args) {
		List<String> cmd = new ArrayList<>();
		if (IS_WINDOWS) {
			cmd.add("cmd");
			cmd.add("/c");
			cmd.addAll(List.of(args));
		} else if (IS_MAC) {
			String shell = System.getenv("SHELL");
			if (shell == null || shell.isEmpty()) {
				shell = "/bin/zsh";
			}
			cmd.add(shell);
			cmd.add("-ic");
			cmd.add(joinArgs(args));
		} else {
			// Linux: also use login shell for consistent PATH
			String shell = System.getenv("SHELL");
			if (shell == null || shell.isEmpty()) {
				shell = "/bin/bash";
			}
			cmd.add(shell);
			cmd.add("-ic");
			cmd.add(joinArgs(args));
		}
		return new ProcessBuilder(cmd);
	}

	private static String joinArgs(String... args) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < args.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			if (args[i].contains(" ") || args[i].contains("'")
					|| args[i].contains("\"")) {
				sb.append('\'');
				sb.append(args[i].replace("'", "'\\''"));
				sb.append('\'');
			} else {
				sb.append(args[i]);
			}
		}
		return sb.toString();
	}
}
