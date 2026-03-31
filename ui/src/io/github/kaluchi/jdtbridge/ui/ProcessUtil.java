package io.github.kaluchi.jdtbridge.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

	/**
	 * Open an interactive terminal window with a command.
	 * Writes a temp script so the command runs and the terminal
	 * stays open for the user to see output.
	 * Same approach as cli/src/terminal.mjs.
	 */
	public static Process openTerminal(String command)
			throws IOException {
		List<String> cmd = new ArrayList<>();
		if (IS_WINDOWS) {
			Path script = Files.createTempFile("jdt-", ".cmd");
			Files.writeString(script,
					"@echo.\r\n@" + command
							+ "\r\n@echo.\r\n@pause\r\n");
			cmd.add("cmd.exe");
			cmd.add("/c");
			cmd.add("start");
			cmd.add("JDT Bridge");
			cmd.add("cmd.exe");
			cmd.add("/K");
			cmd.add("call " + script);
		} else {
			Path script = Files.createTempFile("jdt-", ".sh");
			Files.writeString(script,
					"#!/bin/bash\n" + command + "\nexec bash\n");
			script.toFile().setExecutable(true);
			if (IS_MAC) {
				cmd.add("open");
				cmd.add("-a");
				cmd.add("Terminal");
				cmd.add(script.toString());
			} else {
				cmd.add("x-terminal-emulator");
				cmd.add("-e");
				cmd.add(script.toString());
			}
		}
		return new ProcessBuilder(cmd).start();
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
