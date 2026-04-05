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
    vi.doMock("../src/resolve.mjs", () => ({
      resolveInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1", file: "" }),
    }));
  }

  it("launch list shows launches as table", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { launchId: "my-server:12345", configId: "my-server", configType: "Java Application", mode: "run", terminated: false, pid: "12345" },
        { launchId: "ObjectMapperTest:99", configId: "ObjectMapperTest", configType: "JUnit", mode: "run", terminated: true, exitCode: 0, pid: "99" },
      ]));
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    const out = io.logs[0];
    expect(out).toContain("LAUNCHID");
    expect(out).toContain("CONFIGID");
    expect(out).toContain("CONFIGTYPE");
    expect(out).toContain("STATUS");
    expect(out).toContain("EXITCODE");
    expect(out).toContain("my-server:12345");
    expect(out).toContain("running");
    expect(out).toContain("ObjectMapperTest:99");
    expect(out).toContain("terminated");
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

  it("launch configs shows configurations as table with project and target", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { configId: "my-server", configType: "Java Application", project: "my-project", mainClass: "com.example.Main" },
        { configId: "AllTests", configType: "JUnit", project: "my-project", class: "com.example.AllTests", runner: "JUnit 5" },
        { configId: "jdtbridge-verify", configType: "Maven Build", goals: "clean verify" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    const out = io.logs[0];
    expect(out).toContain("CONFIGID");
    expect(out).toContain("CONFIGTYPE");
    expect(out).toContain("PROJECT");
    expect(out).toContain("TARGET");
    expect(out).toContain("my-server");
    expect(out).toContain("my-project");
    expect(out).toContain("com.example.Main");
    expect(out).toContain("AllTests");
    expect(out).toContain("com.example.AllTests");
    expect(out).toContain("clean verify");
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
      expect(req.url).toContain("launchId=old-test");
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
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:111", mode: "run", pid: "111" }));
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
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:111", mode: "run", pid: "111" }));
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
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:222", mode: "debug", pid: "222" }));
    });
    const { launchDebug } = await import("../src/commands/launch.mjs");
    await launchDebug(["my-server"]);
    expect(io.logs[0]).toContain("debug");
  });

  it("launch run missing name exits", async () => {
    const { launchRun } = await import("../src/commands/launch.mjs");
    await expect(launchRun([])).rejects.toThrow("exit(1)");
  });

  it("launch run passes extra args after --", async () => {
    let receivedUrl;
    await setupMock((req, res) => {
      receivedUrl = req.url;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, configId: "npm-test", launchId: "npm-test:333", mode: "run", pid: "333" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["npm-test", "-q", "--", "test/paths.test.mjs"]);
    expect(receivedUrl).toContain("configId=npm-test");
    expect(receivedUrl).toContain("args=test%2Fpaths.test.mjs");
  });

  it("launch run passes multiple extra args joined", async () => {
    let receivedUrl;
    await setupMock((req, res) => {
      receivedUrl = req.url;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:444", mode: "run", pid: "444" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server", "-q", "--", "--port", "8080", "--debug"]);
    expect(receivedUrl).toContain("args=--port%208080%20--debug");
  });

  it("launch run without -- sends no args param", async () => {
    let receivedUrl;
    await setupMock((req, res) => {
      receivedUrl = req.url;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:555", mode: "run", pid: "555" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server", "-q"]);
    expect(receivedUrl).not.toContain("args=");
  });

  it("launch debug passes extra args after --", async () => {
    let receivedUrl;
    await setupMock((req, res) => {
      receivedUrl = req.url;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:666", mode: "debug", pid: "666" }));
    });
    const { launchDebug } = await import("../src/commands/launch.mjs");
    await launchDebug(["my-server", "-q", "--", "-Xmx2g"]);
    expect(receivedUrl).toContain("debug");
    expect(receivedUrl).toContain("args=-Xmx2g");
  });

  it("launch stop shows stopped", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, configId: "my-server" }));
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
        res.end(JSON.stringify({ ok: true, configId: "my-build", launchId: "my-build:333", mode: "run", pid: "333" }));
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
      res.end(JSON.stringify({ ok: true, configId: "my-server", launchId: "my-server:111", mode: "run", pid: "111" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server"]);
    expect(io.logs[0]).toContain("Launched");
  });

  it("launch config shows KEY VALUE table by default", async () => {
    const detail = {
      configId: "ObjectMapperTest", configType: "JUnit", configTypeId: "org.eclipse.jdt.junit.launchconfig",
      file: "/ws/.metadata/.plugins/org.eclipse.debug.core/ObjectMapperTest.launch",
      attributes: {
        "org.eclipse.jdt.launching.MAIN_TYPE": "com.example.ObjectMapperTest",
        "org.eclipse.jdt.launching.PROJECT_ATTR": "my-project",
        "org.eclipse.jdt.junit.TEST_KIND": "org.eclipse.jdt.junit.loader.junit5",
      },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["ObjectMapperTest"]);
    const out = io.logs.join("\n");
    expect(out).toContain("KEY");
    expect(out).toContain("VALUE");
    expect(out).toContain("ObjectMapperTest");
    expect(out).toContain("my-project");
    expect(out).toContain("com.example.ObjectMapperTest");
    expect(out).toContain("org.eclipse.jdt.junit.TEST_KIND");
  });

  it("launch config text shows synthesized Target as FQMN", async () => {
    const detail = {
      configId: "FooTest", configType: "JUnit", configTypeId: "org.eclipse.jdt.junit.launchconfig",
      attributes: {
        "org.eclipse.jdt.launching.MAIN_TYPE": "com.example.FooTest",
        "org.eclipse.jdt.junit.TESTNAME": "shouldPass",
        "org.eclipse.jdt.launching.PROJECT_ATTR": "my-proj",
      },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["FooTest"]);
    const out = io.logs.join("\n");
    expect(out).toContain("com.example.FooTest#shouldPass");
    expect(out).toContain("Target");
  });

  it("launch config text shows Java Application target", async () => {
    const detail = {
      configId: "MyApp", configType: "Java Application", configTypeId: "org.eclipse.jdt.launching.localJavaApplication",
      attributes: {
        "org.eclipse.jdt.launching.MAIN_TYPE": "com.example.Main",
        "org.eclipse.jdt.launching.PROJECT_ATTR": "my-app",
      },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["MyApp"]);
    const out = io.logs.join("\n");
    expect(out).toContain("com.example.Main");
    expect(out).toContain("Target");
    expect(out).not.toContain("#");
  });

  it("launch config text shows Maven goals as target", async () => {
    const detail = {
      configId: "my-build", configType: "Maven Build", configTypeId: "org.eclipse.m2e.Maven2LaunchConfigurationType",
      attributes: { "M2_GOALS": "clean verify", "M2_THREADS": 4 },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["my-build"]);
    const out = io.logs.join("\n");
    expect(out).toContain("clean verify");
    expect(out).toContain("Target");
  });

  it("launch config text aligns multiline values", async () => {
    const detail = {
      configId: "Test", configType: "JUnit", configTypeId: "org.eclipse.jdt.junit.launchconfig",
      attributes: {
        "org.eclipse.jdt.launching.VM_ARGUMENTS": "-ea\n-javaagent:mock.jar",
        "org.eclipse.jdt.launching.MAIN_TYPE": "com.Test",
      },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["Test"]);
    const out = io.logs.join("\n");
    const lines = out.split("\n");
    const vmLine = lines.find((l) => l.includes("VM_ARGUMENTS"));
    const contLine = lines[lines.indexOf(vmLine) + 1];
    expect(vmLine).toContain("-ea");
    expect(contLine).toContain("-javaagent:mock.jar");
    // continuation should be indented to align with VALUE column
    expect(contLine).toMatch(/^\s+-javaagent/);
  });

  it("launch config --json outputs raw JSON", async () => {
    const detail = {
      configId: "ObjectMapperTest", configType: "JUnit", configTypeId: "org.eclipse.jdt.junit.launchconfig",
      file: "/ws/ObjectMapperTest.launch",
      attributes: { "org.eclipse.jdt.launching.MAIN_TYPE": "com.example.ObjectMapperTest" },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["ObjectMapperTest", "--json"]);
    const out = io.logs.join("\n");
    const parsed = JSON.parse(out);
    expect(parsed.configId).toBe("ObjectMapperTest");
    expect(parsed.attributes).toBeDefined();
  });

  it("launch config text omits Target when no class or goals", async () => {
    const detail = {
      configId: "my-project", configType: "JUnit", configTypeId: "org.eclipse.jdt.junit.launchconfig",
      attributes: {
        "org.eclipse.jdt.launching.MAIN_TYPE": "",
        "org.eclipse.jdt.junit.CONTAINER": "=my-project",
        "org.eclipse.jdt.launching.PROJECT_ATTR": "my-project",
      },
    };
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(detail));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["my-project"]);
    const out = io.logs.join("\n");
    expect(out).toContain("Project");
    expect(out).not.toContain("Target");
  });

  it("launch config --xml shows raw XML", async () => {
    const xml = '<?xml version="1.0"?>\n<launchConfiguration type="org.eclipse.jdt.junit.launchconfig"/>';
    await setupMock((req, res) => {
      expect(req.url).toContain("format=xml");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ name: "ObjectMapperTest", file: "/test.launch", xml }));
    });
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["ObjectMapperTest", "--xml"]);
    expect(io.logs[0]).toContain("launchConfiguration");
  });

  it("launch config missing name exits", async () => {
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await expect(launchConfig([])).rejects.toThrow("exit(1)");
  });

  it("launch config error shows message", async () => {
    await setupMock(errorServer());
    const { launchConfig } = await import("../src/commands/launch.mjs");
    await launchConfig(["nonexistent"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("launch configs shows method in target", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { configId: "FooTest.bar", configType: "JUnit", project: "proj", class: "com.FooTest", method: "bar" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    const out = io.logs[0];
    expect(out).toContain("com.FooTest#bar");
  });
});
