import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const readme = readFileSync(resolve(__dirname, "../README.md"), "utf8");

/**
 * Extract comment-bearing lines from code blocks in the README.
 * Returns [{lineNum, col, text}] where col is the 1-based position of "# ".
 */
function extractCommentLines(content, sectionColumn) {
  const lines = content.split("\n");
  let inBlock = false;
  let blockStart = 0;
  const results = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("```bash")) {
      inBlock = true;
      blockStart = i + 1;
      continue;
    }
    if (line.startsWith("```") && inBlock) {
      inBlock = false;
      continue;
    }
    if (!inBlock) continue;

    // Find "# " that is a comment (not inside a pattern like #method)
    // Comments are preceded by at least two spaces
    const match = line.match(/  # /);
    if (!match) continue;

    const col = match.index + 3; // 1-based position of "# "
    results.push({ lineNum: i + 1, col, text: line.trimEnd() });
  }
  return results;
}

describe("CLI README alignment", () => {
  it("all command comments in ## Plugin setup block align to the same column", () => {
    const section = readme.split("## Plugin setup")[1].split("## Commands")[0];
    const lines = extractCommentLines("```bash\n" + section.split("```bash")[1], 33);
    expect(lines.length).toBeGreaterThan(0);
    const col = lines[0].col;
    for (const line of lines) {
      expect(line.col, `Line ${line.lineNum}: "${line.text}" comment at col ${line.col}, expected ${col}`).toBe(col);
    }
  });

  it("all command comments in ## Commands blocks align to the same column", () => {
    const section = readme.split("## Commands")[1].split("## Instance discovery")[0];
    const lines = extractCommentLines(section);
    expect(lines.length).toBeGreaterThan(0);
    const col = lines[0].col;
    for (const line of lines) {
      expect(line.col, `Line ${line.lineNum}: "${line.text}" comment at col ${line.col}, expected ${col}`).toBe(col);
    }
  });
});
