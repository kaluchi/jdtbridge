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
import { resolveInstance } from "../resolve.mjs";

// ---- Public API ----

const SECTION_NAMES = ["intro", "git", "editors", "problems", "launch-configs", "launches", "tests", "projects", "guide"];

// Env vars passed to every subprocess cliCmd() spawns. Populated
// once at the start of status() from resolveInstance() so that all
// section subprocesses hit step 1 of resolution (env) and skip
// discovery + cwd-match. Without this every subprocess re-resolves,
// hammering /projects N times per section in multi-instance cases.
let _resolvedEnv = {};

export async function status(args) {
  const quiet = args.includes("-q") || args.includes("--quiet");
  const requested = args.filter((a) => !a.startsWith("-"));
  const sections = requested.length > 0
    ? requested.filter((s) => SECTION_NAMES.includes(s))
    : SECTION_NAMES.filter((s) => s !== "guide");

  const META_SECTIONS = new Set(["intro", "guide"]);
  const results = [];

  // Resolve target instance once; propagate to subprocesses via env.
  const inst = await resolveInstance();
  _resolvedEnv = inst ? {
    JDT_BRIDGE_PORT: String(inst.port),
    JDT_BRIDGE_TOKEN: inst.token || "",
    JDT_BRIDGE_HOST: inst.host || "127.0.0.1",
  } : {};

  const showExtras = !quiet && requested.length === 0;
  // Intro first — context for agents seeing this for the first time
  if (showExtras || sections.includes("intro")) results.push(introSection());

  for (const name of sections) {
    if (META_SECTIONS.has(name)) continue;
    const renderer = RENDERERS[name];
    if (renderer) results.push(await renderer());
  }

  // Guide = qlang reference + CLI catalog, collapsed under one
  // section. Same in default and explicit (`jdt status guide`).
  if (showExtras || sections.includes("guide")) {
    results.push(guideSection());
  }

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
function formatSection({ title, cmd, body, description, raw }, { bare, quiet }) {
  if (bare) return body;
  const desc = (!quiet && description) ? description + "\n\n" : "";
  if (raw) return `## ${title}\n\n${desc}${body}`;
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
      + "jdt open <FQN> opens a type in the Java Editor (F3 equivalent).",
  };
}

async function renderProblems() {
  const cmd = `jdt q "@problems | filter(/severity | eq(\\"error\\")) | take(20) * {:message /message :file /location/file :severity /severity :line /location/startLine}"`;
  return {
    title: "Problems", cmd,
    body: cliCmd(cmd),
    description:
      "Eclipse Problems view — errors only, first 20. Updated on every build.\n"
      + "[] = no compilation errors. For warnings or the full list, drop the\n"
      + "filter and take: jdt q '@problems'.",
  };
}

async function renderLaunchConfigs() {
  return {
    title: "Launch Configs", cmd: "jdt launch configs --limit 20",
    body: cliCmd("jdt launch configs --limit 20"),
    description:
      "Eclipse Run Configurations dialog (Run > Run Configurations...).\n"
      + "CONFIGTYPE = ILaunchConfigurationType. CONFIGID = launch config name.\n"
      + "Order: favorites (run/debug/coverage), then launch history\n"
      + "(most-recent first), then remaining configs. Drop --limit for all.",
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
    body: `Eclipse IDE for Java Developers is running and wired to this terminal via the jdt CLI.
jdt exposes the IDE's functions as terminal commands — Java search,
compilation, testing, refactoring, editor control. Everything the
developer sees and does in Eclipse GUI is reachable from here
against the same running instance.

Sections below are live output from that instance. Each section's
header shows the command that produced it, and that command can be
run standalone for a fresh snapshot of just that section. Several
can be combined: jdt status git editors problems.

-q suppresses intro, guide, and per-section descriptions.`,
  };
}

function helpSection() {
  return {
    title: "Help",
    cmd: "jdt help",
    body: cliCmd("jdt help"),
  };
}

function querySection() {
  return {
    title: "Query",
    cmd: "jdt help q",
    body: cliCmd("jdt help q"),
  };
}

function guideSection() {
  const q = querySection();
  const h = helpSection();
  const fence = (cmd, body) =>
    `\`\`\`bash\n$ ${cmd}\n${body}\n\`\`\``;
  const preamble = `The dashboard above is one Eclipse snapshot. \`jdt q\` evaluates
a qlang pipeline against the same workspace, so every section's
data — projects, problems, references, source — is reachable
through one operand catalog.

A pipeline starts with a subject and threads it through
\`|\`-separated steps. The subject is a fully-qualified-name
String, or a nullary axis that produces one (\`@projects\`,
\`@problems\`):

  jdt q '"java.lang.String" | @methods * /name | distinct'
  jdt q '@problems | filter(/severity | eq("error")) | count'

\`|\` pipes the value through; \`*\` fans out per Vec element;
\`/key\` projects a Map field.

Axes most worth knowing first:

  @type        fqn  → :type detail node-Map
  @members     type → Vec of method/field/inner-type skeletons
  @callers     method → distinct skeletons of every call site
  @source      type/method/field → raw Java source (a String)
  @problems    project / file / workspace → Vec of compile markers
  @sourceCard  fqn  → markdown card with source + outgoing/incoming refs

Errors travel as data on the fail-track. Pipe through \`!|\` to
inspect:

  jdt q '"no.such.Type" | @type !| type'                       # → ::TypeNotFound
  jdt q '"String#valueOf" | @method !| /context/candidates'    # AmbiguousMatch's overload list

Every binding is self-describing through axes on its keyword.
Primitives (\`:@type\`) carry a structured descriptor; conduits
(\`:@callers\`) carry the qlang source of their body:

  jdt q ':@type | spec'         # primitive descriptor — subject / returns / throws
  jdt q ':@callers | source'    # conduit body
  jdt q ':@type | docs'         # catalog prose
  jdt q ':@type | examples'     # runnable ~{...} snippets
  jdt q 'manifest * /name'      # every operand in this session

The full reference — grammar, axis catalog, combinators
(\`* >> !|\`), cookbook — is inlined below as \`jdt help q\`.
The full CLI command catalog follows as \`jdt help\`.`;
  return {
    title: "Guide",
    cmd: "jdt status guide",
    raw: true,
    body: [
      preamble,
      fence(q.cmd, q.body),
      fence(h.cmd, h.body),
    ].join("\n\n"),
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
      env: { ...process.env, ..._resolvedEnv, FORCE_COLOR: "1" },
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

Sections (default: all data + intro + guide):
  intro           what jdt is, how to read the dashboard
  git             git repos, branches, modified files
  editors         open editor tabs (active first)
  problems        IMarker.PROBLEM (errors only, first 20)
  launch-configs  saved launch configurations (name, type, project, target)
  launches        running launches
  tests           recent test sessions
  projects        workspace projects with repo mapping
  guide           post-edit workflow + jdt help q + jdt help

Options:
  -q, --quiet  suppress meta-sections (intro, guide) and descriptions

Examples:
  jdt status                    full dashboard
  jdt status -q                 full dashboard, no intro/guide
  jdt status editors problems   editors + problems
  jdt status guide              qlang reference + CLI catalog

Machine-readable access to any single section goes through the
underlying command directly: jdt git --json, jdt editors --json,
jdt launch configs --json, jdt test runs --json, jdt q '@problems',
jdt q '@projects'.`;
