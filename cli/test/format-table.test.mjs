import { describe, it, expect } from "vitest";
import { formatTable } from "../src/format/table.mjs";

describe("formatTable", () => {
  it("aligns columns with headers", () => {
    const out = formatTable(["NAME", "TYPE"], [
      ["foo", "Java Application"],
      ["barbaz", "JUnit"],
    ]);
    const lines = out.split("\n");
    expect(lines).toHaveLength(3);
    expect(lines[0]).toContain("NAME");
    expect(lines[0]).toContain("TYPE");
    // columns should be aligned — TYPE starts at the same position
    const typeCol = lines[0].indexOf("TYPE");
    expect(lines[1].indexOf("Java Application")).toBe(typeCol);
    expect(lines[2].indexOf("JUnit")).toBe(typeCol);
  });

  it("handles empty rows", () => {
    const out = formatTable(["A", "B"], []);
    expect(out).toBe("A  B");
  });

  it("handles empty cell values", () => {
    const out = formatTable(["A", "B"], [
      ["x", ""],
      ["", "y"],
    ]);
    const lines = out.split("\n");
    expect(lines).toHaveLength(3);
  });

  it("handles single multiline cell", () => {
    const out = formatTable(["KEY", "VALUE"], [
      ["args", "line1\nline2\nline3"],
    ]);
    const lines = out.split("\n");
    expect(lines).toHaveLength(4); // header + 3 value lines
    expect(lines[1]).toContain("args");
    expect(lines[1]).toContain("line1");
    expect(lines[2]).toContain("line2");
    expect(lines[3]).toContain("line3");
    // continuation lines should have empty key column, aligned to VALUE
    const valCol = lines[0].indexOf("VALUE");
    expect(lines[2].indexOf("line2")).toBe(valCol);
    expect(lines[3].indexOf("line3")).toBe(valCol);
  });

  it("multiline uses max height across cells in same row", () => {
    const out = formatTable(["A", "B", "C"], [
      ["a1\na2\na3", "b1\nb2", "c1"],
    ]);
    const lines = out.split("\n");
    // header + 3 lines (max height from column A)
    expect(lines).toHaveLength(4);
    expect(lines[1]).toContain("a1");
    expect(lines[1]).toContain("b1");
    expect(lines[1]).toContain("c1");
    expect(lines[2]).toContain("a2");
    expect(lines[2]).toContain("b2");
    expect(lines[3]).toContain("a3");
    // c1 only has 1 line — rows 2,3 should have empty space for C
    expect(lines[3]).not.toContain("c1");
  });

  it("mixed single and multiline rows", () => {
    const out = formatTable(["KEY", "VALUE"], [
      ["name", "foo"],
      ["args", "-ea\n-Xmx512m"],
      ["type", "JUnit"],
    ]);
    const lines = out.split("\n");
    // header + 1 + 2 + 1 = 5
    expect(lines).toHaveLength(5);
    expect(lines[1]).toContain("name");
    expect(lines[1]).toContain("foo");
    expect(lines[2]).toContain("args");
    expect(lines[2]).toContain("-ea");
    expect(lines[3]).toContain("-Xmx512m");
    expect(lines[4]).toContain("type");
    expect(lines[4]).toContain("JUnit");
  });

  it("multiline width calculated from longest line", () => {
    const out = formatTable(["K", "V"], [
      ["short", "abc\nabcdefghij"],
    ]);
    const lines = out.split("\n");
    // "abcdefghij" is the widest — column should accommodate it
    // first line should not be wider than needed
    expect(lines[1]).toContain("abc");
    expect(lines[2]).toContain("abcdefghij");
  });

  it("multiple columns with multiline values", () => {
    const out = formatTable(["A", "B"], [
      ["x1\nx2", "y1\ny2\ny3"],
    ]);
    const lines = out.split("\n");
    // header + 3 lines (max of 2, 3)
    expect(lines).toHaveLength(4);
    expect(lines[1]).toContain("x1");
    expect(lines[1]).toContain("y1");
    expect(lines[2]).toContain("x2");
    expect(lines[2]).toContain("y2");
    expect(lines[3]).toContain("y3");
    // x column exhausted — should be empty padding
    const aCol = lines[0].indexOf("A");
    const bCol = lines[0].indexOf("B");
    expect(lines[3].substring(aCol, bCol).trim()).toBe("");
  });

  it("trims trailing whitespace on each line", () => {
    const out = formatTable(["A", "B"], [
      ["long-value", ""],
    ]);
    const lines = out.split("\n");
    // last cell is empty — trailing spaces should be trimmed
    expect(lines[1]).toBe("long-value");
  });
});
