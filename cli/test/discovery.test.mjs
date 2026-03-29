import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resetHome } from "../src/home.mjs";
import {
  discoverInstances,
  findInstance,
} from "../src/discovery.mjs";

describe("discovery", () => {
  let testDir;
  let instDir;
  let origEnv;
  let servers = [];

  beforeEach(() => {
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-disc-"));
    instDir = join(testDir, "instances");
    mkdirSync(instDir, { recursive: true });
    origEnv = process.env.JDTBRIDGE_HOME;
    process.env.JDTBRIDGE_HOME = testDir;
    resetHome();
  });

  afterEach(async () => {
    if (origEnv !== undefined) {
      process.env.JDTBRIDGE_HOME = origEnv;
    } else {
      delete process.env.JDTBRIDGE_HOME;
    }
    resetHome();
    for (const s of servers) {
      await new Promise((r) => s.close(r));
    }
    servers = [];
  });

  /** Start a mock HTTP server, return its port. */
  async function startMock() {
    const srv = createServer((req, res) => {
      res.writeHead(200);
      res.end("ok");
    });
    await new Promise((r) => srv.listen(0, "127.0.0.1", r));
    servers.push(srv);
    return srv.address().port;
  }

  function writeInstance(name, data) {
    writeFileSync(join(instDir, name), JSON.stringify(data));
  }

  describe("discoverInstances", () => {
    it("returns empty array when no instances", async () => {
      expect(await discoverInstances()).toEqual([]);
    });

    it("returns instance when bridge responds", async () => {
      const port = await startMock();
      writeInstance("abc123.json", {
        port,
        token: "tok",
        pid: process.pid,
        workspace: "D:/ws",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
      expect(instances[0].port).toBe(port);
      expect(instances[0].token).toBe("tok");
      expect(instances[0].workspace).toBe("D:/ws");
      expect(instances[0].file).toContain("abc123.json");
    });

    it("filters out instances where bridge is not responding", async () => {
      writeInstance("dead.json", {
        port: 19999, // nothing listening
        token: "tok",
        pid: 99999999,
        workspace: "D:/ws",
      });
      expect(await discoverInstances()).toEqual([]);
    });

    it("skips files without port", async () => {
      writeInstance("noport.json", {
        token: "tok",
        pid: process.pid,
        workspace: "D:/ws",
      });
      expect(await discoverInstances()).toEqual([]);
    });

    it("skips corrupt JSON files", async () => {
      writeFileSync(join(instDir, "corrupt.json"), "{broken json");
      expect(await discoverInstances()).toEqual([]);
    });

    it("ignores non-.json files", async () => {
      writeFileSync(join(instDir, "readme.txt"), "not an instance");
      expect(await discoverInstances()).toEqual([]);
    });

    it("returns multiple live instances", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "/ws/a" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "/ws/b" });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(2);
    });

    it("passes through version and location fields", async () => {
      const port = await startMock();
      writeInstance("v.json", {
        port, token: "t", pid: process.pid, workspace: "/ws",
        version: "1.0.0.202603181541",
        location: "reference:file:plugins/io.github.kaluchi.jdtbridge_1.0.0.jar",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
      expect(instances[0].version).toBe("1.0.0.202603181541");
      expect(instances[0].location).toBe("reference:file:plugins/io.github.kaluchi.jdtbridge_1.0.0.jar");
    });

    it("works without version and location (backwards compat)", async () => {
      const port = await startMock();
      writeInstance("old.json", {
        port, token: "t", pid: process.pid, workspace: "/ws",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
      expect(instances[0].version).toBeUndefined();
      expect(instances[0].location).toBeUndefined();
    });

    it("uses JDT_BRIDGE_HOST env var", async () => {
      const port = await startMock();
      process.env.JDT_BRIDGE_HOST = "127.0.0.1";
      writeInstance("remote.json", {
        port, token: "t", pid: 1, workspace: "/ws",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
      expect(instances[0].host).toBe("127.0.0.1");
      delete process.env.JDT_BRIDGE_HOST;
    });

    it("uses host field from instance file", async () => {
      const port = await startMock();
      writeInstance("hosted.json", {
        port, token: "t", pid: 1, workspace: "/ws",
        host: "127.0.0.1",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
      expect(instances[0].host).toBe("127.0.0.1");
    });

    it("remote instance probed like local", async () => {
      // Remote host with mock server — probe works
      const port = await startMock();
      writeInstance("remote.json", {
        port, token: "t", pid: 1, workspace: "/ws",
        host: "127.0.0.1", // use localhost so mock server works
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(1);
    });

    it("filters dead instances regardless of host", async () => {
      writeInstance("dead-local.json", {
        port: 19999, token: "t1", pid: 1, workspace: "/ws/dead",
      });
      writeInstance("dead-remote.json", {
        port: 19998, token: "t2", pid: 1, workspace: "/ws/remote",
        host: "192.168.1.100",
      });
      const instances = await discoverInstances();
      expect(instances).toHaveLength(0);
    });
  });

  describe("findInstance", () => {
    it("returns null when no instances", async () => {
      expect(await findInstance()).toBeNull();
    });

    it("returns single instance without hint", async () => {
      const port = await startMock();
      writeInstance("only.json", { port, token: "t", pid: process.pid, workspace: "/ws/main" });
      const inst = await findInstance();
      expect(inst.port).toBe(port);
    });

    it("matches workspace hint", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "/ws/alpha" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "/ws/beta" });
      const inst = await findInstance("beta");
      expect(inst.port).toBe(p2);
    });

    it("matches workspace hint case-insensitively", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "/ws/MyProject" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "/ws/other" });
      const inst = await findInstance("myproject");
      expect(inst.port).toBe(p1);
    });

    it("normalizes backslashes in hint", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "D:/ws/proj" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "D:/ws/other" });
      const inst = await findInstance("D:\\ws\\proj");
      expect(inst.port).toBe(p1);
    });

    it("falls back to first when hint doesn't match", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "/ws/alpha" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "/ws/beta" });
      const inst = await findInstance("nonexistent");
      expect(inst).not.toBeNull();
    });

    it("falls back to first when no hint given with multiple instances", async () => {
      const p1 = await startMock();
      const p2 = await startMock();
      writeInstance("a.json", { port: p1, token: "t1", pid: process.pid, workspace: "/ws/alpha" });
      writeInstance("b.json", { port: p2, token: "t2", pid: process.pid, workspace: "/ws/beta" });
      const inst = await findInstance();
      expect(inst).not.toBeNull();
    });
  });
});
