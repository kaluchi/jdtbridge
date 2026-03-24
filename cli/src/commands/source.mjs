import { getRaw } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import { stripProject } from "../paths.mjs";

export async function source(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  if (!fqn) {
    console.error("Usage: source <FQN>[#method[(param types)]]");
    process.exit(1);
  }
  const method = parsed.method || pos[1];
  let url = `/source?class=${encodeURIComponent(fqn)}`;
  if (method) url += `&method=${encodeURIComponent(method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }
  const result = await getRaw(url, 30_000);
  const file = result.headers["x-file"] || "?";
  const startLine = result.headers["x-start-line"] || "?";
  const endLine = result.headers["x-end-line"] || "?";

  if (startLine === "-1") {
    // Multiple overloads: body has :startLine-endLine prefixes per block
    console.log(
      result.body.replace(
        /^:(\d+-\d+)/gm,
        `${stripProject(file)}:$1`,
      ),
    );
  } else {
    console.log(`${stripProject(file)}:${startLine}-${endLine}`);
    console.log(result.body);
  }
}

export const help = `Print source code of a type or method.

Usage:  jdt source <FQN>[#method[(param types)]]

Examples:
  jdt source app.m8.dao.StaffDaoImpl
  jdt source app.m8.dao.StaffDaoImpl#getStaff
  jdt source "app.m8.dao.StaffDaoImpl#save(Order)"`;
