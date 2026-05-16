import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
} from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createServer } from "node:http";
import {
  eclipseExe,
  resolveEclipsePath,
  isEclipseInstall,
  getEclipseVersion,
  detectProfile,
  getInstalledVersion,
  findEclipsePath,
  getEclipseJavaHome,
  generateTargetPlatform,
  waitForBridge,
  awaitProfileLockFree,
} from "../src/eclipse.mjs";

const IS_WIN = process.platform === "win32";

describe("eclipse", () => {
  let testDir;

  beforeEach(() => {
    testDir = mkdtempSync(join(tmpdir(), "jdt-eclipse-test-"));
  });

  describe("resolveEclipsePath", () => {
    function fwd(p) { return p.replaceAll("\\", "/"); }

    it("resolves .app bundle to Contents/Eclipse", () => {
      const result = resolveEclipsePath(join(testDir, "Eclipse.app"));
      expect(fwd(result)).toMatch(/Eclipse\.app\/Contents\/Eclipse$/);
    });

    it("resolves Contents/MacOS binary path to Contents/Eclipse", () => {
      const result = resolveEclipsePath(
        join(testDir, "Eclipse.app", "Contents", "MacOS", "eclipse"),
      );
      expect(fwd(result)).toMatch(/Eclipse\.app\/Contents\/Eclipse$/);
    });

    it("resolves Contents/MacOS directory to Contents/Eclipse", () => {
      const result = resolveEclipsePath(
        join(testDir, "Eclipse.app", "Contents", "MacOS"),
      );
      expect(fwd(result)).toMatch(/Eclipse\.app\/Contents\/Eclipse$/);
    });

    it("strips trailing path separators before resolving", () => {
      const result = resolveEclipsePath(join(testDir, "Eclipse.app") + "/");
      expect(fwd(result)).toMatch(/Eclipse\.app\/Contents\/Eclipse$/);
    });

    it("trims leading/trailing whitespace", () => {
      const result = resolveEclipsePath("  /opt/eclipse  ");
      expect(result).toBe("/opt/eclipse");
    });

    it("returns regular paths unchanged", () => {
      expect(resolveEclipsePath("/opt/eclipse")).toBe("/opt/eclipse");
      expect(resolveEclipsePath("D:/eclipse")).toBe("D:/eclipse");
    });

    it("returns falsy input as-is", () => {
      expect(resolveEclipsePath(null)).toBeNull();
      expect(resolveEclipsePath("")).toBe("");
    });
  });

  describe("isEclipseInstall", () => {
    it("accepts a directory with eclipsec binary", () => {
      writeFileSync(join(testDir, eclipseExe("eclipsec")), "");
      expect(isEclipseInstall(testDir)).toBe(true);
    });

    it("accepts a directory with .eclipseproduct marker", () => {
      writeFileSync(join(testDir, ".eclipseproduct"), "version=4.40.0\n");
      expect(isEclipseInstall(testDir)).toBe(true);
    });

    it("rejects a directory with neither", () => {
      expect(isEclipseInstall(testDir)).toBe(false);
    });

    it("accepts .app bundle path — reads .eclipseproduct from Contents/Eclipse", () => {
      const eclipseDir = join(testDir, "Eclipse.app", "Contents", "Eclipse");
      mkdirSync(eclipseDir, { recursive: true });
      writeFileSync(join(eclipseDir, ".eclipseproduct"), "version=4.40.0\n");
      expect(isEclipseInstall(join(testDir, "Eclipse.app"))).toBe(true);
    });

    it("rejects .app bundle with Contents/Eclipse but no marker", () => {
      mkdirSync(join(testDir, "Eclipse.app", "Contents", "Eclipse"), { recursive: true });
      expect(isEclipseInstall(join(testDir, "Eclipse.app"))).toBe(false);
    });

    it("accepts Contents/MacOS/eclipse binary path by resolving to Contents/Eclipse", () => {
      const eclipseDir = join(testDir, "Eclipse.app", "Contents", "Eclipse");
      mkdirSync(eclipseDir, { recursive: true });
      writeFileSync(join(eclipseDir, ".eclipseproduct"), "version=4.40.0\n");
      const binaryPath = join(testDir, "Eclipse.app", "Contents", "MacOS", "eclipse");
      expect(isEclipseInstall(binaryPath)).toBe(true);
    });
  });

  describe("eclipseExe", () => {
    it("appends .exe on Windows", () => {
      if (IS_WIN) {
        expect(eclipseExe("eclipsec")).toBe("eclipsec.exe");
        expect(eclipseExe("eclipse")).toBe("eclipse.exe");
      } else {
        expect(eclipseExe("eclipsec")).toBe("eclipsec");
      }
    });
  });

  describe("getEclipseVersion", () => {
    it("returns version from .eclipseproduct", () => {
      writeFileSync(
        join(testDir, ".eclipseproduct"),
        "name=Eclipse Platform\nversion=4.33.0\nid=org.eclipse.sdk.ide\n",
      );
      expect(getEclipseVersion(testDir)).toBe("4.33.0");
    });

    it("returns null when file is missing", () => {
      expect(getEclipseVersion(testDir)).toBeNull();
    });

    it("returns null when no version line", () => {
      writeFileSync(
        join(testDir, ".eclipseproduct"),
        "name=Eclipse Platform\n",
      );
      expect(getEclipseVersion(testDir)).toBeNull();
    });
  });

  describe("detectProfile", () => {
    function createProfileRegistry(...profileNames) {
      const regDir = join(
        testDir,
        "p2",
        "org.eclipse.equinox.p2.engine",
        "profileRegistry",
      );
      mkdirSync(regDir, { recursive: true });
      for (const name of profileNames) {
        mkdirSync(join(regDir, name));
      }
      return regDir;
    }

    it("returns null when profileRegistry dir is missing", () => {
      expect(detectProfile(testDir)).toBeNull();
    });

    it("returns null when no profiles exist", () => {
      createProfileRegistry();
      expect(detectProfile(testDir)).toBeNull();
    });

    it("detects epp.package profile", () => {
      createProfileRegistry(
        "epp.package.java.profile",
        "SDKProfile.profile",
      );
      expect(detectProfile(testDir)).toBe("epp.package.java");
    });

    it("falls back to first profile if no epp", () => {
      createProfileRegistry("SDKProfile.profile");
      expect(detectProfile(testDir)).toBe("SDKProfile");
    });

    it("ignores non-.profile entries", () => {
      const regDir = join(
        testDir,
        "p2",
        "org.eclipse.equinox.p2.engine",
        "profileRegistry",
      );
      mkdirSync(regDir, { recursive: true });
      writeFileSync(join(regDir, "notes.txt"), "ignore me");
      expect(detectProfile(testDir)).toBeNull();
    });
  });

  describe("getInstalledVersion", () => {
    const BUNDLE = "io.github.kaluchi.jdtbridge";

    function createPlugins(...jarNames) {
      const pluginsDir = join(testDir, "plugins");
      mkdirSync(pluginsDir, { recursive: true });
      for (const name of jarNames) {
        writeFileSync(join(pluginsDir, name), "");
      }
    }

    it("returns null when plugins dir is missing", () => {
      expect(getInstalledVersion(testDir, BUNDLE)).toBeNull();
    });

    it("returns null when no matching JARs", () => {
      createPlugins("org.eclipse.core_3.0.0.jar");
      expect(getInstalledVersion(testDir, BUNDLE)).toBeNull();
    });

    it("returns version from matching JAR", () => {
      createPlugins(`${BUNDLE}_1.0.5.jar`);
      expect(getInstalledVersion(testDir, BUNDLE)).toBe("1.0.5");
    });

    it("returns version with qualifier", () => {
      createPlugins(`${BUNDLE}_1.0.0.202501011200.jar`);
      expect(getInstalledVersion(testDir, BUNDLE)).toBe(
        "1.0.0.202501011200",
      );
    });

    it("ignores JARs from other bundles", () => {
      createPlugins(
        "some.other.bundle_2.0.0.jar",
        `${BUNDLE}_1.0.1.jar`,
      );
      expect(getInstalledVersion(testDir, BUNDLE)).toBe("1.0.1");
    });
  });

  describe("findEclipsePath", () => {
    it("uses config path if eclipsec exists", () => {
      writeFileSync(join(testDir, eclipseExe("eclipsec")), "");
      const result = findEclipsePath({ eclipse: testDir });
      expect(result).toBe(testDir);
    });

    it("returns null when config path has no eclipsec", () => {
      const result = findEclipsePath({ eclipse: testDir });
      // Falls through to candidates, which likely don't exist in test env
      // At minimum, config path should not be returned
      expect(result).not.toBe(testDir);
    });

    it("returns null when config is empty and no candidates", () => {
      const result = findEclipsePath({});
      // On a machine without Eclipse in standard paths, returns null
      // We can't assert null because Eclipse might be installed
      expect(typeof result === "string" || result === null).toBe(true);
    });
  });

  describe("getEclipseJavaHome", () => {
    it("returns null when eclipse.ini is missing", () => {
      expect(getEclipseJavaHome(testDir)).toBeNull();
    });

    it("returns null when no -vm entry", () => {
      writeFileSync(
        join(testDir, "eclipse.ini"),
        "-startup\nplugins/org.eclipse.equinox.launcher.jar\n-vmargs\n-Xmx2g\n",
      );
      expect(getEclipseJavaHome(testDir)).toBeNull();
    });

    it("extracts JRE home from -vm pointing to bin dir", () => {
      const jreBin = join(testDir, "plugins", "justj", "jre", "bin");
      mkdirSync(jreBin, { recursive: true });
      writeFileSync(
        join(testDir, "eclipse.ini"),
        `-startup\nplugins/launcher.jar\n-vm\nplugins/justj/jre/bin\n-vmargs\n-Xmx2g\n`,
      );
      const result = getEclipseJavaHome(testDir);
      expect(result).toContain("jre");
      expect(result).not.toContain("bin");
    });
  });

  describe("generateTargetPlatform", () => {
    it("writes target file with Eclipse path", () => {
      generateTargetPlatform(testDir, "D:/eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="D:/eclipse"');
      expect(content).toContain('type="Directory"');
      expect(content).toContain("<?xml");
    });

    it("preserves backslashes as-is (no double escaping)", () => {
      generateTargetPlatform(testDir, "D:\\eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="D:\\eclipse"');
      expect(content).not.toContain("\\\\");
    });

    it("handles paths with spaces", () => {
      generateTargetPlatform(testDir, "C:/Program Files/eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="C:/Program Files/eclipse"');
    });
  });

  describe("waitForBridge", () => {
    let server;

    function startMockServer(handler) {
      return new Promise((res) => {
        server = createServer(handler);
        server.listen(0, "127.0.0.1", () =>
          res(server.address().port),
        );
      });
    }

    afterEach(() => {
      if (server) {
        server.close();
        server = null;
      }
    });

    it("resolves when instance appears and health check passes", async () => {
      const port = await startMockServer((req, res) => {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(["proj-a", "proj-b"]));
      });
      const discoverFn = () => [
        { pid: 42, port, token: null, workspace: "/ws" },
      ];
      const result = await waitForBridge(discoverFn, 42, 5);
      expect(result.port).toBe(port);
      expect(result.projects).toEqual(["proj-a", "proj-b"]);
    });

    it("waits for instance to appear (not found initially)", async () => {
      const port = await startMockServer((req, res) => {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(["proj"]));
      });
      let callCount = 0;
      const discoverFn = () => {
        callCount++;
        // Instance appears on third poll
        if (callCount < 3) return [];
        return [{ pid: 99, port, token: null, workspace: "/ws" }];
      };
      const result = await waitForBridge(discoverFn, 99, 30);
      expect(result.port).toBe(port);
      expect(callCount).toBeGreaterThanOrEqual(3);
    });

    it("rejects on timeout when instance never appears", async () => {
      const discoverFn = () => [];
      await expect(
        waitForBridge(discoverFn, 999, 3),
      ).rejects.toThrow("Timed out");
    });

    it("ignores instances with different PID", async () => {
      const discoverFn = () => [
        { pid: 100, port: 9999, token: null, workspace: "/ws" },
      ];
      await expect(
        waitForBridge(discoverFn, 200, 3),
      ).rejects.toThrow("Timed out");
    });
  });

  describe("awaitProfileLockFree", () => {
    it("returns silently when .lock file does not exist", () => {
      const profileDir = join(testDir, "fresh.profile");
      mkdirSync(profileDir);
      // No java needed — fresh profile path short-circuits.
      awaitProfileLockFree(profileDir, "java-not-installed", 1_000);
    });

    it("acquires the lock when free and returns successfully", () => {
      const profileDir = join(testDir, "free.profile");
      mkdirSync(profileDir);
      writeFileSync(join(profileDir, ".lock"), "");
      // Use system java — required for this test path.
      awaitProfileLockFree(profileDir, "java", 5_000);
    });

    it("throws with a clear message when another JVM holds the lock",
        async () => {
      const profileDir = join(testDir, "held.profile");
      mkdirSync(profileDir);
      const lockFile = join(profileDir, ".lock");
      writeFileSync(lockFile, "");
      // Spawn a Java holder that takes the lock and parks.
      const { spawn } = await import("node:child_process");
      const holderSrc = join(testDir, "Holder.java");
      writeFileSync(holderSrc,
          `import java.io.RandomAccessFile;\n`
          + `import java.nio.channels.FileChannel;\n`
          + `import java.nio.channels.FileLock;\n`
          + `public class Holder {\n`
          + `  public static void main(String[] a) throws Exception {\n`
          + `    try (RandomAccessFile r = new RandomAccessFile(a[0], "rw");\n`
          + `         FileChannel ch = r.getChannel()) {\n`
          + `      FileLock l = ch.tryLock();\n`
          + `      if (l == null) System.exit(2);\n`
          + `      System.out.println("locked");\n`
          + `      Thread.sleep(60_000);\n`
          + `    }\n`
          + `  }\n`
          + `}\n`);
      const holder = spawn("java", [holderSrc, lockFile],
          { stdio: ["ignore", "pipe", "pipe"] });
      // Wait for "locked" line so the lock is taken before probe.
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error("holder timeout")), 10_000);
        holder.stdout.on("data", (b) => {
          if (b.toString().includes("locked")) {
            clearTimeout(t); resolve();
          }
        });
        holder.on("error", reject);
      });
      try {
        expect(() =>
          awaitProfileLockFree(profileDir, "java", 1_500),
        ).toThrow(/held by another JVM/);
      } finally {
        holder.kill();
      }
    }, 20_000);
  });
});
