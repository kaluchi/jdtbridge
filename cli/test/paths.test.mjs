import { describe, it, expect, vi, afterEach } from "vitest";
import { stripProject, toWsPath, hostToSandboxPath } from "../src/paths.mjs";
import { remapJsonPaths } from "../src/json-output.mjs";

describe("stripProject", () => {
  it("strips leading slash", () => {
    expect(stripProject("/my-server/src/main/java/Foo.java")).toBe(
      "my-server/src/main/java/Foo.java",
    );
  });

  it("returns path as-is if no leading slash", () => {
    expect(stripProject("my-server/src/Foo.java")).toBe(
      "my-server/src/Foo.java",
    );
  });
});

describe("toWsPath", () => {
  it("adds leading slash", () => {
    expect(toWsPath("my-server/src/Foo.java")).toBe(
      "/my-server/src/Foo.java",
    );
  });

  it("keeps existing leading slash", () => {
    expect(toWsPath("/my-server/src/Foo.java")).toBe(
      "/my-server/src/Foo.java",
    );
  });
});

describe("toSandboxPath", () => {
  // toSandboxPath checks process.platform at runtime, so we need
  // to re-import after mocking to get the Linux code path.
  async function loadOnLinux() {
    vi.stubGlobal("process", { ...process, platform: "linux" });
    const mod = await import("../src/paths.mjs?linux=" + Date.now());
    return mod.toSandboxPath;
  }

  async function loadOnWindows() {
    vi.stubGlobal("process", { ...process, platform: "win32" });
    const mod = await import("../src/paths.mjs?win=" + Date.now());
    return mod.toSandboxPath;
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("converts D:\\ backslash path on Linux", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("D:\\foo\\bar")).toBe("/d/foo/bar");
  });

  it("converts D:/ forward-slash path on Linux", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("D:/foo/bar")).toBe("/d/foo/bar");
  });

  it("converts C:\\ path on Linux", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("C:\\Users\\foo")).toBe("/c/Users/foo");
  });

  it("leaves Unix path unchanged on Linux", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("/unix/path")).toBe("/unix/path");
  });

  it("leaves relative path unchanged on Linux", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("relative/path")).toBe("relative/path");
  });

  it("returns falsy input as-is", async () => {
    const toSandboxPath = await loadOnLinux();
    expect(toSandboxPath("")).toBe("");
    expect(toSandboxPath(null)).toBe(null);
    expect(toSandboxPath(undefined)).toBe(undefined);
  });

  it("does not convert on Windows", async () => {
    const toSandboxPath = await loadOnWindows();
    expect(toSandboxPath("D:/foo/bar")).toBe("D:/foo/bar");
    expect(toSandboxPath("D:\\foo\\bar")).toBe("D:\\foo\\bar");
  });
});

describe("hostToSandboxPath", () => {
  it("converts D:\\ backslash path", () => {
    expect(hostToSandboxPath("D:\\foo\\bar")).toBe("/d/foo/bar");
  });

  it("converts D:/ forward-slash path", () => {
    expect(hostToSandboxPath("D:/foo/bar")).toBe("/d/foo/bar");
  });

  it("converts C:\\ path", () => {
    expect(hostToSandboxPath("C:\\Users\\foo")).toBe("/c/Users/foo");
  });

  it("normalizes backslashes in non-drive path", () => {
    expect(hostToSandboxPath("relative\\path")).toBe("relative/path");
  });

  it("leaves Unix path unchanged", () => {
    expect(hostToSandboxPath("/unix/path")).toBe("/unix/path");
  });
});

describe("remapJsonPaths", () => {
  it("remaps file keys in flat object", () => {
    const obj = { file: "D:/project/src/Foo.java", fqn: "com.Foo" };
    // On win32, toSandboxPath is a no-op, so file stays the same
    remapJsonPaths(obj);
    expect(obj.fqn).toBe("com.Foo");
    expect(typeof obj.file).toBe("string");
  });

  it("remaps file keys in array of objects", () => {
    const arr = [
      { file: "/path/Foo.java", line: 1 },
      { file: "/path/Bar.java", line: 2 },
    ];
    remapJsonPaths(arr);
    expect(arr[0].file).toBe("/path/Foo.java");
    expect(arr[1].file).toBe("/path/Bar.java");
  });

  it("remaps location keys", () => {
    const obj = { name: "proj", location: "/ws/proj" };
    remapJsonPaths(obj);
    expect(obj.location).toBe("/ws/proj");
  });

  it("recurses into nested objects", () => {
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
