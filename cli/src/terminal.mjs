/**
 * Cross-platform external terminal launcher.
 *
 * Opens a new terminal window with a command, avoiding shell escaping
 * issues by writing the command to a temp script file (.cmd / .sh).
 *
 * The returned child process has:
 * - `.pid` that stays alive while the terminal window is open
 * - `'exit'` event that fires when the window closes or command finishes
 *
 * Platform strategies:
 *
 * Windows:
 *   cmd.exe /c start /wait cmd.exe /K call <tmp.cmd>
 *   - `start /wait` opens a new console window and blocks the parent
 *     until the child window is closed by the user.
 *   - Parent cmd.exe has no visible window (inherits Eclipse's hidden
 *     console or the current terminal's console).
 *   - `stdio: "pipe"` keeps the parent process alive (with "ignore"
 *     cmd.exe exits immediately on Windows).
 *   - Temp .cmd file avoids cmd.exe argument escaping problems with
 *     paths containing backslashes, spaces, and special characters.
 *
 * macOS:
 *   open -a Terminal <tmp.sh>
 *   - Opens Terminal.app with the script.
 *
 * Linux:
 *   x-terminal-emulator -e <tmp.sh>
 *   - Uses Debian/Ubuntu's terminal alternative system.
 *   - Falls back to whatever is configured as x-terminal-emulator.
 */

import { spawn } from "node:child_process";
import { writeFileSync, chmodSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const IS_WINDOWS = process.platform === "win32";
const IS_MAC = process.platform === "darwin";

/**
 * @param {string} title - terminal window title (Windows only)
 * @param {string} cmd - shell command to execute in the terminal
 * @returns {import('child_process').ChildProcess}
 */
export function openTerminal(title, cmd) {
  const script = writeScript(cmd);

  if (IS_WINDOWS) {
    return spawn("cmd.exe", [
      "/c", "start", "/wait", "cmd.exe", "/K", `call ${script}`,
    ], { stdio: "pipe" });
  }

  if (IS_MAC) {
    return spawn("open", ["-a", "Terminal", script],
      { stdio: "pipe" });
  }

  return spawn("x-terminal-emulator", ["-e", script],
    { stdio: "pipe" });
}

/**
 * Write command to a temp script file.
 * Windows: .cmd batch file.
 * Unix: .sh with shebang and exec bash (keeps terminal open after command).
 */
function writeScript(cmd) {
  const name = `jdt-agent-${Date.now()}`;

  if (IS_WINDOWS) {
    const path = join(tmpdir(), `${name}.cmd`);
    writeFileSync(path, `@${cmd}\n`);
    return path;
  }

  const path = join(tmpdir(), `${name}.sh`);
  writeFileSync(path, `#!/bin/bash\n${cmd}\nexec bash\n`);
  chmodSync(path, 0o755);
  return path;
}
