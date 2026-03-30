/**
 * Open a system terminal with a command.
 * Returns a child process with working PID and exit event.
 *
 * Windows: `start /wait` opens new window, blocks until closed.
 * Unix: spawns terminal emulator as detached process group.
 */

import { spawn } from "node:child_process";

const IS_WINDOWS = process.platform === "win32";
const IS_MAC = process.platform === "darwin";

/**
 * Open an external terminal window running the given command.
 *
 * The returned child process:
 * - `.pid` stays alive while terminal window is open
 * - `'exit'` event fires when window closes or command finishes
 *
 * @param {string} title - terminal tab/window title
 * @param {string} cmd - command to execute
 * @returns {import('child_process').ChildProcess}
 */
export function openTerminal(title, cmd) {
  if (IS_WINDOWS) {
    // start /wait: opens new console window, blocks until it closes
    // The spawned cmd.exe parent stays alive → trackable PID + exit event
    return spawn("cmd.exe", [
      "/c", "start", `/wait`, `"${title}"`, "cmd.exe", "/K", cmd,
    ], { stdio: "ignore", detached: true, windowsVerbatimArguments: true });
  }

  if (IS_MAC) {
    return spawn("open", ["-a", "Terminal", "--args", "-e",
      `bash -c '${cmd}; exec bash'`],
      { stdio: "ignore", detached: true });
  }

  // Linux: try x-terminal-emulator (Debian/Ubuntu), fall back to xterm
  return spawn("x-terminal-emulator", [
    "-e", `bash -c '${cmd}; exec bash'`,
  ], { stdio: "ignore", detached: true });
}
