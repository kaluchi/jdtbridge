import { describe, it, expect, beforeEach, vi } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";

async function loadModuleWithHome(home) {
  process.env.JDTBRIDGE_HOME = home;
  vi.resetModules();
  return await import(
      "../src/path-translate.mjs?home=" + Date.now());
}

function writeCache(home, bridgeSocket, projects) {
  const dir = join(home, "remote-instances", "project-paths");
  mkdirSync(dir, { recursive: true });
  const hash = createHash("sha256").update(bridgeSocket)
      .digest("hex").slice(0, 12);
  const file = join(dir, hash + ".json");
  writeFileSync(file,
      JSON.stringify({
        "bridge-socket": bridgeSocket,
        scannedAt: Date.now(),
        "mount-points": [],
        projects,
      }, null, 2));
}

describe("loadTranslationTable", () => {
  let home;
  beforeEach(() => {
    home = mkdtempSync(join(tmpdir(), "jdt-remap-"));
  });

  it("returns empty array when no cache exists", async () => {
    const { loadTranslationTable } = await loadModuleWithHome(home);
    expect(loadTranslationTable("nowhere:1234")).toEqual([]);
  });

  it("loads rows from cache and sorts by eclipseRoot length desc",
        async () => {
    writeCache(home, "host:7777", {
      outer: {
        eclipseRoot: "D:/host",
        localRoot:   "/mnt/host",
      },
      inner: {
        eclipseRoot: "D:/host/nested/inner",
        localRoot:   "/mnt/host/nested/inner",
      },
      standalone: {
        eclipseRoot: "C:/other",
        localRoot:   "/mnt/other",
      },
    });
    const { loadTranslationTable } = await loadModuleWithHome(home);
    const rows = loadTranslationTable("host:7777");
    // Only the relative ordering between overlapping prefixes
    // matters — the nested project must win over its outer.
    const roots = rows.map(r => r.eclipseRoot);
    expect(roots.indexOf("D:/host/nested/inner"))
        .toBeLessThan(roots.indexOf("D:/host"));
    expect(roots).toContain("C:/other");
    expect(roots.length).toBe(3);
  });

  it("skips rows missing eclipseRoot or localRoot", async () => {
    writeCache(home, "host:7777", {
      good:    { eclipseRoot: "D:/a", localRoot: "/a" },
      halfEcl: { eclipseRoot: "D:/b" },
      halfLoc: { localRoot:   "/c" },
      bare:    "legacy-string-format",
    });
    const { loadTranslationTable } = await loadModuleWithHome(home);
    const rows = loadTranslationTable("host:7777");
    expect(rows.length).toBe(1);
    expect(rows[0].eclipseRoot).toBe("D:/a");
  });
});

describe("translatePath", () => {
  const winToLinux = [
    { eclipseRoot: "D:\\host\\work",
      localRoot:   "/mnt/work" },
    { eclipseRoot: "C:\\Users\\me\\projects",
      localRoot:   "/home/me/projects" },
  ];
  const linuxToLinux = [
    { eclipseRoot: "/home/eclipse/work",
      localRoot:   "/workspace" },
  ];
  const linuxToWin = [
    { eclipseRoot: "/host/src",
      localRoot:   "D:\\sandbox" },
  ];

  async function translate() {
    const { translatePath } = await loadModuleWithHome(
        mkdtempSync(join(tmpdir(), "jdt-ptrans-")));
    return translatePath;
  }

  it("returns input unchanged when table is empty", async () => {
    const translatePath = await translate();
    expect(translatePath("D:\\foo\\bar", [])).toBe("D:\\foo\\bar");
  });

  it("passes non-string values through", async () => {
    const translatePath = await translate();
    expect(translatePath(null, winToLinux)).toBe(null);
    expect(translatePath(42, winToLinux)).toBe(42);
    expect(translatePath(undefined, winToLinux)).toBe(undefined);
  });

  it("rewrites a Windows eclipseRoot prefix to Linux localRoot",
        async () => {
    const translatePath = await translate();
    expect(translatePath(
        "D:\\host\\work\\proj\\src\\Foo.java",
        winToLinux))
      .toBe("/mnt/work/proj/src/Foo.java");
  });

  it("accepts forward-slash host paths against "
      + "backslash eclipseRoot", async () => {
    const translatePath = await translate();
    expect(translatePath(
        "D:/host/work/proj/src/Foo.java", winToLinux))
      .toBe("/mnt/work/proj/src/Foo.java");
  });

  it("returns the localRoot verbatim when the input equals "
      + "eclipseRoot exactly", async () => {
    const translatePath = await translate();
    expect(translatePath("D:\\host\\work", winToLinux))
      .toBe("/mnt/work");
  });

  it("picks the LONGEST matching prefix", async () => {
    const nested = [
      { eclipseRoot: "D:\\host\\work\\inner",
        localRoot:   "/mnt/inner" },
      { eclipseRoot: "D:\\host\\work",
        localRoot:   "/mnt/work" },
    ];
    nested.sort((a, b) => b.eclipseRoot.length - a.eclipseRoot.length);
    const translatePath = await translate();
    expect(translatePath(
        "D:\\host\\work\\inner\\pkg\\X.java", nested))
      .toBe("/mnt/inner/pkg/X.java");
    expect(translatePath(
        "D:\\host\\work\\other\\pkg\\X.java", nested))
      .toBe("/mnt/work/other/pkg/X.java");
  });

  it("leaves paths outside every eclipseRoot unchanged", async () => {
    const translatePath = await translate();
    expect(translatePath(
        "D:\\eclipse\\plugins\\foo.jar", winToLinux))
      .toBe("D:\\eclipse\\plugins\\foo.jar");
    expect(translatePath(
        "C:\\Users\\other\\file.txt", winToLinux))
      .toBe("C:\\Users\\other\\file.txt");
  });

  it("Linux host → Linux CLI mapping", async () => {
    const translatePath = await translate();
    expect(translatePath(
        "/home/eclipse/work/proj/src/Foo.java", linuxToLinux))
      .toBe("/workspace/proj/src/Foo.java");
  });

  it("Linux host → Windows CLI: suffix uses backslash",
        async () => {
    const translatePath = await translate();
    expect(translatePath(
        "/host/src/proj/File.java", linuxToWin))
      .toBe("D:\\sandbox\\proj\\File.java");
  });

  it("does not partial-match a sibling project prefix",
        async () => {
    // eclipseRoot `D:/work` must not match `D:/work-other/…`
    // because the char after the prefix is not a separator.
    const translatePath = await translate();
    const t = [
      { eclipseRoot: "D:\\work",
        localRoot:   "/mnt/work" },
    ];
    expect(translatePath("D:\\work-other\\foo", t))
      .toBe("D:\\work-other\\foo");
    expect(translatePath("D:\\work\\foo", t))
      .toBe("/mnt/work/foo");
  });
});
