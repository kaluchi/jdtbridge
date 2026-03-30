/**
 * Open a system terminal with a command.
 * Returns a child process whose PID can be monitored —
 * when the terminal closes, the PID dies.
 */

import { spawn } from "node:child_process";

const IS_WINDOWS = process.platform === "win32";

/**
 * Open an external terminal window running the given command.
 * Returns the spawned child process (unref'd, detached).
 *
 * On Windows: spawns cmd.exe directly (detached creates a new
 * console window). PID is cmd.exe — alive while terminal is open.
 *
 * On Unix: spawns bash in a new process group. PID/PGID is bash —
 * process.kill(-pid, 0) checks the entire group.
 *
 * @param {string} title - terminal tab/window title
 * @param {string} cmd - command to execute
 * @returns {import('child_process').ChildProcess}
 */
export function openTerminal(title, cmd) {
  let child;
  if (IS_WINDOWS) {
    child = spawn("cmd.exe", ["/K", `title ${title} && ${cmd}`], {
      stdio: "ignore",
      detached: true,
    });
  } else {
    child = spawn("bash", ["-c", `${cmd}; exec bash`], {
      stdio: "ignore",
      detached: true,
    });
  }
  child.unref();
  return child;
}

/**
 * Check if a terminal process tree is still alive.
 * On Unix: checks entire process group via negative PID.
 * On Windows: checks root PID (cmd.exe).
 */
export function isTerminalAlive(pid) {
  try {
    if (IS_WINDOWS) {
      process.kill(pid, 0);
    } else {
      process.kill(-pid, 0); // check process group
    }
    return true;
  } catch {
    return false;
  }
}
