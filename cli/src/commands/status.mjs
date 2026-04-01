/**
 * Composite dashboard — shows Eclipse IDE state in one call.
 * Each section maps to a standalone jdt command for incremental refresh.
 *
 * Architecture:
 *   Renderer  — returns { title, cmd, body } (pure data, no formatting)
 *   Compositor — assembles sections into markdown with code fences
 *   Helpers   — reposFromServer, gitCmd, ago (stateless utilities)
 */

import { execSync } from "node:child_process";
import { basename } from "node:path";

// ---- Public API ----

const SECTION_NAMES = ["intro", "git", "editors", "errors", "launches", "tests", "projects", "guide"];

export async function status(args) {
  const jsonFlag = args.includes("--json");
  const quiet = args.includes("-q") || args.includes("--quiet");
  const requested = args.filter((a) => !a.startsWith("-"));
  const sections = requested.length > 0
    ? requested.filter((s) => SECTION_NAMES.includes(s))
    : SECTION_NAMES.filter((s) => s !== "guide");

  if (jsonFlag) {
    const dataSections = sections.filter((s) => s !== "intro" && s !== "guide");
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

  const results = [];

  const showExtras = !quiet && requested.length === 0;
  // Intro first — context for agents seeing this for the first time
  if (showExtras || sections.includes("intro")) results.push(introSection());

  for (const name of sections) {
    if (name === "intro" || name === "guide") continue;
    const renderer = RENDERERS[name];
    if (renderer) results.push(await renderer());
  }

  // Guide last
  if (showExtras) results.push(guideSection());
  if (sections.includes("guide") && !showExtras) results.push(guideSection());

  const single = results.length === 1;
  console.log(results.map((s) => formatSection(s, single)).join("\n\n"));
}

// ---- Compositor ----

/**
 * Format a section object into markdown.
 * Single section: no ## header, just ```bash block.
 * Multiple sections: ## Title + ```bash block.
 */
function formatSection({ title, cmd, body }, single) {
  if (single) return body;
  return `## ${title}\n\n\`\`\`bash\n$ ${cmd}\n${body}\n\`\`\``;
}

/** JSON commands for --json composite output. */
const JSON_COMMANDS = {
  git: "jdt git --json",
  editors: "jdt editors --json",
  errors: "jdt errors --json",
  launches: "jdt launch list --json",
  tests: "jdt test sessions --json",
  projects: "jdt projects --json",
};

// ---- Renderers (return { title, cmd, body }) ----

const RENDERERS = {
  git: renderGit,
  editors: renderEditors,
  errors: renderErrors,
  launches: renderLaunches,
  tests: renderTests,
  projects: renderProjects,
};

async function renderGit() {
  return { title: "Git", cmd: "jdt git list --no-files", body: cliCmd("jdt git list --no-files") };
}

async function renderEditors() {
  return { title: "Editors", cmd: "jdt editors", body: cliCmd("jdt editors") };
}

async function renderErrors() {
  return { title: "Errors", cmd: "jdt errors --json", body: cliCmd("jdt errors --json") };
}

async function renderLaunches() {
  return { title: "Launches", cmd: "jdt launch list", body: cliCmd("jdt launch list") };
}

async function renderTests() {
  return { title: "Tests", cmd: "jdt test sessions", body: cliCmd("jdt test sessions") };
}

async function renderProjects() {
  return { title: "Projects", cmd: "jdt projects", body: cliCmd("jdt projects") };
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
  ## Intro      -> jdt status intro
  ## Git        -> jdt status git
  ## Editors    -> jdt status editors
  ...
  ## Guide      -> jdt status guide
That command can be run standalone for a fresh snapshot: jdt status errors
Several commands can be combined in one call: jdt status git editors projects
Beyond this dashboard, jdt has 30+ more commands.
Run jdt help for the full reference.

-q suppresses this intro and the guide at the end.`,
  };
}

function guideSection() {
  return {
    title: "Guide",
    cmd: "jdt status guide",
    body: `This dashboard is a composite of standalone commands.
Refresh the full dashboard or individual sections.
-q suppresses this guide. Selecting sections suppresses it too.

  jdt status                  all sections + intro + guide
  jdt status -q               all sections, no intro/guide
  jdt status intro            only intro
  jdt status git              only git repos and branches
  jdt status editors          only open editor tabs
  jdt status errors           only compilation errors
  jdt status launches         only running launches
  jdt status tests            only test results
  jdt status projects         only project list
  jdt status guide            only this guide

Each section can also be refreshed with its standalone command:

  jdt git                     same as jdt status git
  jdt editors                 same as jdt status editors
  jdt errors                  compilation errors (with --project for one project)
  jdt launch list             same as jdt status launches
  jdt test sessions           same as jdt status tests
  jdt projects                same as jdt status projects

Combine sections for focused refresh:

  jdt status editors errors   what's open + what's broken
  jdt status git tests        repo state + test results
  jdt status git projects     repos + project list

Specialized refresh after editing code:

  jdt errors                  check compilation after edit
  jdt errors --project X      check one project only (faster)
  jdt test run FQN -f -q      run one test, stream result
  jdt build --project X       trigger build if auto-build is off

For full command reference:

  jdt help                    all available commands
  jdt help <command>          detailed usage for a command`,
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
export { formatSection, guideSection, SECTION_NAMES, JSON_COMMANDS };

export const help = `CLI screenshot of Eclipse — composite view of IDE state.

Usage:  jdt status [sections...] [-q] [--json]

Sections (default: all):
  intro        context for AI agents (shown by default, suppressed by -q)
  git          git repos, branches, modified files
  editors      open editor tabs (active first)
  errors       compilation errors
  launches     running launches
  tests        recent test sessions
  projects     workspace projects with repo mapping
  guide        usage guide (shown by default, suppressed by -q)

Options:
  --json       composite JSON of all data sections

Examples:
  jdt status                    full dashboard
  jdt status -q                 full dashboard, no intro/guide
  jdt status intro              only intro
  jdt status editors errors     only editors + errors
  jdt status git                only git state
  jdt status guide              only usage guide
  jdt status --json             all sections as JSON
  jdt status errors --json      single section as JSON`;
