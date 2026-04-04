// Terminal session identification — detect unique tab/pane ID
// from environment variables set by terminal emulators.
//
// Used by jdt use for per-terminal workspace pinning.
// See docs/jdt-use-spec.md for rationale and priority chain.

import { execSync } from "node:child_process";

/**
 * Priority chain of terminal session env vars.
 * Each terminal emulator sets a unique identifier per tab/pane.
 * These are inherited by all child processes — through shell →
 * agent → sandbox bash → jdt CLI.
 */
const TERMINAL_SESSION_VARS = [
  "WT_SESSION",        // Windows Terminal — per tab
  "ITERM_SESSION_ID",  // iTerm2 — per tab/pane
  "TERM_SESSION_ID",   // macOS Terminal.app, VS Code, JetBrains — per session
  "TMUX_PANE",         // tmux — per pane
  "STY",               // GNU screen — per session
  "ConEmuPID",         // ConEmu — per instance
];

/**
 * Returns the first available terminal session identifier.
 * Returns null if no terminal ID can be determined.
 * @returns {string|null}
 */
export function resolveTerminalId() {
  for (const v of TERMINAL_SESSION_VARS) {
    if (process.env[v]) return process.env[v];
  }
  // Unix: tty device as fallback (skip on Windows — tty command absent)
  if (process.platform !== "win32") {
    try {
      return execSync("tty", {
        stdio: ["pipe", "pipe", "pipe"],
        encoding: "utf8",
      }).trim();
    } catch { /* no tty (non-interactive shell, Docker, etc.) */ }
  }
  return null;
}
