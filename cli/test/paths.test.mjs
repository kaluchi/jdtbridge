import { describe, it, expect } from "vitest";
import {
  toWsPath, hostToSandboxPath, formatLineRange, normalizePath,
  isAbsolutePath,
} from "../src/paths.mjs";
import { remapJsonPaths } from "../src/json-output.mjs";

describe("toWsPath", () => {
  it("adds leading slash", () => {
    expect(toWsPath("my-server/src/main/java/Foo.java")).toBe(
      "/my-server/src/main/java/Foo.java",
    );
  });

  it("keeps existing leading slash", () => {
    expect(toWsPath("/my-server/src/Foo.java")).toBe(
      "/my-server/src/Foo.java",
    );
  });
});

describe("hostToSandboxPath", () => {
  // Agent-sandbox path construction: CLI runs on host and builds
  // paths for `docker sandbox exec …` commands. Windows drive
  // letters become /<drive>/ using Docker Desktop WSL2 convention.
  it("converts D:\\ backslash path", () => {
    expect(hostToSandboxPath("D:\\foo\\bar")).toBe("/d/foo/bar");
  });

  it("converts D:/ forward-slash path", () => {
    expect(hostToSandboxPath("D:/foo/bar")).toBe("/d/foo/bar");
  });

  it("converts C:\\ path", () => {
    expect(hostToSandboxPath("C:\\Users\\foo")).toBe("/c/Users/foo");
  });

  it("normalises backslashes in non-drive path", () => {
    expect(hostToSandboxPath("relative\\path")).toBe("relative/path");
  });

  it("leaves Unix path unchanged", () => {
    expect(hostToSandboxPath("/unix/path")).toBe("/unix/path");
  });
});

describe("formatLineRange", () => {
  it("formats a range", () => {
    expect(formatLineRange(10, 20)).toBe(":10-20");
  });

  it("marks missing source with a hint", () => {
    expect(formatLineRange(-1, -1)).toBe(" (source not attached)");
  });
});

describe("normalizePath", () => {
  it("replaces backslashes with forward slashes", () => {
    expect(normalizePath("D:\\a\\b\\c")).toBe("D:/a/b/c");
  });

  it("leaves forward-slash paths alone", () => {
    expect(normalizePath("/a/b/c")).toBe("/a/b/c");
  });
});

describe("isAbsolutePath", () => {
  it("accepts POSIX absolute path", () => {
    expect(isAbsolutePath("/foo/bar.xml")).toBe(true);
  });

  it("accepts Windows drive path with backslashes", () => {
    expect(isAbsolutePath("D:\\git\\repo\\foo.xml")).toBe(true);
  });

  it("accepts Windows drive path with forward slashes", () => {
    expect(isAbsolutePath("D:/git/repo/foo.xml")).toBe(true);
  });

  it("accepts UNC path", () => {
    expect(isAbsolutePath("\\\\server\\share\\file.txt")).toBe(true);
  });

  it("rejects Java FQN", () => {
    expect(isAbsolutePath("com.example.Foo")).toBe(false);
    expect(isAbsolutePath("com.example.Foo#bar(String)")).toBe(false);
    expect(isAbsolutePath("com.example.Foo$Inner")).toBe(false);
  });

  it("rejects relative path", () => {
    expect(isAbsolutePath("foo/bar.xml")).toBe(false);
    expect(isAbsolutePath("./foo.xml")).toBe(false);
  });

  it("rejects non-string inputs", () => {
    expect(isAbsolutePath(null)).toBe(false);
    expect(isAbsolutePath(undefined)).toBe(false);
    expect(isAbsolutePath(42)).toBe(false);
  });
});

describe("remapJsonPaths (no instance → no-op)", () => {
  // When the current instance is local (or absent — default in
  // unit-test context), translateHostPath returns every input
  // verbatim. Tests that want to observe remapping set up a fake
  // remote instance + cache; see path-translate.test.mjs.
  it("leaves path fields unchanged when no remote context", () => {
    const obj = {
      path: "D:\\git\\proj\\src",
      file: "D:\\git\\proj\\src\\Foo.java",
      rootPath: "D:\\git\\proj",
      outputLocation: "D:\\git\\proj\\target\\classes",
      fqn: "pkg.Foo",
    };
    remapJsonPaths(obj);
    expect(obj.path).toBe("D:\\git\\proj\\src");
    expect(obj.file).toBe("D:\\git\\proj\\src\\Foo.java");
    expect(obj.rootPath).toBe("D:\\git\\proj");
    expect(obj.outputLocation).toBe("D:\\git\\proj\\target\\classes");
    expect(obj.fqn).toBe("pkg.Foo");
  });

  it("recurses into nested structures", () => {
    const obj = { items: [{ nested: { file: "/a/b.java" } }] };
    remapJsonPaths(obj);
    expect(obj.items[0].nested.file).toBe("/a/b.java");
  });

  it("handles null and primitives safely", () => {
    expect(() => remapJsonPaths(null)).not.toThrow();
    expect(() => remapJsonPaths(42)).not.toThrow();
    expect(() => remapJsonPaths("str")).not.toThrow();
  });
});
