/**
 * Open a system terminal with a command.
 * Used by agent providers to launch agents in external terminals.
 */

import { spawn } from "node:child_process";

const IS_WINDOWS = process.platform === "win32";

/**
 * Open an external terminal window running the given command.
 * Returns the spawned child process (already unref'd).
 *
 * @param {string} title - terminal tab/window title
 * @param {string} cmd - command to execute
 * @returns {import('child_process').ChildProcess}
 */
export function openTerminal(title, cmd) {
  let child;
  if (IS_WINDOWS) {
    child = spawn("wt.exe", [
      "new-tab", "--title", title, "cmd", "/K", cmd,
    ], { stdio: "ignore", detached: true });
  } else {
    child = spawn("x-terminal-emulator", [
      "-e", `bash -c '${cmd}; exec bash'`,
    ], { stdio: "ignore", detached: true });
  }
  child.unref();
  return child;
}
