import { describe, it, expect } from "vitest";
import { alignCmds } from "../src/format/align.mjs";

describe("alignCmds", () => {
  it("aligns descriptions to the widest command + 2 spaces", () => {
    const out = alignCmds([
      ["a", "short"],
      ["abcdefgh", "long-cmd"],
      ["abcd", "mid"],
    ]);
    const lines = out.split("\n");
    expect(lines).toHaveLength(3);
    // widest = 8 ("abcdefgh"); descriptions all start at idx 2+1+8+1+2 = 14
    // (indent 2 + ` + cmd + ` + gap 2)
    const idx = lines.map((l) => l.indexOf(/\S/.test(l[14]) ? l[14] : ""));
    expect(lines[0]).toBe("  `a`         short");
    expect(lines[1]).toBe("  `abcdefgh`  long-cmd");
    expect(lines[2]).toBe("  `abcd`      mid");
  });

  it("single row → just gap of 2", () => {
    expect(alignCmds([["x", "y"]])).toBe("  `x`  y");
  });

  it("custom indent and gap", () => {
    const out = alignCmds(
      [["a", "x"], ["bc", "y"]],
      { indent: "    ", gap: 4 },
    );
    expect(out.split("\n")).toEqual([
      "    `a`     x",
      "    `bc`    y",
    ]);
  });

  it("equal-width commands → uniform 2-space gap", () => {
    expect(alignCmds([["foo", "1"], ["bar", "2"]])).toBe(
      "  `foo`  1\n  `bar`  2",
    );
  });
});
