import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  existsSync,
  readdirSync,
} from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resetHome } from "../src/home.mjs";
import { setColorEnabled } from "../src/color.mjs";
import {
  agentList,
  agentStop,
  agentLogs,
  agentProviders,
  agentRun,
  resolveBridge,
  printBootstrapChecks,
} from "../src/commands/agent.mjs";

function captureConsole() {
  const logs = [];
  const errors = [];
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;
  console.log = (...args) => logs.push(args.join(" "));
  console.error = (...args) => errors.push(args.join(" "));
  process.exit = (code) => {
    throw new Error(`exit(${code})`);
  };
  return {
    logs,
    errors,
    restore() {
      console.log = origLog;
      console.error = origError;
      process.exit = origExit;
    },
  };
}

describe("agent commands", () => {
  let testDir;
  let agentsPath;
  let origEnv;
  let io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-agent-"));
    agentsPath = join(testDir, "agents");
    mkdirSync(agentsPath, { recursive: true });
    origEnv = process.env.JDTBRIDGE_HOME;
    process.env.JDTBRIDGE_HOME = testDir;
    resetHome();
  });

  afterEach(() => {
    io.restore();
    if (origEnv !== undefined) {
      process.env.JDTBRIDGE_HOME = origEnv;
    } else {
      delete process.env.JDTBRIDGE_HOME;
    }
    resetHome();
  });

  function writeSession(name, data) {
    writeFileSync(
      join(agentsPath, `${name}.json`),
      JSON.stringify({ name, ...data }),
    );
  }

  describe("providers", () => {
    it("lists available providers", async () => {
      await agentProviders();
      expect(io.logs.some((l) => l.includes("local"))).toBe(true);
      expect(io.logs.some((l) => l.includes("sandbox"))).toBe(true);
    });
  });

  describe("list", () => {
    it("shows empty message when no sessions", async () => {
      await agentList();
      expect(io.logs.some((l) => l.includes("No agent sessions"))).toBe(true);
    });

    it("shows sessions from files", async () => {
      writeSession("test-1", {
        provider: "local",
        agent: "claude",
        pid: process.pid, // current process — alive
        startedAt: Date.now(),
      });
      await agentList();
      expect(io.logs.some((l) => l.includes("test-1"))).toBe(true);
      expect(io.logs.some((l) => l.includes("local"))).toBe(true);
      expect(io.logs.some((l) => l.includes("claude"))).toBe(true);
    });

    it("cleans up stale sessions", async () => {
      writeSession("dead-session", {
        provider: "local",
        agent: "claude",
        pid: 99999999, // non-existent PID
        startedAt: Date.now() - 60000,
      });
      await agentList();
      // Session shown but file should be cleaned up
      expect(io.logs.some((l) => l.includes("dead-session"))).toBe(true);
      const files = readdirSync(agentsPath);
      expect(files).toHaveLength(0);
    });
  });

  describe("stop", () => {
    it("errors when no sessions exist", async () => {
      await expect(agentStop([])).rejects.toThrow("exit(1)");
      expect(io.errors.some((l) => l.includes("No agent sessions"))).toBe(
        true,
      );
    });

    it("shows choices when multiple sessions match", async () => {
      writeSession("local-claude-111", {
        provider: "local",
        agent: "claude",
        pid: 99999999,
        startedAt: Date.now(),
      });
      writeSession("sandbox-claude-222", {
        provider: "sandbox",
        agent: "claude",
        pid: 99999998,
        startedAt: Date.now(),
      });
      await expect(agentStop(["claude"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("Multiple sessions")),
      ).toBe(true);
      expect(
        io.errors.some((l) => l.includes("jdt agent stop local-claude-111")),
      ).toBe(true);
      expect(
        io.errors.some((l) =>
          l.includes("jdt agent stop sandbox-claude-222"),
        ),
      ).toBe(true);
    });

    it("errors when session not found", async () => {
      await expect(agentStop(["nonexistent"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("No agent session found")),
      ).toBe(true);
    });

    it("removes session file on stop", async () => {
      writeSession("to-stop", {
        provider: "local",
        agent: "claude",
        pid: 99999999, // dead PID — kill will fail silently
        startedAt: Date.now(),
      });
      await agentStop(["to-stop"]);
      expect(io.logs.some((l) => l.includes("Stopped to-stop"))).toBe(true);
      expect(existsSync(join(agentsPath, "to-stop.json"))).toBe(false);
    });

    it("stops single session without args", async () => {
      writeSession("only-one", {
        provider: "local",
        agent: "claude",
        pid: 99999999,
        startedAt: Date.now(),
      });
      await agentStop([]);
      expect(io.logs.some((l) => l.includes("Stopped only-one"))).toBe(true);
      expect(existsSync(join(agentsPath, "only-one.json"))).toBe(false);
    });

    it("matches by agent name", async () => {
      writeSession("local-claude-123", {
        provider: "local",
        agent: "claude",
        pid: 99999999,
        startedAt: Date.now(),
      });
      await agentStop(["claude"]);
      expect(io.logs.some((l) => l.includes("Stopped local-claude-123"))).toBe(true);
    });

    it("matches by partial name", async () => {
      writeSession("sandbox-gemini-456", {
        provider: "sandbox",
        agent: "gemini",
        pid: 99999999,
        startedAt: Date.now(),
      });
      await agentStop(["gemini-456"]);
      expect(io.logs.some((l) => l.includes("Stopped sandbox-gemini-456"))).toBe(true);
    });
  });

  describe("logs", () => {
    it("errors when no sessions exist", async () => {
      await expect(agentLogs([])).rejects.toThrow("exit(1)");
      expect(io.errors.some((l) => l.includes("No agent sessions"))).toBe(true);
    });

    it("errors when session not found", async () => {
      await expect(agentLogs(["nonexistent"])).rejects.toThrow("exit(1)");
      expect(
        io.errors.some((l) => l.includes("No agent session found")),
      ).toBe(true);
    });

    it("shows message for local sessions", async () => {
      writeSession("local-sess", {
        provider: "local",
        agent: "claude",
        pid: process.pid,
        startedAt: Date.now(),
      });
      await agentLogs(["local-sess"]);
      expect(
        io.logs.some((l) => l.includes("not available")),
      ).toBe(true);
    });
  });

  describe("run", () => {
    it("errors when no provider given", async () => {
      await expect(agentRun([])).rejects.toThrow("exit(1)");
      expect(io.errors.some((l) => l.includes("Usage"))).toBe(true);
    });

    it("errors when no agent given", async () => {
      await expect(agentRun(["local"])).rejects.toThrow("exit(1)");
      expect(io.errors.some((l) => l.includes("Usage"))).toBe(true);
    });

    it("errors on unknown provider", async () => {
      await expect(agentRun(["nonexistent", "claude"])).rejects.toThrow(
        "exit(1)",
      );
      expect(
        io.errors.some((l) => l.includes("Unknown provider")),
      ).toBe(true);
    });

    it("generates default name from provider and agent", async () => {
      // Smoke test — full integration requires a mock bridge
    });
  });

  describe("resolveBridge", () => {
    it("returns env vars from session config", async () => {
      const result = await resolveBridge({
        bridgePort: 12345,
        bridgeToken: "tok123",
        bridgeHost: "10.0.0.1",
      });
      expect(result.JDT_BRIDGE_PORT).toBe("12345");
      expect(result.JDT_BRIDGE_TOKEN).toBe("tok123");
      expect(result.JDT_BRIDGE_HOST).toBe("10.0.0.1");
    });

    it("defaults host to 127.0.0.1", async () => {
      const result = await resolveBridge({
        bridgePort: 9999,
        bridgeToken: "tok",
      });
      expect(result.JDT_BRIDGE_HOST).toBe("127.0.0.1");
    });
  });

  describe("printBootstrapChecks", () => {
    it("shows CLAUDE.md found", () => {
      const dir = mkdtempSync(join(tmpdir(), "jdtbridge-boot-"));
      writeFileSync(join(dir, "CLAUDE.md"), "# test");
      mkdirSync(join(dir, ".claude", "agents"), { recursive: true });
      writeFileSync(join(dir, ".claude", "agents", "Explore.md"), "");
      writeFileSync(join(dir, ".claude", "agents", "Plan.md"), "");
      printBootstrapChecks(dir);
      expect(io.logs.some((l) => l.includes("CLAUDE.md"))).toBe(true);
      expect(io.logs.some((l) => l.includes("Explore"))).toBe(true);
      expect(io.logs.some((l) => l.includes("Plan"))).toBe(true);
    });

    it("shows missing when no CLAUDE.md", () => {
      const dir = mkdtempSync(join(tmpdir(), "jdtbridge-boot-"));
      mkdirSync(join(dir, ".claude"), { recursive: true });
      printBootstrapChecks(dir);
      expect(io.logs.some((l) => l.includes("not found"))).toBe(true);
    });

    it("shows missing .claude directory", () => {
      const dir = mkdtempSync(join(tmpdir(), "jdtbridge-boot-"));
      printBootstrapChecks(dir);
      expect(io.logs.some((l) => l.includes(".claude/"))).toBe(true);
    });

    it("detects hooks in settings.local.json", () => {
      const dir = mkdtempSync(join(tmpdir(), "jdtbridge-boot-"));
      writeFileSync(join(dir, "CLAUDE.md"), "");
      mkdirSync(join(dir, ".claude"), { recursive: true });
      writeFileSync(join(dir, ".claude", "settings.local.json"),
        JSON.stringify({
          hooks: {
            PreToolUse: [{ hooks: [{ command: "jdt refs check" }] }],
            PostToolUse: [{ hooks: [{ command: "jdt refresh" }] }],
          },
        }));
      printBootstrapChecks(dir);
      expect(io.logs.some((l) => l.includes("PreToolUse"))).toBe(true);
      expect(io.logs.some((l) => l.includes("PostToolUse"))).toBe(true);
    });
  });
});
