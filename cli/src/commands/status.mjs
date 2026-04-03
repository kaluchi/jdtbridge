/**
 * Composite dashboard — shows Eclipse IDE state in one call.
 * Each section maps to a standalone jdt command for incremental refresh.
 *
 * Architecture:
 *   Renderer  — returns { title, cmd, body, description? } (pure data)
 *   Compositor — assembles sections into markdown with code fences
 *   Helpers   — reposFromServer, gitCmd, ago (stateless utilities)
 */

import { execSync } from "node:child_process";
import { basename } from "node:path";

// ---- Public API ----

const SECTION_NAMES = ["intro", "git", "editors", "problems", "launch-configs", "launches", "tests", "projects", "help", "guide"];

export async function status(args) {
  const jsonFlag = args.includes("--json");
  const quiet = args.includes("-q") || args.includes("--quiet");
  const requested = args.filter((a) => !a.startsWith("-"));
  const sections = requested.length > 0
    ? requested.filter((s) => SECTION_NAMES.includes(s))
    : SECTION_NAMES.filter((s) => s !== "guide" && s !== "help");

  if (jsonFlag) {
    const dataSections = sections.filter((s) => !new Set(["intro", "guide", "help"]).has(s));
    const result = {};
    for (const name of dataSections) {
      const cmd = JSON_COMMANDS[name];
      if (!cmd) continue;
      try {
        result[name] = JSON.parse(cliCmd(cmd));
      } catch {
        result[name] = null;
      }
    }
    console.log(JSON.stringify(result, null, 2));
    return;
  }

  const META_SECTIONS = new Set(["intro", "guide", "help"]);
  const results = [];

  const showExtras = !quiet && requested.length === 0;
  // Intro first — context for agents seeing this for the first time
  if (showExtras || sections.includes("intro")) results.push(introSection());

  for (const name of sections) {
    if (META_SECTIONS.has(name)) continue;
    const renderer = RENDERERS[name];
    if (renderer) results.push(await renderer());
  }

  // Help before guide
  if (showExtras || sections.includes("help")) results.push(helpSection());
  // Guide last
  if (showExtras) results.push(guideSection());
  if (sections.includes("guide") && !showExtras) results.push(guideSection());

  const bare = results.length === 1;
  console.log(results.map((s) => formatSection(s, { bare, quiet })).join("\n\n"));
}

// ---- Compositor ----

/**
 * Format a section object into markdown.
 *
 * @param {Object} section - { title, cmd, body, description? }
 * @param {Object} opts
 * @param {boolean} opts.bare  - single section: body only, no header/fence
 * @param {boolean} opts.quiet - suppress description
 */
function formatSection({ title, cmd, body, description }, { bare, quiet }) {
  if (bare) return body;
  const desc = (!quiet && description) ? description + "\n\n" : "";
  return `## ${title}\n\n${desc}\`\`\`bash\n$ ${cmd}\n${body}\n\`\`\``;
}

/** JSON commands for --json composite output. */
const JSON_COMMANDS = {
  git: "jdt git --json",
  editors: "jdt editors --json",
  problems: "jdt problems --json",
  "launch-configs": "jdt launch configs --json",
  launches: "jdt launch list --json",
  tests: "jdt test runs --json",
  projects: "jdt projects --json",
};

// ---- Renderers (return { title, cmd, body }) ----

const RENDERERS = {
  git: renderGit,
  editors: renderEditors,
  problems: renderProblems,
  "launch-configs": renderLaunchConfigs,
  launches: renderLaunches,
  tests: renderTests,
  projects: renderProjects,
};

async function renderGit() {
  return {
    title: "Git", cmd: "jdt git list --no-files",
    body: cliCmd("jdt git list --no-files"),
    description:
      "Eclipse EGit — Git Repositories view, Team menu.\n"
      + "REPO from project locations. BRANCH = HEAD ref. STATUS = git status.",
  };
}

async function renderEditors() {
  return {
    title: "Editors", cmd: "jdt editors",
    body: cliCmd("jdt editors"),
    description:
      "Eclipse editor area — open tabs. Active tab marked >.\n"
      + "jdt open <FQMN> opens a type in the Java Editor (F3 equivalent).",
  };
}

async function renderProblems() {
  return {
    title: "Problems", cmd: "jdt problems --json",
    body: cliCmd("jdt problems --json"),
    description:
      "Eclipse Problems view — IMarker.PROBLEM markers (errors, warnings).\n"
      + "Updated on every build. [] = clean workspace.",
  };
}

async function renderLaunchConfigs() {
  return {
    title: "Launch Configs", cmd: "jdt launch configs",
    body: cliCmd("jdt launch configs"),
    description:
      "Eclipse Run Configurations dialog (Run > Run Configurations...).\n"
      + "CONFIGTYPE = ILaunchConfigurationType. CONFIGID = launch config name.",
  };
}

