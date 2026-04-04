import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resetHome } from "../src/home.mjs";

describe("resolve", () => {
  let testDir, instDir;
  let origEnv;

  beforeEach(() => {
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-resolve-"));
    instDir = join(testDir, "instances");
    mkdirSync(instDir, { recursive: true });
    origEnv = {
      JDTBRIDGE_HOME: process.env.JDTBRIDGE_HOME,
      JDT_BRIDGE_PORT: process.env.JDT_BRIDGE_PORT,
      JDT_BRIDGE_TOKEN: process.env.JDT_BRIDGE_TOKEN,
      WT_SESSION: process.env.WT_SESSION,
    };
    process.env.JDTBRIDGE_HOME = testDir;
    delete process.env.JDT_BRIDGE_PORT;
    delete process.env.JDT_BRIDGE_TOKEN;
    delete process.env.WT_SESSION;
    resetHome();
  });

  afterEach(() => {
    for (const [k, v] of Object.entries(origEnv)) {
      if (v !== undefined) process.env[k] = v;
      else delete process.env[k];
    }
    resetHome();
    vi.resetModules();
  });

  function writeInstance(name, data) {
    writeFileSync(join(instDir, `${name}.json`), JSON.stringify(data));
  }

  // --- workspacePathsMatch ---

  describe("workspacePathsMatch", () => {
    let workspacePathsMatch;

    beforeEach(async () => {
      ({ workspacePathsMatch } = await import("../src/resolve.mjs"));
    });

    it("matches identical paths", () => {
      expect(workspacePathsMatch("/a/b", "/a/b")).toBe(true);
    });

    it("matches case-insensitively", () => {
      expect(workspacePathsMatch("D:\\Workspace", "d:\\workspace")).toBe(true);
    });

    it("normalizes backslashes", () => {
      expect(workspacePathsMatch("D:\\ws\\proj", "D:/ws/proj")).toBe(true);
    });

    it("rejects different paths", () => {
      expect(workspacePathsMatch("/a/b", "/a/c")).toBe(false);
    });
  });

  // --- resolveInstance ---

  describe("resolveInstance", () => {
    it("returns env var instance when JDT_BRIDGE_PORT set", async () => {
      process.env.JDT_BRIDGE_PORT = "9999";
      process.env.JDT_BRIDGE_TOKEN = "tok";
      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();
      expect(inst.port).toBe(9999);
      expect(inst.token).toBe("tok");
    });

    it("returns single discovered instance", async () => {
      writeInstance("inst1", {
        port: 58800, token: "abc", pid: process.pid,
        workspace: "/ws/one",
      });
      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();
      expect(inst.port).toBe(58800);
      expect(inst.workspace).toBe("/ws/one");
    });

    it("returns null when no instances", async () => {
      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();
      expect(inst).toBeNull();
    });

    it("uses ppid pin when ppid alive and workspace online", async () => {
      writeInstance("inst1", {
        port: 58800, token: "a", pid: process.pid,
        workspace: "/ws/one",
      });
      writeInstance("inst2", {
        port: 58900, token: "b", pid: process.pid,
        workspace: "/ws/two",
      });

      const { writePin } = await import("../src/home.mjs");
      // ppid is process.ppid — alive by definition during test
      writePin(`ppid-${process.ppid}.json`, {
        workspace: "/ws/two",
        pinnedAt: new Date().toISOString(),
      });

      const { resolveInstance } = await import("../src/resolve.mjs");
      const resolvedInstance = await resolveInstance();
      expect(resolvedInstance.port).toBe(58900);
      expect(resolvedInstance.workspace).toBe("/ws/two");
    });

    it("ppid pin takes priority over terminal pin", async () => {
      process.env.WT_SESSION = "shared-terminal-id";
      writeInstance("inst1", {
        port: 58800, token: "a", pid: process.pid,
        workspace: "/ws/one",
      });
      writeInstance("inst2", {
        port: 58900, token: "b", pid: process.pid,
        workspace: "/ws/two",
      });

      const { writePin } = await import("../src/home.mjs");
      // ppid pin → workspace one, terminal pin → workspace two
      writePin(`ppid-${process.ppid}.json`, {
        workspace: "/ws/one",
        pinnedAt: new Date().toISOString(),
      });
      writePin("term-shared-terminal-id.json", {
        workspace: "/ws/two",
        pinnedAt: new Date().toISOString(),
      });

      const { resolveInstance } = await import("../src/resolve.mjs");
      const resolvedInstance = await resolveInstance();
      // ppid pin wins — workspace one
      expect(resolvedInstance.port).toBe(58800);
      expect(resolvedInstance.workspace).toBe("/ws/one");
    });

    it("uses terminal pin when available", async () => {
      process.env.WT_SESSION = "test-session-id";
      writeInstance("inst1", {
        port: 58800, token: "a", pid: process.pid,
        workspace: "/ws/one",
      });
      writeInstance("inst2", {
        port: 58900, token: "b", pid: process.pid,
        workspace: "/ws/two",
      });

      const { writePin } = await import("../src/home.mjs");
      writePin("term-test-session-id.json", {
        workspace: "/ws/two",
        pinnedAt: new Date().toISOString(),
      });

      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();
      expect(inst.port).toBe(58900);
      expect(inst.workspace).toBe("/ws/two");
    });

    it("falls through when terminal pin workspace is offline", async () => {
      process.env.WT_SESSION = "test-session-id";
      writeInstance("inst1", {
        port: 58800, token: "a", pid: process.pid,
        workspace: "/ws/one",
      });

      const { writePin } = await import("../src/home.mjs");
      writePin("term-test-session-id.json", {
        workspace: "/ws/gone",
        pinnedAt: new Date().toISOString(),
      });

      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();
      // Falls through to discovery, finds inst1
      expect(inst.port).toBe(58800);
    });

    it("warns on stderr with multiple instances and no pin", async () => {
      writeInstance("inst1", {
        port: 58800, token: "a", pid: process.pid,
        workspace: "/ws/one",
      });
      writeInstance("inst2", {
        port: 58900, token: "b", pid: process.pid,
        workspace: "/ws/two",
      });

      const origWrite = process.stderr.write;
      let stderrOutput = "";
      process.stderr.write = (msg) => { stderrOutput += msg; };

      const { resolveInstance } = await import("../src/resolve.mjs");
      const inst = await resolveInstance();

      process.stderr.write = origWrite;
      expect(inst).not.toBeNull();
      expect(stderrOutput).toContain("Multiple Eclipse instances");
      expect(stderrOutput).toContain("jdt use");
    });
  });

  // --- writePinFiles ---

  describe("writePinFiles", () => {
    it("writes ppid pin file", async () => {
      const { writePinFiles } = await import("../src/resolve.mjs");
      const { readPin } = await import("../src/home.mjs");
      writePinFiles("/ws/test");
      const pin = readPin(`ppid-${process.ppid}.json`);
      expect(pin).not.toBeNull();
      expect(pin.workspace).toBe("/ws/test");
    });

    it("writes terminal pin when WT_SESSION set", async () => {
      process.env.WT_SESSION = "write-test-session";
      const { writePinFiles } = await import("../src/resolve.mjs");
      const { readPin } = await import("../src/home.mjs");
      writePinFiles("/ws/test");
      const pin = readPin("term-write-test-session.json");
      expect(pin).not.toBeNull();
      expect(pin.workspace).toBe("/ws/test");
    });
  });

  // --- cleanStalePins ---

  describe("cleanStalePins", () => {
    it("removes ppid pin with dead PID", async () => {
      const { writePin, readPin } = await import("../src/home.mjs");
      writePin("ppid-999999.json", { workspace: "/ws/x", pinnedAt: "" });
      const { cleanStalePins } = await import("../src/resolve.mjs");
      cleanStalePins([]);
      expect(readPin("ppid-999999.json")).toBeNull();
    });

    it("removes terminal pin with offline workspace", async () => {
      const { writePin, readPin } = await import("../src/home.mjs");
      writePin("term-old-session.json", { workspace: "/ws/gone", pinnedAt: "" });
      const { cleanStalePins } = await import("../src/resolve.mjs");
      cleanStalePins([{ workspace: "/ws/other", port: 1 }]);
      expect(readPin("term-old-session.json")).toBeNull();
    });

    it("keeps terminal pin with live workspace", async () => {
      const { writePin, readPin } = await import("../src/home.mjs");
      writePin("term-live-session.json", { workspace: "/ws/live", pinnedAt: "" });
      const { cleanStalePins } = await import("../src/resolve.mjs");
      cleanStalePins([{ workspace: "/ws/live", port: 1 }]);
      expect(readPin("term-live-session.json")).not.toBeNull();
    });
  });
});
