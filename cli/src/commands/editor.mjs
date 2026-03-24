import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import { stripProject } from "../paths.mjs";

export async function activeEditor() {
  const result = await get("/active-editor");
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.file === null) {
    console.log("(no file open)");
  } else {
    console.log(`${stripProject(result.file)}:${result.line}`);
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

export const activeEditorHelp = `Show the file and cursor line of the active Eclipse editor.

Usage:  jdt active-editor`;

export const openHelp = `Open a type or method in the Eclipse editor.

Usage:  jdt open <FQN>[#method[(param types)]]

Examples:
  jdt open app.m8.dao.StaffDaoImpl
  jdt open app.m8.dao.StaffDaoImpl#getStaff
  jdt open "app.m8.dao.StaffDaoImpl#save(Order)"`;
