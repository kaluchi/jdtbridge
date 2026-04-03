// References output formatter.
// Markdown snippets — same structure as jdt source:
//   #### `FQMN`
//   `FILE:LINE`
//
//   ```java
//   code
//   ```

import { stripProject, toSandboxPath } from "../paths.mjs";

export function formatReferences(results) {
  const blocks = [];
  for (const r of results) {
    const f = toSandboxPath(stripProject(r.file));
    const isBinary = r.line <= 0;
    const lines = [];

    // Line 1: header (FQMN)
    lines.push(r.in ? `#### \`${r.in}\`` : `#### ${f}`);

    // Line 2: location
    if (isBinary) {
      const jar = f.split(/[/\\]/).pop();
      lines.push(`\`${r.project || "?"} (${jar})\``);
    } else {
      lines.push(`\`${f}:${r.line}\``);
    }

    // Code block
    if (r.content) {
      lines.push("");
      lines.push("```java");
      lines.push(r.content);
      lines.push("```");
    }

    blocks.push(lines.join("\n"));
  }
  console.log(blocks.join("\n\n---\n\n"));
}
