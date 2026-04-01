import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  startServer, stopServer, captureConsole, errorServer, disableColor,
} from "./helpers/mock-server.mjs";

describe("launch commands", () => {
  let server, port, io;

  beforeEach(() => {
    disableColor();
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.resetModules();
  });

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/bridge-env.mjs", () => ({
      getPinnedBridge: () => null,
    }));
    vi.doMock("../src/discovery.mjs", () => ({
      discoverInstances: async () => [],
      findInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1" }),
    }));
  }

  it("launch list shows launches as table", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", type: "Java Application", mode: "run", terminated: false, pid: "12345" },
        { name: "ObjectMapperTest", type: "JUnit", mode: "run", terminated: true, exitCode: 0 },
      ]));
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    const out = io.logs[0];
    expect(out).toContain("NAME");
    expect(out).toContain("STATUS");
    expect(out).toContain("my-server");
    expect(out).toContain("running");
    expect(out).toContain("ObjectMapperTest");
    expect(out).toContain("terminated (0)");
  });

  it("launch list empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.logs[0]).toBe("(no launches)");
  });

  it("launch logs shows output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "my-server", terminated: false,
        output: "Server started on port 8080\nReady.\n",
      }));
    });
    const { launchLogs } = await import("../src/commands/launch.mjs");
    await launchLogs(["my-server"]);
    expect(io.logs[0]).toContain("Server started");
  });

  it("launch console alias works", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "my-server", terminated: false,
        output: "Server started on port 8080\nReady.\n",
      }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server"]);
    expect(io.logs[0]).toContain("Server started");
  });

  it("launch console with tail", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("tail=10");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ name: "my-server", terminated: false, output: "last line\n" }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server", "--tail", "10"]);
    expect(io.logs[0]).toContain("last line");
  });

  it("launch configs shows configurations as table", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", type: "Java Application" },
        { name: "AllTests", type: "JUnit" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    const out = io.logs[0];
    expect(out).toContain("NAME");
    expect(out).toContain("TYPE");
    expect(out).toContain("my-server");
    expect(out).toContain("AllTests");
  });

  it("launch configs empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    expect(io.logs[0]).toBe("(no launch configurations)");
  });

  it("launch clear shows count", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ removed: 3 }));
    });
    const { launchClear } = await import("../src/commands/launch.mjs");
    await launchClear([]);
    expect(io.logs[0]).toContain("3");
    expect(io.logs[0]).toContain("launches");
  });

  it("launch clear by name", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("name=old-test");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ removed: 1 }));
    });
    const { launchClear } = await import("../src/commands/launch.mjs");
    await launchClear(["old-test"]);
    expect(io.logs[0]).toContain("1");
  });

  it("launch run shows launched with guide", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server"]);
    expect(io.logs[0]).toContain("Launched");
    const all = io.logs.join("\n");
    expect(all).toContain("jdt launch logs");
    expect(all).toContain("jdt launch stop");
  });

  it("launch run -q suppresses guide", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server", "-q"]);
    expect(io.logs).toHaveLength(1);
    expect(io.logs[0]).toContain("Launched");
  });

  it("launch debug sends debug flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("debug");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "debug" }));
    });
    const { launchDebug } = await import("../src/commands/launch.mjs");
    await launchDebug(["my-server"]);
    expect(io.logs[0]).toContain("debug");
  });

  it("launch run missing name exits", async () => {
    const { launchRun } = await import("../src/commands/launch.mjs");
    await expect(launchRun([])).rejects.toThrow("exit(1)");
  });

  it("launch stop shows stopped", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server" }));
    });
    const { launchStop } = await import("../src/commands/launch.mjs");
    await launchStop(["my-server"]);
    expect(io.logs[0]).toContain("Stopped");
  });

  it("launch stop missing name exits", async () => {
    const { launchStop } = await import("../src/commands/launch.mjs");
    await expect(launchStop([])).rejects.toThrow("exit(1)");
  });

  it("launch console missing name exits", async () => {
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await expect(launchConsole([])).rejects.toThrow("exit(1)");
  });

  it("launch console error does not exit 1", async () => {
    await setupMock(errorServer());
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("launch list error does not exit 1", async () => {
    await setupMock(errorServer());
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("launch console --follow streams text/plain", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/launch/console/stream")) {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.write("line1\n");
        res.write("line2\n");
        res.end();
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    const chunks = [];
    const origWrite = process.stdout.write;
    process.stdout.write = (chunk) => { chunks.push(chunk.toString()); return true; };
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "--follow"])).rejects.toThrow("exit(0)");
      const output = chunks.join("");
      expect(output).toContain("line1");
      expect(output).toContain("line2");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch console --follow passes stream filter", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/launch/console/stream")) {
        expect(req.url).toContain("stream=stderr");
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("error output");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "-f", "--stderr"])).rejects.toThrow("exit(0)");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch console --follow passes tail param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/launch/console/stream")) {
        expect(req.url).toContain("tail=20");
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("tail output");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "--follow", "--tail", "20"])).rejects.toThrow("exit(0)");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch run --follow launches then streams", async () => {
    let urls = [];
    await setupMock((req, res) => {
      urls.push(req.url);
      if (req.url.includes("/launch/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, name: "my-build", mode: "run" }));
      } else if (req.url.includes("/launch/console/stream")) {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("BUILD SUCCESS\n");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "my-build", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchRun } = await import("../src/commands/launch.mjs");
      await expect(launchRun(["my-build", "--follow"])).rejects.toThrow("exit(0)");
      expect(urls[0]).toContain("/launch/run");
      expect(urls[1]).toContain("/launch/console/stream");
      expect(io.errors[0]).toContain("Launched");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch run without --follow prints launched", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server"]);
    expect(io.logs[0]).toContain("Launched");
  });
});
