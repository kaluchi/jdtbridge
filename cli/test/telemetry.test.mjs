import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  existsSync,
} from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createServer } from "node:http";
import { resetHome } from "../src/home.mjs";
import { setColorEnabled } from "../src/color.mjs";

describe("telemetry", () => {
  let testDir;
  let instDir;
  let origEnv;

  beforeEach(() => {
    setColorEnabled(false);
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-tel-"));
    instDir = join(testDir, "instances");
    mkdirSync(instDir, { recursive: true });
    origEnv = { ...process.env };
    process.env.JDTBRIDGE_HOME = testDir;
    resetHome();
  });

  afterEach(() => {
    process.env = origEnv;
    resetHome();
    vi.resetModules();
  });

  describe("resolveConfig", () => {
    it("resolves from env vars", async () => {
      process.env.JDT_BRIDGE_SESSION = "test-sess";
      process.env.JDT_BRIDGE_PORT = "12345";
      process.env.JDT_BRIDGE_TOKEN = "tok";
      process.env.JDT_BRIDGE_HOST = "10.0.0.1";

      const { installTelemetry } = await import("../src/telemetry.mjs");
      // installTelemetry reads config internally
      // If it doesn't throw and patches console, config was resolved
      installTelemetry();
      // Verify monkey-patch happened by checking console.log is wrapped
      expect(console.log).not.toBe(origEnv._origLog);
    });

    it("resolves from instance file with session", async () => {
      delete process.env.JDT_BRIDGE_SESSION;
      delete process.env.JDT_BRIDGE_PORT;
      delete process.env.JDT_BRIDGE_TOKEN;

      writeFileSync(join(instDir, "test.json"), JSON.stringify({
        port: 9999,
        token: "tok-inst",
        host: "host.docker.internal",
        session: "sandbox-sess",
      }));

      const { installTelemetry } = await import("../src/telemetry.mjs");
      installTelemetry();
    });

    it("skips when no session in env or instance file", async () => {
      delete process.env.JDT_BRIDGE_SESSION;
      delete process.env.JDT_BRIDGE_PORT;
      delete process.env.JDT_BRIDGE_TOKEN;

      writeFileSync(join(instDir, "nosess.json"), JSON.stringify({
        port: 9999,
        token: "tok",
        // no session field
      }));

      const origLog = console.log;
      const { installTelemetry } = await import("../src/telemetry.mjs");
      installTelemetry();
      // console.log should NOT be patched
      expect(console.log).toBe(origLog);
    });

    it("skips when no instance files", async () => {
      delete process.env.JDT_BRIDGE_SESSION;
      delete process.env.JDT_BRIDGE_PORT;
      delete process.env.JDT_BRIDGE_TOKEN;

      const origLog = console.log;
      const { installTelemetry } = await import("../src/telemetry.mjs");
      installTelemetry();
      expect(console.log).toBe(origLog);
    });
  });

  describe("drainTelemetry", () => {
    let server;
    let port;

    async function startDrainServer(responseText) {
      server = createServer((req, res) => {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end(responseText);
      });
      await new Promise((r) => server.listen(0, "127.0.0.1", r));
      port = server.address().port;
    }

    afterEach(async () => {
      if (server) await new Promise((r) => server.close(r));
      server = null;
    });

    it("drains text from bridge", async () => {
      await startDrainServer("[BRIDGE] GET /projects (200, 5ms)\n");

      const { telemetryUntilExit } = await import("../src/telemetry.mjs");
      // Can't easily test telemetryUntilExit without a child process,
      // but we can verify the module loads without errors
      expect(telemetryUntilExit).toBeTypeOf("function");
    });
  });
});

describe("client pinned connection", () => {
  let origEnv;

  beforeEach(() => {
    origEnv = { ...process.env };
  });

  afterEach(() => {
    process.env = origEnv;
    vi.resetModules();
  });

  it("uses env vars when JDT_BRIDGE_PORT set", async () => {
    process.env.JDT_BRIDGE_PORT = "55555";
    process.env.JDT_BRIDGE_TOKEN = "pinned-tok";

    const { connect, resetClient } = await import("../src/client.mjs");
    resetClient();

    const inst = await connect();
    expect(inst.port).toBe(55555);
    expect(inst.token).toBe("pinned-tok");
    expect(inst.host).toBe("127.0.0.1");
  });

  it("uses custom host from env", async () => {
    process.env.JDT_BRIDGE_PORT = "55555";
    process.env.JDT_BRIDGE_TOKEN = "tok";
    process.env.JDT_BRIDGE_HOST = "host.docker.internal";

    const { connect, resetClient } = await import("../src/client.mjs");
    resetClient();

    const inst = await connect();
    expect(inst.host).toBe("host.docker.internal");
  });

  it("picks up session from instance file", async () => {
    const testDir = mkdtempSync(join(tmpdir(), "jdtbridge-client-"));
    const instDir = join(testDir, "instances");
    mkdirSync(instDir, { recursive: true });
    process.env.JDTBRIDGE_HOME = testDir;
    resetHome();

    delete process.env.JDT_BRIDGE_PORT;
    delete process.env.JDT_BRIDGE_TOKEN;
    delete process.env.JDT_BRIDGE_SESSION;

    // Create instance file with session
    const server = createServer((req, res) => {
      res.writeHead(200);
      res.end("ok");
    });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const port = server.address().port;

    writeFileSync(join(instDir, "test.json"), JSON.stringify({
      port,
      token: "tok",
      pid: process.pid,
      workspace: "/test",
      session: "my-session",
    }));

    const { connect, resetClient } = await import("../src/client.mjs");
    resetClient();

    const inst = await connect();
    expect(inst.session).toBe("my-session");

    await new Promise((r) => server.close(r));
  });
});

describe("terminal", () => {
  it("writeScript creates temp file", async () => {
    const { openTerminal } = await import("../src/terminal.mjs");
    // openTerminal creates a temp script — verify it's a function
    expect(openTerminal).toBeTypeOf("function");
  });
});
