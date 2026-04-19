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
import { normalizePath } from "../paths.mjs";

// ---- Public API ----

const SECTION_NAMES = ["intro", "git", "editors", "problems", "launch-configs", "launches", "tests", "projects", "help", "guide"];

export async function status(args) {
  const quiet = args.includes("-q") || args.includes("--quiet");
  const requested = args.filter((a) => !a.startsWith("-"));
  const sections = requested.length > 0
    ? requested.filter((s) => SECTION_NAMES.includes(s))
    : SECTION_NAMES.filter((s) => s !== "guide" && s !== "help");

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
  const cmd = `jdt q "@problems * {:severity /severity :file /location/file :line /location/startLine :message /message} | table"`;
  return {
    title: "Problems", cmd,
    body: cliCmd(cmd),
    description:
      "Eclipse Problems view — IMarker.PROBLEM markers (errors, warnings).\n"
      + "Updated on every build. (empty) = clean workspace.\n"
      + "The `* {…}` reshape flattens the :location sub-Map into file/line columns.",
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
  const cmd = `jdt q "@projects * inter(#{:fqn :rootPath :repo :branch}) | table"`;
  return {
    title: "Projects", cmd,
    body: cliCmd(cmd),
    description:
      "Eclipse Package Explorer / Project Explorer.\n"
      + "rootPath = filesystem path. repo = git root if EGit-managed.",
  };
}

function introSection() {
  return {
    title: "Intro",
    cmd: "jdt status intro",
    body: `Eclipse IDE is running and connected to this terminal via jdt CLI.
jdt exposes the IDE's semantic Java graph as a pipeline query surface.
grep sees text. jdt q sees structure — types, methods, call sites,
hierarchies, annotations, classpaths — and composes them.

Query the graph with jdt q '<qlang-pipeline>'. Pipelines start
with a SEED (literal string, nullary operand) and chain operands
that take their subject from pipeValue. Operands never receive
identifiers as captured arguments — that's RPC. Filtering and
composition is done with core qlang (filter, *, >>, |, !|, as,
let). Examples:

  jdt q '@projects * @members * @methods
        | filter(/modifiers | any(eq("public")))
        | filter(@callers | empty) * /fqn'
  -- public methods with no callers (deletion candidates)

  jdt q '"com.example.Repository" | @subtypes * /fqn'
  -- all subtypes of an interface

  jdt q '"com.example.Foo#bar()" | @callers * /fqn'
  -- who calls this method

  jdt q '"com.example.MyService" | @ancestors * /fqn'
  -- full supertype chain

  jdt q '"com.example.Foo" | @detail'
  -- detail-node for a fqn/fqmn (routes via kind)

  jdt q 'manifest | filter(/name | startsWith("@")) * /name'
  -- every @-operand (axes, conduits, render, IO) — the full vocab

Discover any operand:
  jdt q 'reify(:@subtypes)'               -- docs + examples + throws
  jdt q 'reify(:@subtypes) | runExamples' -- run the doc snippets
  jdt help q                              -- grammar + host ops + debug

Full qlang grammar: https://github.com/kaluchi/qlang/blob/master/docs/qlang-spec.md

The sections below are live output from the running Eclipse instance.
Each section is produced by a command shown in its header.
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
    body: `Graph query — jdt q:

  jdt q '<qlang-pipeline>'   evaluate against the JDT graph
  jdt q '@operand'           bare-name reify = descriptor
  jdt q 'manifest'           full operand catalog

  Pipelines start with a SEED (string literal, @projects, etc.).
  Operands never take FQN/FQMN as captured args — that's RPC.

  as(:name) snapshots any value — reuse without re-fetching:
    jdt q '"*" | @types | as(:all) | all | count'

  !| catches errors on the fail-track:
    jdt q '"no.such" | @type !| /thrown'

After editing code:

  jdt q '@problems'           check compilation after edit
  jdt test run FQN -f -q      run one test, stream result
  jdt build --project X       trigger build if auto-build is off

Dashboard:

  jdt status -q               all sections, no intro/help/guide
  jdt status editors problems   selective refresh`,
  };
}

// ---- Helpers (stateless, exported for testing) ----

export function reposFromServer(projects) {
  const seen = new Map();
  for (const p of projects) {
    const repo = normalizePath(p.repo || "");
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
export { formatSection, helpSection, guideSection, SECTION_NAMES };

export const help = `CLI screenshot of Eclipse — composite view of IDE state.

Usage:  jdt status [sections...] [-q]

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

Examples:
  jdt status                    full dashboard
  jdt status -q                 full dashboard, no intro/help/guide
  jdt status editors problems   editors + problems
  jdt status help               command reference

Machine-readable access to any single section goes through the
underlying command directly: jdt git --json, jdt editors --json,
jdt launch configs --json, jdt test runs --json, jdt q '@problems',
jdt q '@projects'.`;
