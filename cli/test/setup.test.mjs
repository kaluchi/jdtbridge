import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setColorEnabled } from "../src/color.mjs";

function captureConsole() {
  const logs = [];
  const errors = [];
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;
  console.log = (...args) => logs.push(args.join(" "));
  console.error = (...args) => errors.push(args.join(" "));
  process.exit = (code) => { throw new Error(`exit(${code})`); };
  return {
    logs, errors,
    restore() {
      console.log = origLog;
      console.error = origError;
      process.exit = origExit;
    },
  };
}

describe("setup command", () => {
  let io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(() => {
    io.restore();
    vi.resetModules();
  });

  function mockDeps(overrides = {}) {
    const eclipse = {
      eclipseExe: (name) => name,
      isEclipseRunning: () => false,
      findEclipsePath: () => "/mock/eclipse",
      getEclipseVersion: () => "4.33.0",
      detectProfile: () => "epp.package.java",
      getInstalledVersion: () => null,
      stopEclipse: () => true,
      startEclipse: () => 12345,
      runDirector: () => "",
      p2Install: () => "",
      p2Uninstall: () => "",
      ...overrides.eclipse,
    };

    const discovery = {
      discoverInstances: () => [],
      probe: () => Promise.resolve(),
      ...overrides.discovery,
    };

    const home = {
      readConfig: () => ({}),
      writeConfig: () => ({}),
      getHome: () => "/mock/home",
      instancesDir: () => "/mock/home/instances",
      resetHome: () => {},
      ...overrides.home,
    };

    vi.doMock("../src/eclipse.mjs", () => eclipse);
    vi.doMock("../src/discovery.mjs", () => discovery);
    vi.doMock("../src/home.mjs", () => home);

    // Mock child_process for prereq checks and maven build
    vi.doMock("node:child_process", () => ({
      execSync: vi.fn((cmd, opts) => {
        if (overrides.execSync) return overrides.execSync(cmd, opts);
        if (cmd.includes("java")) return 'openjdk version "21.0.1" 2024-01-01';
        if (cmd.includes("mvn --version")) return "Apache Maven 3.9.6";
        if (cmd.includes("mvn")) return "BUILD SUCCESS";
        return "";
      }),
      spawn: vi.fn(() => ({ unref: () => {}, pid: 99 })),
    }));

    // Mock node:fs for findRepoRoot and getBuiltRepoPath
    vi.doMock("node:fs", () => ({
      existsSync: overrides.existsSync || (() => true),
      readdirSync: overrides.readdirSync || (() => []),
      readFileSync: overrides.readFileSync || (() => ""),
      mkdirSync: () => {},
      writeFileSync: () => {},
    }));
  }

  async function importSetup() {
    return import("../src/commands/setup.mjs");
  }

  // ---- --check mode ----

  describe("--check mode", () => {
    it("shows prerequisites section", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(io.logs.some((l) => l.includes("Prerequisites"))).toBe(true);
      expect(io.logs.some((l) => l.includes("Node.js"))).toBe(true);
    });

    it("shows Eclipse section with version", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(io.logs.some((l) => l.includes("Eclipse"))).toBe(true);
      expect(io.logs.some((l) => l.includes("/mock/eclipse"))).toBe(true);
      expect(io.logs.some((l) => l.includes("4.33.0"))).toBe(true);
    });

    it("shows profile", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(
        io.logs.some((l) => l.includes("epp.package.java")),
      ).toBe(true);
    });

    it("shows Eclipse not found when no path", async () => {
      mockDeps({ eclipse: { findEclipsePath: () => null } });
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(
        io.logs.some((l) => l.includes("Eclipse not found")),
      ).toBe(true);
    });

    it("shows Bridge section", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(io.logs.some((l) => l.includes("Bridge"))).toBe(true);
    });

    it("shows live instances", async () => {
      mockDeps({
        discovery: {
          discoverInstances: () => [
            { port: 7891, pid: 123, workspace: "D:/ws" },
          ],
        },
      });
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(
        io.logs.some((l) => l.includes("port 7891")),
      ).toBe(true);
    });

    it("shows Plugin source section", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(
        io.logs.some((l) => l.includes("Plugin source")),
      ).toBe(true);
    });

    it("shows plugin version from bridge instance", async () => {
      mockDeps({
        discovery: {
          discoverInstances: () => [
            { port: 9999, pid: 1, workspace: "/ws", version: "1.0.5", location: "file:plugins/bundle.jar" },
          ],
        },
      });
      const { setup } = await importSetup();
      await setup(["--check"]);
      expect(io.logs.some((l) => l.includes("1.0.5"))).toBe(true);
    });
  });

  // ---- default (install) mode ----

  describe("install mode", () => {
    it("exits if prerequisites fail", async () => {
      mockDeps({
        execSync: (cmd) => {
          if (cmd.includes("java")) throw new Error("not found");
          if (cmd.includes("mvn --version")) throw new Error("not found");
          return "";
        },
      });
      const { setup } = await importSetup();
      await expect(setup([])).rejects.toThrow("exit(1)");
      expect(io.errors.some((l) => l.includes("Missing prerequisites"))).toBe(true);
    });

    it("exits if Eclipse not found and no TTY", async () => {
      mockDeps({ eclipse: { findEclipsePath: () => null } });
      const { setup } = await importSetup();
      // In non-TTY, ask() returns "" which fails validation
      await expect(setup([])).rejects.toThrow("exit(1)");
    });

    it("exits if profile not detected", async () => {
      mockDeps({ eclipse: { detectProfile: () => null } });
      const { setup } = await importSetup();
      await expect(setup([])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("p2 profile")),
      ).toBe(true);
    });

    it("shows skip-build message", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--skip-build"]);
      expect(
        io.logs.some((l) => l.includes("Build skipped")),
      ).toBe(true);
    });

    it("calls p2Install on fresh install", async () => {
      const p2InstallFn = vi.fn();
      mockDeps({
        eclipse: { p2Install: p2InstallFn },
      });
      const { setup } = await importSetup();
      await setup(["--skip-build"]);
      expect(p2InstallFn).toHaveBeenCalled();
    });

    it("calls p2Uninstall before install when upgrading", async () => {
      const calls = [];
      mockDeps({
        eclipse: {
          getInstalledVersion: () => "1.0.0",
          p2Uninstall: () => calls.push("uninstall"),
          p2Install: () => calls.push("install"),
        },
      });
      const { setup } = await importSetup();
      await setup(["--skip-build"]);
      expect(calls).toEqual(["uninstall", "install"]);
    });

    it("captures workspace BEFORE stopping Eclipse", async () => {
      const startEclipseFn = vi.fn(() => 12345);
      let stopCalled = false;
      mockDeps({
        eclipse: {
          isEclipseRunning: () => !stopCalled,
          stopEclipse: () => { stopCalled = true; return true; },
          startEclipse: startEclipseFn,
        },
        discovery: {
          discoverInstances: () =>
            stopCalled
              ? [] // After stop, no live instances
              : [{ port: 7891, pid: 123, workspace: "D:/my-workspace" }],
        },
      });
      const { setup } = await importSetup();
      await setup(["--skip-build"]);

      // Eclipse should be restarted with the captured workspace
      expect(startEclipseFn).toHaveBeenCalledWith(
        "/mock/eclipse",
        "D:/my-workspace",
      );
    });

    it("starts Eclipse even if it was not running before install", async () => {
      const startEclipseFn = vi.fn().mockReturnValue(12345);
      mockDeps({
        eclipse: {
          isEclipseRunning: () => false,
          startEclipse: startEclipseFn,
        },
      });
      const { setup } = await importSetup();
      await setup(["--skip-build"]);
      expect(startEclipseFn).toHaveBeenCalled();
    });

    it("shows Setup complete on success", async () => {
      mockDeps();
      const { setup } = await importSetup();
      await setup(["--skip-build"]);
      expect(
        io.logs.some((l) => l.includes("Setup complete")),
      ).toBe(true);
    });

    it("passes --eclipse flag to config", async () => {
      const readConfigFn = vi.fn(() => ({}));
      mockDeps({ home: { readConfig: readConfigFn, writeConfig: () => ({}) } });
      const { setup } = await importSetup();
      await setup(["--skip-build", "--eclipse", "/custom/path"]);
      // The function should have been called, and eclipse flag applied
      expect(readConfigFn).toHaveBeenCalled();
    });
  });

  // ---- --remove mode ----

  describe("--remove mode", () => {
    it("exits if Eclipse not found", async () => {
      mockDeps({ eclipse: { findEclipsePath: () => null } });
      const { setup } = await importSetup();
      await expect(setup(["--remove"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("Eclipse not found")),
      ).toBe(true);
    });

    it("exits if profile not detected", async () => {
      mockDeps({ eclipse: { detectProfile: () => null } });
      const { setup } = await importSetup();
      await expect(setup(["--remove"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("p2 profile")),
      ).toBe(true);
    });

    it("shows not installed when no plugin", async () => {
      mockDeps({ eclipse: { getInstalledVersion: () => null } });
      const { setup } = await importSetup();
      await setup(["--remove"]);
      expect(
        io.logs.some((l) => l.includes("not installed")),
      ).toBe(true);
    });

    it("calls p2Uninstall when plugin is installed", async () => {
      const p2UninstallFn = vi.fn();
      mockDeps({
        eclipse: {
          getInstalledVersion: () => "1.0.0",
          isEclipseRunning: () => false,
          p2Uninstall: p2UninstallFn,
        },
      });
      const { setup } = await importSetup();
      await setup(["--remove"]);
      expect(p2UninstallFn).toHaveBeenCalled();
      expect(
        io.logs.some((l) => l.includes("Plugin removed")),
      ).toBe(true);
    });

    it("exits on p2Uninstall error", async () => {
      mockDeps({
        eclipse: {
          getInstalledVersion: () => "1.0.0",
          isEclipseRunning: () => false,
          p2Uninstall: () => { throw new Error("p2 error"); },
        },
      });
      const { setup } = await importSetup();
      await expect(setup(["--remove"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("p2 error")),
      ).toBe(true);
    });
  });

  // ---- help export ----

  describe("help", () => {
    it("exports help text", async () => {
      mockDeps();
      const { help } = await importSetup();
      expect(help).toContain("jdt setup");
      expect(help).toContain("--check");
      expect(help).toContain("--remove");
      expect(help).toContain("--skip-build");
      expect(help).toContain("--clean");
      expect(help).toContain("--eclipse");
    });
  });
});
