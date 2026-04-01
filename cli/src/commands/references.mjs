import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import { output } from "../output.mjs";
import { formatReferences } from "../format/references.mjs";

export async function references(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  if (!fqn) {
    console.error("Usage: references <FQN>[#method[(param types)]] [--field name] [--json]");
    process.exit(1);
  }
  let url = `/references?class=${encodeURIComponent(fqn)}`;
  if (flags.field) {
    url += `&field=${encodeURIComponent(flags.field)}`;
  } else {
    const method = parsed.method || pos[1];
    if (method) url += `&method=${encodeURIComponent(method)}`;
    if (parsed.paramTypes) {
      url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
    }
  }

  const data = await get(url, 30_000);

  output(args, data, {
    empty: "(no references)",
    text: formatReferences,
  });
}

export const help = `Find all references to a type, method, or field across the workspace.

Usage:  jdt references <FQN>[#method[(param types)]] [--field name] [--json]

FQMN formats (Fully Qualified Method Name):
  pkg.Class#method              any overload
  pkg.Class#method()            zero-arg overload
  pkg.Class#method(String)      specific signature
  pkg.Class.method(String)      Eclipse Copy Qualified Name style

Flags:
  --field <name>   find references to a field
  --json           output as JSON

Examples:
  jdt references com.example.dto.BaseEntity
  jdt references com.example.dao.UserDaoImpl#getStaff
  jdt references "com.example.dao.UserDaoImpl#save(Order)"
  jdt references com.example.dao.UserDaoImpl --field staffCache
  jdt references com.example.Foo --json`;
