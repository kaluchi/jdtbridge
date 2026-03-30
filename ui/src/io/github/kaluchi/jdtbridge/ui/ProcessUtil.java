package io.github.kaluchi.jdtbridge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for building OS-aware process commands.
 * On Windows, wraps commands with {@code cmd /c} so that
 * npm-linked .cmd scripts (like {@code jdt}) are found.
 */
public class ProcessUtil {

	private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
			.toLowerCase().contains("win");

	public static ProcessBuilder command(String... args) {
		List<String> cmd = new ArrayList<>();
		if (IS_WINDOWS) {
			cmd.add("cmd");
			cmd.add("/c");
		}
		cmd.addAll(List.of(args));
		return new ProcessBuilder(cmd);
	}
}
