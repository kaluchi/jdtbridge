import { basename } from "node:path";
import { get } from "../client.mjs";
import { extractPositional, parseFqn } from "../args.mjs";
import {
  translateHostPath,
  translateHostPathFromLocal,
} from "../path-translate.mjs";
import { isAbsolutePath } from "../paths.mjs";
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
        translateHostPath(r.file),
      ]);
      console.log(formatTable([" ", "FILE", "PROJECT", "PATH"], rows));
    },
  });
}

export async function open(args) {
  const pos = extractPositional(args);
  const arg = pos[0];
  if (!arg) {
    console.error(
      "Usage: open <FQN>[#method[(param types)]] | open <path>");
    process.exit(1);
  }

  if (isAbsolutePath(arg)) {
    const hostPath = translateHostPathFromLocal(arg);
    const result = await get(
      `/openFile?path=${encodeURIComponent(hostPath)}`);
    if (result.error) {
      console.error(result.error);
      return;
    }
    console.log("Opened");
    return;
  }

  const parsed = parseFqn(arg);
  const fqn = parsed.className;
  const method = parsed.method || pos[1];
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

export const openHelp = `Open a Java element or file in the Eclipse editor.

Usage:  jdt open <FQN>[#method[(param types)]]
        jdt open <absolute-path>

Examples:
  jdt open com.example.dao.UserDaoImpl
  jdt open com.example.dao.UserDaoImpl#getStaff
  jdt open D:/git/repo/pom.xml`;
