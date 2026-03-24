import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";

export async function editors() {
  const results = await get("/editors");
  if (results.error) {
    console.error(results.error);
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no open editors)");
    return;
  }
  for (const r of results) {
    console.log(r.file);
  }
}

export async function open(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
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
    process.exit(1);
  }
  console.log("Opened");
}

export const editorsHelp = `List all open editors in Eclipse. Active editor first.

Usage:  jdt editors

Output: absolute file paths, one per line.`;

export const openHelp = `Open a type or method in the Eclipse editor.

Usage:  jdt open <FQN>[#method[(param types)]]

Examples:
  jdt open app.m8.dao.StaffDaoImpl
  jdt open app.m8.dao.StaffDaoImpl#getStaff
  jdt open "app.m8.dao.StaffDaoImpl#save(Order)"`;
