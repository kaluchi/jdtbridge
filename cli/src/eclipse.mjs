// Eclipse management — discovery, lifecycle, p2 operations.

import { execSync, spawn } from "node:child_process";
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { request } from "node:http";

const IS_WIN = process.platform === "win32";

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** Return platform-specific executable name. */
export function eclipseExe(name) {
  return IS_WIN ? name + ".exe" : name;
}

/** Check if any Eclipse process is running. */
export function isEclipseRunning() {
  try {
    if (IS_WIN) {
      const out = execSync("tasklist", {
        encoding: "utf8",
        stdio: ["pipe", "pipe", "pipe"],
      });
      return out.toLowerCase().includes("eclipse.exe");
    }
    execSync("pgrep -f eclipse", { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

/**
 * Find Eclipse installation directory.
 * Checks config first, then well-known locations.
 * Accepts either a traditional install with eclipsec(.exe) or any
 * Eclipse-based product that has a .eclipseproduct marker file
 * (e.g. Spring Tools, STS).
 */
export function isEclipseInstall(dir) {
  if (!dir) return false;
  if (existsSync(join(dir, eclipseExe("eclipsec")))) return true;
  if (existsSync(join(dir, ".eclipseproduct"))) return true;
  return false;
}

export function findEclipsePath(config) {
  if (config.eclipse && isEclipseInstall(config.eclipse)) {
    return config.eclipse;
  }
  const candidates = IS_WIN
    ? ["D:/eclipse", "C:/eclipse"]
    : [
        "/usr/local/eclipse",
        "/opt/eclipse",
        `${process.env.HOME}/eclipse`,
      ];
  for (const p of candidates) {
    if (isEclipseInstall(p)) return p;
  }
  return null;
}

/** Read Eclipse version from .eclipseproduct file. */
export function getEclipseVersion(eclipsePath) {
  const f = join(eclipsePath, ".eclipseproduct");
  if (!existsSync(f)) return null;
  const m = readFileSync(f, "utf8").match(/version=(\S+)/);
  return m ? m[1] : null;
}

/**
 * Extract JAVA_HOME from eclipse.ini (-vm entry).
 * Returns absolute path to JDK/JRE home, or null if not found.
 */
export function getEclipseJavaHome(eclipsePath) {
  const iniFile = join(eclipsePath, "eclipse.ini");
  if (!existsSync(iniFile)) return null;
  const lines = readFileSync(iniFile, "utf8").split(/\r?\n/);
  const vmIndex = lines.indexOf("-vm");
  if (vmIndex === -1 || vmIndex + 1 >= lines.length) return null;
  const vmPath = lines[vmIndex + 1].trim();
  // -vm points to a bin/ dir or a javaw.exe — resolve to JRE/JDK home
  const resolved = resolve(eclipsePath, vmPath);
  // e.g. plugins/.../jre/bin → go up to jre (or jdk root)
  if (resolved.endsWith("bin") || resolved.endsWith("bin/") || resolved.endsWith("bin\\")) {
    const parent = dirname(resolved);
    // If parent is "jre", go one more level up for JDK home
    return parent;
  }
  // If it points to an executable, go up two levels (bin/java.exe → jre)
  return dirname(dirname(resolved));
}

/** Detect the p2 profile name from the profile registry. */
export function detectProfile(eclipsePath) {
  const regDir = join(
    eclipsePath,
    "p2",
    "org.eclipse.equinox.p2.engine",
    "profileRegistry",
  );
  if (!existsSync(regDir)) return null;
  const dirs = readdirSync(regDir).filter((d) => d.endsWith(".profile"));
  if (dirs.length === 0) return null;
  const epp = dirs.find((d) => d.startsWith("epp.package."));
  return (epp || dirs[0]).replace(".profile", "");
}

/** Find the installed version of a bundle in Eclipse plugins dir. */
export function getInstalledVersion(eclipsePath, bundleId) {
  const pluginsDir = join(eclipsePath, "plugins");
  if (!existsSync(pluginsDir)) return null;
  const jars = readdirSync(pluginsDir).filter(
    (f) => f.startsWith(bundleId + "_") && f.endsWith(".jar"),
  );
  if (jars.length === 0) return null;
  const m = jars[jars.length - 1].match(/_(.+)\.jar$/);
  return m ? m[1] : "unknown";
}

/**
 * Stop Eclipse gracefully, then force-kill if needed.
 * Returns true if Eclipse is stopped, false if it could not be stopped.
 */
export function stopEclipse() {
  if (!isEclipseRunning()) return true;
  try {
    if (IS_WIN) {
      execSync("taskkill /IM eclipse.exe", { stdio: "ignore" });
    } else {
      execSync("pkill -f eclipse", { stdio: "ignore" });
    }
  } catch {
    /* ignore */
  }
  for (let i = 0; i < 60; i++) {
    sleep(500);
    if (!isEclipseRunning()) return true;
  }
  try {
    if (IS_WIN) {
      execSync("taskkill /F /IM eclipse.exe", { stdio: "ignore" });
    } else {
      execSync("pkill -9 -f eclipse", { stdio: "ignore" });
    }
    sleep(2000);
  } catch {
    /* ignore */
  }
  return !isEclipseRunning();
}

/**
 * Start Eclipse as a detached process.
 * @returns {number} PID of the launched process
 */
export function startEclipse(eclipsePath, workspace) {
  const exe = join(eclipsePath, eclipseExe("eclipse"));
  const args = workspace ? ["-data", workspace] : [];
  const child = spawn(exe, args, {
    detached: true,
    stdio: "ignore",
    windowsHide: false,
  });
  child.unref();
  return child.pid;
}

/** Generate jdtbridge.target pointing to the Eclipse installation. */
export function generateTargetPlatform(repoRoot, eclipsePath) {
  const targetFile = join(repoRoot, "jdtbridge.target");
  const content = `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?>
<target name="eclipse-local" sequenceNumber="1">
    <locations>
        <location path="${eclipsePath}" type="Directory"/>
    </locations>
</target>
`;
  writeFileSync(targetFile, content);
}

function findLauncherJar(eclipsePath) {
  const pluginsDir = join(eclipsePath, "plugins");
  if (!existsSync(pluginsDir)) return null;
  const jars = readdirSync(pluginsDir).filter((f) =>
    f.startsWith("org.eclipse.equinox.launcher_") && f.endsWith(".jar"),
  );
  if (jars.length === 0) return null;
  jars.sort();
  return join(pluginsDir, jars[jars.length - 1]);
}

/** Run the p2 director application (headless Eclipse). */
export function runDirector(eclipsePath, profile, extraArgs) {
  const eclipsecPath = join(eclipsePath, eclipseExe("eclipsec"));

  let cmd;
  let prefixArgs = [];

  if (existsSync(eclipsecPath)) {
    // Classic Eclipse launcher
    cmd = `"${eclipsecPath}"`;
  } else {
    // Fallback: run via Equinox launcher JAR (works for Eclipse-based products
    // such as Spring Tools that ship a branded launcher instead of eclipsec).
    const launcherJar = findLauncherJar(eclipsePath);
    if (!launcherJar) {
      throw new Error(
        "Cannot find eclipsec(.exe) or org.eclipse.equinox.launcher_*.jar in Eclipse installation.",
      );
    }
    cmd = "java";
    prefixArgs = ["-jar", `"${launcherJar}"`];
  }

  const args = [
    cmd,
    ...prefixArgs,
    "-nosplash",
    "-application",
    "org.eclipse.equinox.p2.director",
    "-profile",
    profile,
    "-destination",
    `"${eclipsePath}"`,
    ...extraArgs,
  ].join(" ");

  try {
    return execSync(args + " 2>&1", {
      encoding: "utf8",
      timeout: 180_000,
    });
  } catch (e) {
    const output = e.stdout || e.stderr || e.message;
    const lines = output
      .split("\n")
      .filter(
        (l) =>
          !l.includes("DEBUG") &&
          !l.includes("INFO:") &&
          !l.includes("spifly") &&
          l.trim(),
      );
    throw new Error(lines.join("\n"));
  }
}

/** Install a feature via p2 director from a local repository. */
export function p2Install(eclipsePath, profile, repoPath, featureIU) {
  const repoUrl = `file:///${repoPath.replace(/\\/g, "/")}`;
  return runDirector(eclipsePath, profile, [
    "-repository",
    `"${repoUrl}"`,
    "-installIU",
    featureIU,
  ]);
}

/** Uninstall a feature via p2 director. */
export function p2Uninstall(eclipsePath, profile, featureIU) {
  return runDirector(eclipsePath, profile, ["-uninstallIU", featureIU]);
}

/**
 * Wait for a bridge instance file with the given PID to appear,
 * then hit /projects as a health check.
 * @param {Function} discoverFn - discoverInstances function
 * @param {number} pid - PID of the Eclipse process to wait for
 * @param {number} [timeoutSec=120] - max seconds to wait
 * @returns {Promise<{port: number, projects: string[]}>} port and project list
 */
export function waitForBridge(discoverFn, pid, timeoutSec = 120) {
  const deadline = Date.now() + timeoutSec * 1000;

  return new Promise((resolve, reject) => {
    async function poll() {
      if (Date.now() > deadline) {
        reject(new Error("Timed out waiting for bridge"));
        return;
      }
      const instances = await discoverFn();
      const inst = instances.find((i) => i.pid === pid);
      if (!inst) {
        setTimeout(poll, 2000);
        return;
      }
      // Instance found — health check via /projects
      healthCheck(inst.port, inst.token)
        .then((projects) => resolve({ port: inst.port, projects }))
        .catch(() => setTimeout(poll, 2000));
    }
    poll();
  });
}

function healthCheck(port, token) {
  return new Promise((resolve, reject) => {
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const req = request(
      { hostname: "127.0.0.1", port, path: "/projects", timeout: 5000, headers },
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          if (res.statusCode !== 200) {
            reject(new Error(`HTTP ${res.statusCode}`));
            return;
          }
          try { resolve(JSON.parse(data)); }
          catch { reject(new Error("Invalid JSON")); }
        });
      },
    );
    req.on("timeout", () => { req.destroy(); reject(new Error("timeout")); });
    req.on("error", reject);
    req.end();
  });
}
