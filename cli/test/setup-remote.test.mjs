import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resetHome } from "../src/home.mjs";
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

describe("jdt setup remote", () => {
  let testDir, io, origEnv;

  beforeEach(() => {
    setColorEnabled(false);
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-remote-"));
    origEnv = process.env.JDTBRIDGE_HOME;
    process.env.JDTBRIDGE_HOME = testDir;
    resetHome();
    io = captureConsole();
  });

  afterEach(() => {
    io.restore();
    if (origEnv !== undefined) {
      process.env.JDTBRIDGE_HOME = origEnv;
    } else {
      delete process.env.JDTBRIDGE_HOME;
    }
    resetHome();
    try { rmSync(testDir, { recursive: true }); } catch { /* ok */ }
  });

  async function runSetupRemote(args) {
    const { setupRemote } = await import("../src/commands/setup-remote.mjs");
    await setupRemote(args);
  }

  function createProjectDir(parentDir, projectName) {
    const projectDir = join(parentDir, projectName);
    mkdirSync(projectDir, { recursive: true });
    writeFileSync(join(projectDir, ".project"),
      `<?xml version="1.0" encoding="UTF-8"?>
<projectDescription><name>${projectName}</name></projectDescription>`);
    return projectDir;
  }

  // --- No args ---

  describe("no arguments", () => {
    it("shows onboarding with examples when no instances", async () => {
      await runSetupRemote([]);
      const output = io.logs.join("\n");
      expect(output).toContain("No remote instances configured");
      expect(output).toContain("--bridge-socket");
      expect(output).toContain("--token");
      expect(output).toContain("--add-mount-point");
      expect(output).toContain("host.docker.internal");
      expect(output).toContain("SSH tunnel");
      expect(output).toContain("<eclipse-host>");
      expect(output).toContain("<mounted-directory>");
    });

    it("shows JSON empty array when no instances", async () => {
      await runSetupRemote(["--json"]);
      const jsonOutput = JSON.parse(io.logs.join(""));
      expect(jsonOutput).toEqual([]);
    });

    it("shows status when instances exist", async () => {
      // Create an instance first
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "abc123",
      ]);
      io.logs.length = 0;

      await runSetupRemote([]);
      const output = io.logs.join("\n");
      expect(output).toContain("host.docker.internal:7777");
      expect(output).toContain("******bc123");
      expect(output).toContain("jdt setup remote --check");
    });
  });

  // --- Configure ---

  describe("configure new instance", () => {
    it("creates instance file with bridge-socket and token", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "mytoken123",
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("Wrote");
      expect(output).toContain("bridge-socket: host.docker.internal:7777");
      expect(output).toContain("******en123");

      // Verify file on disk
      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = existsSync(remoteDir)
        ? require("fs").readdirSync(remoteDir)
            .filter(f => f.endsWith(".json"))
        : [];
      expect(instanceFiles.length).toBe(1);
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));
      expect(instanceData["bridge-socket"]).toBe("host.docker.internal:7777");
      expect(instanceData.token).toBe("mytoken123");
    });

    it("auto-generates token when not provided", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("auto-generated");
      // Token should be 32 hex chars (shown in full)
      expect(output).toMatch(/[0-9a-f]{32}/);
    });

    it("scans mount point for .project files", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "my-project");
      createProjectDir(mountDir, "my-lib");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("my-project");
      expect(output).toContain("my-lib");
      expect(output).toContain("2 projects cached");
    });

    it("writes project path cache", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "cached-project");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);

      const cacheDir = join(testDir, "remote-instances", "project-paths");
      const cacheFiles = require("fs").readdirSync(cacheDir)
        .filter(f => f.endsWith(".json"));
      expect(cacheFiles.length).toBe(1);

      const cacheData = JSON.parse(
        readFileSync(join(cacheDir, cacheFiles[0]), "utf8"));
      expect(cacheData.projects["cached-project"]).toBeTruthy();
    });
  });

  // --- Update ---

  describe("update existing instance", () => {
    it("replaces token", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "old-token",
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "new-token",
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("Updated");
      expect(output).toContain("was: ******token");
    });

    it("adds mount point to existing", async () => {
      const mountDir1 = join(testDir, "mount1");
      const mountDir2 = join(testDir, "mount2");
      createProjectDir(mountDir1, "proj-a");
      createProjectDir(mountDir2, "proj-b");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir1,
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--add-mount-point", mountDir2,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("Updated");
      expect(output).toContain("added");
      expect(output).toContain("proj-b");
      // Should show specific mount path, not generic "mount points"
      expect(output).toContain(mountDir2);
    });

    it("reuses token when not specified on update", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "keep-this-token",
      ]);
      io.logs.length = 0;

      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "new-proj");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--add-mount-point", mountDir,
      ]);
      const output = io.logs.join("\n");
      // Should not mention token change
      expect(output).not.toContain("was:");
      expect(output).not.toContain("auto-generated");
    });

    it("idempotent add-mount-point", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      // Add same mount point again
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--add-mount-point", mountDir,
      ]);
      // Should rescan but not duplicate
      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));
      const mountPointCount = instanceData["mount-points"]
        .filter(mp => mp === mountDir).length;
      expect(mountPointCount).toBe(1);
    });
  });

  // --- Delete ---

  describe("delete", () => {
    it("removes instance and cache", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--delete",
        "--bridge-socket", "host.docker.internal:7777",
      ]);
      expect(io.logs.join("\n")).toContain("Removed");

      const remoteDir = join(testDir, "remote-instances");
      const remainingFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      expect(remainingFiles.length).toBe(0);
    });

    it("fails for nonexistent bridge-socket", async () => {
      await expect(runSetupRemote([
        "--delete",
        "--bridge-socket", "nonexistent:9999",
      ])).rejects.toThrow("exit(1)");
      expect(io.errors[0]).toContain("No remote instance");
    });
  });

  // --- Remove mount point ---

  describe("remove-mount-point", () => {
    it("removes mount point from config", async () => {
      const mountDir1 = join(testDir, "mount1");
      const mountDir2 = join(testDir, "mount2");
      createProjectDir(mountDir1, "proj-a");
      createProjectDir(mountDir2, "proj-b");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir1,
        "--add-mount-point", mountDir2,
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--remove-mount-point", mountDir2,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("removed");

      // Verify mount point removed from config
      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));
      expect(instanceData["mount-points"]).not.toContain(mountDir2);
      expect(instanceData["mount-points"]).toContain(mountDir1);
    });

    it("shows removed projects table", async () => {
      const mountDir1 = join(testDir, "mount1");
      const mountDir2 = join(testDir, "mount2");
      createProjectDir(mountDir1, "keep-this");
      createProjectDir(mountDir2, "remove-this");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir1,
        "--add-mount-point", mountDir2,
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--remove-mount-point", mountDir2,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("Removed from cache");
      expect(output).toContain("remove-this");
      // Removed section is between "Removed from cache" and "Scanning"
      const removedSection = output.split("Removed from cache")[1]
        .split("Scanning")[0];
      expect(removedSection).toContain("remove-this");
      expect(removedSection).not.toContain("keep-this");
      expect(output).toContain("1 projects cached");
    });
  });

  // --- .project scanning ---

  describe("project scanning", () => {
    it("reads project name from .project XML", async () => {
      const mountDir = join(testDir, "mount");
      const projectDir = join(mountDir, "my-folder");
      mkdirSync(projectDir, { recursive: true });
      writeFileSync(join(projectDir, ".project"),
        `<?xml version="1.0"?>
<projectDescription>
  <name>custom.project.name</name>
  <comment/>
</projectDescription>`);

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("custom.project.name");
    });

    it("skips directories without .project", async () => {
      const mountDir = join(testDir, "mount");
      mkdirSync(join(mountDir, "no-project-here"), { recursive: true });
      writeFileSync(join(mountDir, "no-project-here", "README.md"),
        "hello");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("0 projects cached");
    });

    it("skips node_modules and target dirs", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(join(mountDir, "node_modules"), "hidden-in-nm");
      createProjectDir(join(mountDir, "target"), "hidden-in-target");
      createProjectDir(mountDir, "visible");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      const output = io.logs.join("\n");
      expect(output).toContain("visible");
      expect(output).not.toContain("hidden-in-nm");
      expect(output).not.toContain("hidden-in-target");
      expect(output).toContain("1 projects cached");
    });
  });

  // --- JSON output ---

  describe("json output", () => {
    it("outputs JSON for configured instances", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok123",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      await runSetupRemote(["--json"]);
      const jsonOutput = JSON.parse(io.logs.join(""));
      expect(jsonOutput).toHaveLength(1);
      expect(jsonOutput[0]["bridge-socket"]).toBe(
        "host.docker.internal:7777");
      expect(jsonOutput[0].projects).toHaveLength(1);
      expect(jsonOutput[0].projects[0].project).toBe("proj");
    });

    it("outputs single remote JSON with --bridge-socket --json", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "tok123",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777", "--json",
      ]);
      const jsonOutput = JSON.parse(io.logs.join(""));
      // Single object, not array
      expect(jsonOutput["bridge-socket"]).toBe(
        "host.docker.internal:7777");
      expect(jsonOutput.projects).toHaveLength(1);
    });

    it("fails --bridge-socket --json for nonexistent remote", async () => {
      await expect(runSetupRemote([
        "--bridge-socket", "nonexistent:9999", "--json",
      ])).rejects.toThrow("exit(1)");
    });
  });

  // --- Token masking ---

  describe("token display", () => {
    it("masks token with last 5 chars in status", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
        "--token", "abcdefghij12345",
      ]);
      io.logs.length = 0;

      await runSetupRemote([]);
      const output = io.logs.join("\n");
      expect(output).toContain("******12345");
    });

    it("shows full token on auto-generate", async () => {
      await runSetupRemote([
        "--bridge-socket", "host.docker.internal:7777",
      ]);
      const output = io.logs.join("\n");
      expect(output).toMatch(/[0-9a-f]{32}/);
      expect(output).toContain("auto-generated");
    });
  });

  // --- Multiple instances ---

  describe("multiple remote instances", () => {
    it("creates separate files per bridge-socket", async () => {
      await runSetupRemote([
        "--bridge-socket", "host1:7777", "--token", "t1",
      ]);
      await runSetupRemote([
        "--bridge-socket", "host2:8888", "--token", "t2",
      ]);

      io.logs.length = 0;
      await runSetupRemote(["--json"]);
      const jsonOutput = JSON.parse(io.logs.join(""));
      expect(jsonOutput).toHaveLength(2);
      const sockets = jsonOutput.map(r => r["bridge-socket"]).sort();
      expect(sockets).toEqual(["host1:7777", "host2:8888"]);
    });
  });

  // --- Mount point operation order ---

  describe("mount-point operation order", () => {
    it("applies add and remove in argument order", async () => {
      const mountDir1 = join(testDir, "mount1");
      const mountDir2 = join(testDir, "mount2");
      const mountDir3 = join(testDir, "mount3");
      createProjectDir(mountDir1, "proj-1");
      createProjectDir(mountDir2, "proj-2");
      createProjectDir(mountDir3, "proj-3");

      // Create with mount1 and mount2
      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "t",
        "--add-mount-point", mountDir1,
        "--add-mount-point", mountDir2,
      ]);
      io.logs.length = 0;

      // Remove mount1, add mount3 — in this order
      await runSetupRemote([
        "--bridge-socket", "host:7777",
        "--remove-mount-point", mountDir1,
        "--add-mount-point", mountDir3,
      ]);

      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));

      expect(instanceData["mount-points"]).not.toContain(mountDir1);
      expect(instanceData["mount-points"]).toContain(mountDir2);
      expect(instanceData["mount-points"]).toContain(mountDir3);
    });

    it("add then remove same path = not present", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "t",
        "--add-mount-point", mountDir,
        "--remove-mount-point", mountDir,
      ]);

      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));

      expect(instanceData["mount-points"]).not.toContain(mountDir);
    });

    it("remove then add same path = present", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      // First create with mountDir
      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "t",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      // Remove then add — result: present
      await runSetupRemote([
        "--bridge-socket", "host:7777",
        "--remove-mount-point", mountDir,
        "--add-mount-point", mountDir,
      ]);

      const remoteDir = join(testDir, "remote-instances");
      const instanceFiles = require("fs").readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const instanceData = JSON.parse(
        readFileSync(join(remoteDir, instanceFiles[0]), "utf8"));

      expect(instanceData["mount-points"]).toContain(mountDir);
    });
  });

  // --- Edge cases ---

  describe("path separator normalization", () => {
    it("matches mount point with mixed slashes in status", async () => {
      // Simulate Windows: cache has backslash paths, mount point has forward
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj-a");
      createProjectDir(mountDir, "proj-b");

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      // Status should show MOUNT_POINT for all projects, not just first
      await runSetupRemote([]);
      const output = io.logs.join("\n");
      const mountPointMatches = output.split("\n").filter(
        line => line.includes("proj-") && line.includes(mountDir));
      expect(mountPointMatches.length).toBe(2);
    });

    it("matches mount point when cache uses backslashes", async () => {
      // Manually write cache with backslash paths to simulate Windows
      const mountDir = join(testDir, "mount").replace(/\\/g, "/");
      createProjectDir(join(testDir, "mount"), "my-proj");

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "tok",
        "--add-mount-point", mountDir,
      ]);

      // Rewrite cache with backslash paths
      const { readdirSync, readFileSync: readFS, writeFileSync: writeFS }
        = await import("node:fs");
      const cacheDir = join(testDir, "remote-instances", "project-paths");
      const cacheFile = readdirSync(cacheDir)
        .find(f => f.endsWith(".json"));
      const cachePath = join(cacheDir, cacheFile);
      const cache = JSON.parse(readFS(cachePath, "utf8"));
      // Replace forward slashes with backslashes in project paths
      for (const [k, v] of Object.entries(cache.projects)) {
        cache.projects[k] = v.replace(/\//g, "\\");
      }
      writeFS(cachePath, JSON.stringify(cache));

      io.logs.length = 0;
      await runSetupRemote([]);
      const output = io.logs.join("\n");
      // MOUNT_POINT column should still be populated
      const projLine = output.split("\n").find(l => l.includes("my-proj"));
      expect(projLine).toBeTruthy();
      expect(projLine).toContain(mountDir);
    });
  });

  describe("no-op update suppression", () => {
    it("does not print Updated header on --check without changes", async () => {
      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "tok",
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--check",
      ]);
      const output = io.logs.join("\n");
      expect(output).not.toContain("Updated");
    });

    it("does not print Updated when token unchanged", async () => {
      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "same-tok",
      ]);
      io.logs.length = 0;

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "same-tok",
      ]);
      const output = io.logs.join("\n");
      expect(output).not.toContain("Updated");
      expect(output).not.toContain("was:");
    });
  });

  describe("idempotent remove-mount-point", () => {
    it("is no-op when removing non-existent mount point", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      // Remove a mount point that was never added
      await runSetupRemote([
        "--bridge-socket", "host:7777",
        "--remove-mount-point", "/nonexistent/path",
      ]);

      // Mount points should be unchanged
      const remoteDir = join(testDir, "remote-instances");
      const files = readdirSync(remoteDir)
        .filter(f => f.endsWith(".json"));
      const data = JSON.parse(readFileSync(
        join(remoteDir, files[0]), "utf8"));
      expect(data["mount-points"]).toContain(mountDir);
      expect(data["mount-points"]).toHaveLength(1);
    });
  });

  describe("instance with no mount points", () => {
    it("shows status without project table", async () => {
      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "my-secret-token",
      ]);
      io.logs.length = 0;

      await runSetupRemote([]);
      const output = io.logs.join("\n");
      expect(output).toContain("host:7777");
      expect(output).toContain("******token");
      // No PROJECT table header since no mount points scanned
      expect(output).not.toContain("PROJECT");
    });
  });

  describe("json output completeness", () => {
    it("includes mountPoint in project entries", async () => {
      const mountDir = join(testDir, "mount");
      createProjectDir(mountDir, "proj");

      await runSetupRemote([
        "--bridge-socket", "host:7777", "--token", "tok",
        "--add-mount-point", mountDir,
      ]);
      io.logs.length = 0;

      await runSetupRemote(["--json"]);
      const jsonOutput = JSON.parse(io.logs.join(""));
      const project = jsonOutput[0].projects[0];
      expect(project.project).toBe("proj");
      expect(project.localPath).toBeTruthy();
      expect(project.mountPoint).toBe(mountDir);
    });
  });

  // --- Error handling ---

  describe("errors", () => {
    it("fails without bridge-socket on configure", async () => {
      await expect(runSetupRemote([
        "--token", "abc",
      ])).rejects.toThrow("exit(1)");
      expect(io.errors[0]).toContain("--bridge-socket");
    });
  });
});
