import { basename } from "node:path";
import { get } from "../client.mjs";
import { extractPositional, parseFqmn } from "../args.mjs";
import { toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";
import { formatTable } from "../format/table.mjs";

export async function editors(args = []) {
  const data = await get("/editors");

  output(args, data, {
    empty: "(no open editors)",
    text(data) {
      const rows = data.map((r) => [
        r.active ? ">" : "",
        r.fqn ? `\`${r.fqn}\`` : basename(r.file),
        r.project || "",
        toSandboxPath(r.file),
      ]);
      console.log(formatTable([" ", "FILE", "PROJECT", "PATH"], rows));
    },
  });
}

export async function open(args) {
  const pos = extractPositional(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  const method = parsed.method || pos[1];
  if (!fqn) {
    console.error("Usage: open <FQN>[#method[(param types)]]");
    process.exit(1);
  }
  let url = `/open?class=${encodeURIComponent(fqn)}`;
  if (method) url += `&method=${encodeURIComponent(method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    return;
  }
  console.log("Opened");
}

export const editorsHelp = `List all open editors in Eclipse. Active editor first.

Usage:  jdt editors [--json]

Options:
  --json    output as JSON

Examples:
  jdt editors
  jdt editors --json`;

export const openHelp = `Open a type or method in the Eclipse editor.

Usage:  jdt open <FQN>[#method[(param types)]]

Examples:
  jdt open com.example.dao.UserDaoImpl
  jdt open com.example.dao.UserDaoImpl#getStaff
  jdt open "com.example.dao.UserDaoImpl#save(Order)"`;
