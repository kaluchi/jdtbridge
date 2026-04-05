/**
 * Git repos overview — shows repos, branches, dirty state.
 * Data from EGit via /projects endpoint (repo + branch per project).
 */

import { execSync } from "node:child_process";
import { basename } from "node:path";
import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { toSandboxPath, normalizePath } from "../paths.mjs";
import { output } from "../output.mjs";
import { formatTable } from "../format/table.mjs";
import { green, yellow } from "../color.mjs";

export async function git(args = []) {
  const sub = args[0];
  if (!sub || sub === "list" || sub.startsWith("-")) {
    return gitList(sub === "list" ? args.slice(1) : args);
  }
  console.error(`Unknown git subcommand: ${sub}\n`);
  console.log(help);
  process.exit(1);
}

async function gitList(args = []) {
  const flags = parseFlags(args);
  const noFiles = args.includes("--no-files");
  const limit = noFiles ? -1 : (flags.limit !== undefined ? Number(flags.limit) : 10);
  const filter = extractPositional(args);
  const projects = await get("/projects");
  if (projects.error) {
    output(args, projects, { text() {} });
    return;
  }

  let repos = reposFromProjects(projects);
  if (filter.length > 0) {
    repos = repos.filter((r) =>
      filter.some((f) => r.name === f || r.name.includes(f)));
  }

  // Build repo data with raw git status lines
  const repoData = repos.map((repo) => {
    const statusOut = gitCmd(repo.path, "git status --short");
    const dirtyLines = statusOut.split("\n").filter((l) => l.trim());
    return { repo, dirtyLines };
  });

  // JSON gets clean data (no ANSI)
  const data = repoData.map(({ repo, dirtyLines }) => ({
    name: repo.name,
    path: toSandboxPath(repo.path),
    branch: repo.branch,
    dirty: dirtyLines.length,
    files: dirtyLines.map((l) => stripAnsi(l.trim())),
  }));

  output(args, data, {
    empty: "(no git repos)",
    text() {
      const headers = ["REPO", "STATUS", "PATH", "BRANCH"];
      const rows = [];
      for (const { repo, dirtyLines } of repoData) {
        const repoPath = toSandboxPath(repo.path);
        const status = dirtyLines.length > 0
          ? yellow(`${dirtyLines.length} modified`) : green("clean");
        rows.push([repo.name, status, repoPath, repo.branch]);

        if (dirtyLines.length > 0 && limit >= 0) {
          const show = limit === 0 ? dirtyLines : dirtyLines.slice(0, limit);
          for (const l of show) rows.push([l.trim(), "", "", ""]);
          if (limit > 0 && dirtyLines.length > limit) {
            rows.push([`...+${dirtyLines.length - limit} more`, "", "", ""]);
          }
        }
      }
      console.log(formatTable(headers, rows));
    },
  });
}

function reposFromProjects(projects) {
  const seen = new Map();
  for (const p of projects) {
    const repo = normalizePath(p.repo || "");
    if (!repo) continue;
    if (!seen.has(repo)) {
      seen.set(repo, { path: repo, name: basename(repo), branch: p.branch || "" });
    }
  }
  return [...seen.values()];
}

const ANSI_RE = /\x1b\[[0-9;]*m/g;
function stripAnsi(s) { return s.replace(ANSI_RE, ""); }

function gitCmd(repoPath, cmd) {
  try {
    return execSync(cmd, {
      cwd: repoPath, encoding: "utf8", timeout: 5000,
      env: { ...process.env, GIT_CONFIG_COUNT: "1",
        GIT_CONFIG_KEY_0: "color.status", GIT_CONFIG_VALUE_0: "always" },
    }).trim();
  } catch { return ""; }
}

export const help = `Show git repos, branches, and modified files.

Usage:  jdt git [repo...] [--limit N] [--json]

Arguments:
  repo          filter by repo name (substring match)

Flags:
  --limit <N>   max dirty files per repo (default: 10, 0 = all)
  --no-files    hide file list, show only summary table
  --json        output as JSON

Examples:
  jdt git                all repos
  jdt git m8             only repos matching "m8"
  jdt git m8 --limit 0   m8 repos, all dirty files
  jdt git --json`;
