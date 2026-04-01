import { basename } from "node:path";
import { get } from "../client.mjs";
import { toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";
import { formatTable } from "../format/table.mjs";

export async function projects(args = []) {
  const data = await get("/projects");

  output(args, data, {
    empty: "(no projects)",
    text(data) {
      const repoSet = new Set();
      const rows = data.map((p) => {
        const name = p.name || p;
        const loc = toSandboxPath(p.location || "");
        const repo = (p.repo || "").replace(/\\/g, "/");
        const repoName = repo ? basename(repo) : "";
        if (repoName) repoSet.add(repoName);
        return [`\`${name}\``, loc, repoName];
      });

      console.log(formatTable(["PROJECT", "LOCATION", "REPO"], rows));
      console.log(`\n${data.length} projects, ${repoSet.size} repos`);
    },
  });
}

export const help = `List workspace projects with repo mapping.

Usage:  jdt projects [--json]

Options:
  --json    output as JSON

Examples:
  jdt projects
  jdt projects --json`;