async function renderLaunches() {
  return {
    title: "Launches", cmd: "jdt launch list",
    body: cliCmd("jdt launch list"),
    description:
      "Eclipse Debug view + Console view. Running and terminated processes.\n"
      + "LaunchId = handle for jdt launch logs/stop/clear.",
  };
}

async function renderTests() {
  return {
    title: "Tests", cmd: "jdt test runs",
    body: cliCmd("jdt test runs"),
    description:
      "Eclipse JUnit view. PDE test runner for plugin tests, JUnit for plain.\n"
      + "TestRunId = handle for jdt test status.",
  };
}

async function renderProjects() {
  return {
    title: "Projects", cmd: "jdt projects",
    body: cliCmd("jdt projects"),
    description:
      "Eclipse Package Explorer / Project Explorer.\n"
      + "LOCATION = filesystem path. REPO = git root if EGit-managed.",
  };
}

function introSection() {
  return {
    title: "Intro",
    cmd: "jdt status intro",
    body: `Eclipse IDE is running and connected to this terminal via jdt CLI.
jdt is a bridge that exposes the IDE's functions as terminal commands.
Commands cover Java search, compilation, testing, and refactoring.
Everything the developer sees and does in Eclipse GUI
the AI assistant can access through jdt CLI against the same
running instance. Same IDE, different interface.

The sections below are live output from that instance.
Each section is produced by a command shown in its header:
  ## Git     -> jdt status git
  ## Problems -> jdt status problems
That command can be run standalone for a fresh snapshot.
Several commands can be combined: jdt status git editors projects

-q suppresses intro, help, guide, and section descriptions.`,
  };
}

function helpSection() {
  return {
    title: "Help",
    cmd: "jdt help",
    body: cliCmd("jdt help"),
  };
}

function guideSection() {
  return {
    title: "Guide",
    cmd: "jdt status guide",
    body: `After editing code:

  jdt problems                check compilation after edit
  jdt problems --project X    check one project only (faster)
  jdt test run FQN -f -q      run one test, stream result
  jdt build --project X       trigger build if auto-build is off

Refreshing the dashboard:

  jdt status -q               all sections, no intro/help/guide
  jdt status editors problems   combine specific sections
  jdt help <command>          detailed usage for any command`,
  };
}

// ---- Helpers (stateless, exported for testing) ----

export function reposFromServer(projects) {
  const seen = new Map();
  for (const p of projects) {
    const repo = (p.repo || "").replace(/\\/g, "/");
    if (!repo) continue;
    if (!seen.has(repo)) {
      seen.set(repo, { path: repo, name: basename(repo), branch: p.branch || "", projects: [] });
    }
    seen.get(repo).projects.push(p.name || p);
  }
  return [...seen.values()];
}

export function buildDirtyMap(repos) {
  const map = {};
  for (const repo of repos) {
    const statusOut = gitCmd(repo.path, "git status --short");
    for (const line of statusOut.split("\n")) {
      if (!line.trim()) continue;
      const dir = line.slice(3).split("/")[0];
      const key = repo.path + "/" + dir;
      map[key] = (map[key] || 0) + 1;
    }
  }
  return map;
}

export function cliCmd(cmd) {
  try {
    return execSync(cmd, {
      encoding: "utf8", timeout: 30_000,
      env: { ...process.env, FORCE_COLOR: "1" },
    }).replace(/\n+$/, "");
  } catch { return "(error)"; }
}

export function gitCmd(repoPath, cmd) {
  try {
    return execSync(cmd, { cwd: repoPath, encoding: "utf8", timeout: 5000 }).trim();
  } catch { return ""; }
}

export function ago(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  return `${h}h ago`;
}

// Exported for compositor testing
export { formatSection, helpSection, guideSection, SECTION_NAMES, JSON_COMMANDS };

export const help = `CLI screenshot of Eclipse — composite view of IDE state.

Usage:  jdt status [sections...] [-q] [--json]

Sections (default: all):
  intro           context for AI agents (shown by default, suppressed by -q)
  git             git repos, branches, modified files
  editors         open editor tabs (active first)
  problems        IMarker.PROBLEM (errors, warnings)
  launch-configs  saved launch configurations (name, type, project, target)
  launches        running launches
  tests           recent test sessions
  projects        workspace projects with repo mapping
  help            full jdt command reference (shown by default, suppressed by -q)
  guide           hints and patterns (shown by default, suppressed by -q)

Options:
  -q, --quiet  suppress meta-sections (intro, help, guide) and descriptions
  --json       composite JSON of all data sections

Examples:
  jdt status                    full dashboard
  jdt status -q                 full dashboard, no intro/help/guide
  jdt status editors problems   editors + problems
  jdt status help               command reference
  jdt status --json             all sections as JSON`;
