// End-to-end coverage for the :jdt/graph @problems conduit.
//
// Two test modes are used, each probing a different concern:
//
// 1. **HTTP-path mode** — mock `../src/client.mjs` `get`, evaluate
//    `use(:jdt/graph) | <expr> | @problems` expressions against the
//    real @problemMarkers primitive. Verifies the URL path the
//    primitive emits for every subject shape / kind.
//
// 2. **Dependency-injection mode** — call `@problemsVia` directly
//    with mocked-out fetcher / liftNode / fileLocation /
//    packageTypes conduits. Exercises the conduit's dispatch
//    branches in isolation, without hitting any axis impl.
//    Confirms the higher-order plumbing (fetcher et al. are pure
//    params) composes correctly.
//
// Homogeneity with @source is tested implicitly — top-level type
// subjects produce a single compilation-unit-scope URL call with
// no range filter, matching the @source "Top-level types return
// the full compilation unit" docstring.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword } from "@kaluchi/qlang-core";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, "..", "lib");

function createLocator(graphImpls) {
  return (namespaceName) => {
    const qlangPath = join(
        MODULE_LIB, ...namespaceName.split("/")) + ".qlang";
    const source = readFileSync(qlangPath, "utf8");
    const impls = namespaceName === "jdt/graph"
        ? graphImpls : undefined;
    return { source, impls };
  };
}

async function loadSession(mockedGet) {
  process.env.JDT_GRAPH_CACHE = "0";
  vi.resetModules();
  // client.mjs is imported by both graph.impl.mjs (get) and
  // path-translate.mjs (currentInstance). currentInstance is
  // stubbed to null so translateHostPathFromLocal is a no-op —
  // tests run as a local instance, paths pass through unchanged.
  vi.doMock("../src/client.mjs", () => ({
    get: mockedGet,
    currentInstance: () => null,
    BridgeNotRunningError: class BridgeNotRunningError extends Error {},
    isConnectionError: () => false,
  }));
  const graphImpl = await import(
      "../lib/jdt/graph.impl.mjs?graphmod=" + Date.now());
  const { createSession } = await import(
      "@kaluchi/qlang-core/session");
  const session = await createSession({
      locator: createLocator(graphImpl.createImpls()) });
  return session;
}

afterEach(() => {
  delete process.env.JDT_GRAPH_CACHE;
  vi.resetModules();
  vi.doUnmock("../src/client.mjs");
});

// ────────────────────────────────────────────────────────────────
// HTTP-path mode: verifies primitive @problemMarkers URL shape.
// ────────────────────────────────────────────────────────────────

describe("@problems → @problemMarkers URL dispatch", () => {
  it("bare @problems → /problems (workspace, no of)", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell('use(:jdt/graph) | @problems');
    expect(calls).toEqual(["/problems"]);
  });

  it("String project fqn → /problems?of=<fqn>", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) | "my-project" | @problems');
    expect(calls).toEqual(["/problems?of=my-project"]);
  });

  it("String file path (Windows drive) → /problems?of=<path>",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) | "D:/git/proj/Foo.java" | @problems');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fgit%2Fproj%2FFoo.java"]);
  });

  it("String file path (unix absolute) → /problems?of=<path>",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) | "/home/u/Foo.java" | @problems');
    expect(calls).toEqual(["/problems?of=%2Fhome%2Fu%2FFoo.java"]);
  });

  it("Map :kind \"project\" → primitive uses :fqn", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "project" :fqn "my-app"} | @problems');
    expect(calls).toEqual(["/problems?of=my-app"]);
  });

  it("Map :kind \"file\" → primitive uses :fqn as path", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "file" :fqn "D:/proj/X.java"} | @problems');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fproj%2FX.java"]);
  });

  it("Top-level type node → /problems?of=<location/file> (no range)",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    // :declaringType = null marks top-level; conduit should not
    // apply range filter → primitive is called with file path only.
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "pkg.Top" '
      +    ':declaringType null '
      +    ':location {:file "D:/proj/Top.java" '
      +               ':startLine 10 :endLine 99}} | @problems');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fproj%2FTop.java"]);
  });

  it("Inner type node → /problems?of=<file> + pipeline range filter",
        async () => {
    // Server returns 3 markers at lines 5 / 25 / 120 — pipeline-side
    // filter should keep only line 25 (within the inner type's
    // [20, 50] range). The primitive URL is the file only; filtering
    // happens after the fetch on the CLI side.
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [
        { kind: "problem", location: { file: "D:/proj/Outer.java",
            startLine: 5,   endLine: 5 } },
        { kind: "problem", location: { file: "D:/proj/Outer.java",
            startLine: 25,  endLine: 25 } },
        { kind: "problem", location: { file: "D:/proj/Outer.java",
            startLine: 120, endLine: 120 } },
      ];
    });
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "pkg.Outer.Inner" '
      +    ':declaringType "pkg.Outer" '
      +    ':location {:file "D:/proj/Outer.java" '
      +               ':startLine 20 :endLine 50}} '
      + '| @problems | count');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fproj%2FOuter.java"]);
    expect(res.result).toBe(1);
  });

  it("Method node → /problems?of=<file> + range filter",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [
        { kind: "problem", location: { file: "D:/proj/Foo.java",
            startLine: 3,  endLine: 3 } },
        { kind: "problem", location: { file: "D:/proj/Foo.java",
            startLine: 42, endLine: 42 } },
      ];
    });
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "method" :fqn "pkg.Foo#bar()" '
      +    ':location {:file "D:/proj/Foo.java" '
      +               ':startLine 40 :endLine 50}} '
      + '| @problems | count');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fproj%2FFoo.java"]);
    expect(res.result).toBe(1);
  });

  it("Field node → /problems?of=<file> + range filter", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [
        { kind: "problem", location: { file: "D:/proj/Foo.java",
            startLine: 3, endLine: 3 } },
        { kind: "problem", location: { file: "D:/proj/Foo.java",
            startLine: 7, endLine: 7 } },
      ];
    });
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "field" :fqn "pkg.Foo#count" '
      +    ':location {:file "D:/proj/Foo.java" '
      +               ':startLine 7 :endLine 7}} '
      + '| @problems | count');
    expect(calls).toEqual(
        ["/problems?of=D%3A%2Fproj%2FFoo.java"]);
    expect(res.result).toBe(1);
  });

  it("Package node → @typesInPackage + @containingFile + fetcher",
        async () => {
    // Emitted path sequence mirrors the fan-out:
    //   /typesInPackage?of=pkg      → 2 types
    //   /file?of=<typeN.location>   → 2 file nodes (same file →
    //                                  distinct keeps 1)
    //   /problems?of=<file>         → 1 primitive call
    // The test asserts distinct happened (1 fetcher call, not 2)
    // and that the URL set is exactly the above.
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      if (path === "/typesInPackage?of=pkg") {
        return [
          { kind: "type", fqn: "pkg.Foo",
              location: { file: "D:/pkg/Foo.java" } },
          { kind: "type", fqn: "pkg.Bar",
              location: { file: "D:/pkg/Foo.java" } },
        ];
      }
      if (path.startsWith("/file?of=")) {
        return { kind: "file",
            fqn: decodeURIComponent(path.split("=")[1]) };
      }
      if (path.startsWith("/problems?of=")) {
        return [];
      }
      throw new Error("unexpected path: " + path);
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "package" :fqn "pkg"} | @problems');
    // 1 /typesInPackage, 2 /file (one per type), 1 /problems
    // (after distinct by file node equality).
    expect(calls).toContain("/typesInPackage?of=pkg");
    expect(calls.filter(
        (p) => p.startsWith("/file?of=")).length).toBe(2);
    expect(calls.filter(
        (p) => p.startsWith("/problems?of=")).length).toBe(1);
  });

  it("Unsupported Map :kind → fail-track error descriptor",
        async () => {
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "reference"} | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    expect(d.get(keyword("kind"))).toBe(keyword("unsupported-scope-kind"));
  });

  it("Backslash-only path (no forward slash) → file branch",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) | "D:\\\\proj\\\\Foo.java" | @problems');
    // Verify it was routed to file scope (the URL carries the
    // backslash-escaped path verbatim — primitive doesn't canonise).
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(/^\/problems\?of=/);
  });

  it("Relative path with slash → treated as file-shape (path-sep "
        + "triggers file branch even without drive letter)",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) | "src/main/Foo.java" | @problems');
    expect(calls).toEqual(
        ["/problems?of=src%2Fmain%2FFoo.java"]);
  });

  it("Empty String subject → /problems?of= (server replies "
        + "ProjectNotFound; conduit passes through)", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return { _error: { kind: "project-not-found",
          thrown: "ProjectNotFound",
          origin: "jdt/plugin",
          message: "Project not found: ",
          context: { project: "" } } };
    });
    const res = await session.evalCell(
        'use(:jdt/graph) | "" | @problems');
    expect(calls).toEqual(["/problems?of="]);
    // Fail-track error is propagated as an error value.
    expect(res.result?.descriptor).toBeDefined();
  });

  it("Pipeline filter composes after @problems (severity)",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [
        { kind: "problem", severity: "error",
            location: { file: "X.java", startLine: 1, endLine: 1 } },
        { kind: "problem", severity: "warning",
            location: { file: "X.java", startLine: 2, endLine: 2 } },
        { kind: "problem", severity: "error",
            location: { file: "X.java", startLine: 3, endLine: 3 } },
      ];
    });
    const res = await session.evalCell(
        'use(:jdt/graph) | @problems '
      + '| filter(/severity | eq("error")) | count');
    expect(calls).toEqual(["/problems"]);
    expect(res.result).toBe(2);
  });

  it("Distribution via * — @projects * @problems fans out "
        + "to one primitive call per project", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      if (path === "/projects") {
        return [
          { kind: "project", fqn: "alpha" },
          { kind: "project", fqn: "beta" },
          { kind: "project", fqn: "gamma" },
        ];
      }
      return [{ kind: "problem", severity: "error",
          location: { file: "x", startLine: 1, endLine: 1 } }];
    });
    const res = await session.evalCell(
        'use(:jdt/graph) | @projects * @problems | flat | count');
    expect(calls[0]).toBe("/projects");
    expect(calls.filter((p) => p.startsWith("/problems?of="))
        .sort()).toEqual([
      "/problems?of=alpha",
      "/problems?of=beta",
      "/problems?of=gamma",
    ]);
    // 3 projects × 1 marker each → 3 total after flat.
    expect(res.result).toBe(3);
  });

  it("Inner type boundary — marker on startLine inclusive",
        async () => {
    const session = await loadSession(async () => [
      { kind: "problem",
          location: { file: "F.java", startLine: 20, endLine: 20 } },
    ]);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "O.I" :declaringType "O" '
      +    ':location {:file "F.java" '
      +               ':startLine 20 :endLine 50}} '
      + '| @problems | count');
    // startLine == node.startLine → included by gte (inclusive).
    expect(res.result).toBe(1);
  });

  it("Inner type boundary — marker on endLine inclusive",
        async () => {
    const session = await loadSession(async () => [
      { kind: "problem",
          location: { file: "F.java", startLine: 50, endLine: 50 } },
    ]);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "O.I" :declaringType "O" '
      +    ':location {:file "F.java" '
      +               ':startLine 20 :endLine 50}} '
      + '| @problems | count');
    // endLine == node.endLine → included by lte (inclusive).
    expect(res.result).toBe(1);
  });

  it("Inner type boundary — marker one line above excluded",
        async () => {
    const session = await loadSession(async () => [
      { kind: "problem",
          location: { file: "F.java", startLine: 19, endLine: 19 } },
    ]);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "O.I" :declaringType "O" '
      +    ':location {:file "F.java" '
      +               ':startLine 20 :endLine 50}} '
      + '| @problems | count');
    expect(res.result).toBe(0);
  });

  it("Inner type boundary — marker one line below excluded",
        async () => {
    const session = await loadSession(async () => [
      { kind: "problem",
          location: { file: "F.java", startLine: 51, endLine: 51 } },
    ]);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "O.I" :declaringType "O" '
      +    ':location {:file "F.java" '
      +               ':startLine 20 :endLine 50}} '
      + '| @problems | count');
    expect(res.result).toBe(0);
  });

  it("Empty response from primitive → empty Vec result",
        async () => {
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) | @problems | count');
    expect(res.result).toBe(0);
  });

  it("Fail-track from primitive (error descriptor) propagates",
        async () => {
    const session = await loadSession(async () => ({
      _error: { kind: "jdt-internal-error",
          thrown: "CoreException",
          origin: "jdt/plugin",
          message: "workspace in bad state" }
    }));
    const res = await session.evalCell(
        'use(:jdt/graph) | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    // Server-originated descriptors carry their :kind as a raw
    // String (jsonToQlang keeps JSON strings as strings). Only
    // the outer _error wrapper triggers lifting via
    // liftServerResponse → makeErrorValue.
    expect(d.get(keyword("kind"))).toBe("jdt-internal-error");
  });

  it("Vec subject through `|` → missing-subject (not distributed)",
        async () => {
    // Vec subjects require `*` distribution explicitly. A bare
    // `[a b] | @problems` pipes the Vec as a single subject — the
    // conduit sees a Vec (isString=false, isMap=false, isNull=false)
    // → trailing-default error branch fires.
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) | [1 2 3] | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    expect(d.get(keyword("kind")))
        .toBe(keyword("unsupported-subject-type"));
  });

  it("Number subject → missing-subject-type error", async () => {
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) | 42 | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    expect(d.get(keyword("kind")))
        .toBe(keyword("unsupported-subject-type"));
  });

  it("Boolean subject → missing-subject-type error", async () => {
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) | true | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    expect(d.get(keyword("kind")))
        .toBe(keyword("unsupported-subject-type"));
  });

  it("Keyword subject → missing-subject-type error", async () => {
    const session = await loadSession(async () => []);
    const res = await session.evalCell(
        'use(:jdt/graph) | :foo | @problems');
    expect(res.result?.descriptor).toBeDefined();
    const d = res.result.descriptor;
    expect(d.get(keyword("kind")))
        .toBe(keyword("unsupported-subject-type"));
  });

  it("Nested inner (inner-of-inner) type → same range-filter "
        + "behaviour (declaringType not null)", async () => {
    const session = await loadSession(async () => [
      { kind: "problem",
          location: { file: "F.java", startLine: 100, endLine: 100 } },
      { kind: "problem",
          location: { file: "F.java", startLine: 1, endLine: 1 } },
    ]);
    const res = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "type" :fqn "O.I1.I2" :declaringType "O.I1" '
      +    ':location {:file "F.java" '
      +               ':startLine 95 :endLine 120}} '
      + '| @problems | count');
    // Only line 100 in [95, 120] — nested is still "inner".
    expect(res.result).toBe(1);
  });

  it("Project :fqn with dots → passed through unchanged (not a path)",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "project" :fqn "io.github.kaluchi.jdtbridge"} '
      + '| @problems');
    expect(calls).toEqual(
        ["/problems?of=io.github.kaluchi.jdtbridge"]);
  });

  it("File node with backslash-only fqn → primitive URL-encodes "
        + "path verbatim", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return [];
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| {:kind "file" :fqn "D:\\\\proj\\\\X.java"} | @problems');
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(
        /^\/problems\?of=D%3A(%5C|%2F)+proj/);
  });
});

// ────────────────────────────────────────────────────────────────
// Dependency-injection mode: @problemsVia with qlang-level mocks.
// ────────────────────────────────────────────────────────────────
//
// @problemsVia takes four lambdas — fetcher, liftNode,
// fileLocation, packageTypes. Tests pass pure qlang expressions so
// no axis impl is exercised and no HTTP is emitted.

describe("@problemsVia — pure dispatch with injected mocks", () => {
  it("null subject → fetcher is called with the null", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | @problemsVia(["workspace-stub"],
                     ["lift-unused"],
                     ["file-unused"],
                     ["types-unused"])`);
    expect(res.result).toEqual(["workspace-stub"]);
  });

  it("String non-path → fetcher is invoked with the String",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | "my-project"
      | @problemsVia(as(:s) | [{:scope s}],
                     ["lift-unused"],
                     ["file-unused"],
                     ["types-unused"])`);
    // Fetcher mock captures its input as :scope. Result Vec is
    // [{:scope "my-project"}] — one element.
    expect(res.result).toHaveLength(1);
    expect(res.result[0].get(keyword("scope"))).toBe("my-project");
  });

  it("Map :kind project → fetcher called on node (:fqn read "
        + "by real @problemMarkers; here just the node passes)",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "project" :fqn "app"}
      | @problemsVia(as(:n) | [{:received n | /fqn}],
                     ["lift-unused"],
                     ["file-unused"],
                     ["types-unused"])`);
    expect(res.result).toHaveLength(1);
    expect(res.result[0].get(keyword("received"))).toBe("app");
  });

  it("Map :kind package → packageTypes, fileLocation, fetcher "
        + "chain in order", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "package" :fqn "pkg"}
      | @problemsVia(
          [{:m "marker"}],
          ["lift-unused"],
          {:kind "file" :fqn "F.java"},
          [{:kind "type" :fqn "A"} {:kind "type" :fqn "B"}])
      | count`);
    // packageTypes returns 2 type nodes, fileLocation maps each to
    // the same file node → distinct collapses to 1, fetcher fires
    // once per distinct file → 1 marker total.
    expect(res.result).toBe(1);
  });

  it("Map :kind type top-level → fetcher fires once, "
        + "no range filter", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "type" :fqn "pkg.Foo"
         :declaringType null
         :location {:file "F.java" :startLine 1 :endLine 100}}
      | @problemsVia(
          [{:location {:startLine 5   :endLine 5}}
           {:location {:startLine 999 :endLine 999}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // Top-level: no range filter, both markers pass through.
    expect(res.result).toBe(2);
  });

  it("Map :kind type inner → range filter applied", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "type" :fqn "pkg.Outer.Inner"
         :declaringType "pkg.Outer"
         :location {:file "F.java" :startLine 20 :endLine 40}}
      | @problemsVia(
          [{:location {:startLine 5   :endLine 5}}
           {:location {:startLine 25  :endLine 25}}
           {:location {:startLine 100 :endLine 100}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // Inner: only line 25 falls within [20, 40].
    expect(res.result).toBe(1);
  });

  it("Map :kind method → range filter applied", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "method" :fqn "pkg.Foo#bar()"
         :location {:file "F.java" :startLine 40 :endLine 50}}
      | @problemsVia(
          [{:location {:startLine 3  :endLine 3}}
           {:location {:startLine 45 :endLine 45}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    expect(res.result).toBe(1);
  });

  it("Unsupported Map :kind → structured fail-track error",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "reference"}
      | @problemsVia(["unused"], ["unused"],
                     ["unused"], ["unused"])`);
    const d = res.result?.descriptor;
    expect(d).toBeDefined();
    expect(d.get(keyword("kind"))).toBe(
        keyword("unsupported-scope-kind"));
  });

  it("Field node → range filter narrows to single line",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "field" :fqn "pkg.Foo#count"
         :location {:file "F.java" :startLine 7 :endLine 7}}
      | @problemsVia(
          [{:location {:startLine 6 :endLine 6}}
           {:location {:startLine 7 :endLine 7}}
           {:location {:startLine 8 :endLine 8}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // Only line 7 in [7, 7].
    expect(res.result).toBe(1);
  });

  it("Package scope with empty packageTypes → empty result "
        + "(no fetcher calls)", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "package" :fqn "emptypkg"}
      | @problemsVia(
          [{:should "not be fetched"}],
          ["lift-unused"],
          {:kind "file" :fqn "F.java"},
          [])
      | count`);
    // packageTypes returns empty Vec → nothing to distribute.
    expect(res.result).toBe(0);
  });

  it("Package scope — distinct collapses duplicate files", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "package" :fqn "pkg"}
      | @problemsVia(
          [{:marker "once"}],
          ["lift-unused"],
          {:kind "file" :fqn "Same.java"},
          [{:kind "type" :fqn "A"}
           {:kind "type" :fqn "B"}
           {:kind "type" :fqn "C"}])
      | count`);
    // 3 types, all map to the same {:kind "file" :fqn "Same.java"},
    // distinct → 1 file, fetcher fires once, 1 marker.
    expect(res.result).toBe(1);
  });

  it("Kindless Map (no :kind) → workspace fetcher invocation",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:foo "bar"}
      | @problemsVia(
          [{:m "workspace-stub"}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // :kind absent → conduit treats as workspace, fetcher fires
    // against the kindless Map.
    expect(res.result).toBe(1);
  });

  it("String with '#' → liftNode is invoked before fetcher",
        async () => {
    // liftNode mock returns a method-shaped Map; fetcher then
    // receives the :location/file projection (not the original
    // String). Range filter uses the mock's :location range.
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | "pkg.Foo#bar(String)"
      | @problemsVia(
          [{:location {:startLine 10 :endLine 10}}
           {:location {:startLine 25 :endLine 25}}
           {:location {:startLine 99 :endLine 99}}],
          {:kind "method" :fqn "pkg.Foo#bar(String)"
           :location {:file "F.java"
                      :startLine 20 :endLine 30}},
          ["file-unused"],
          ["types-unused"])
      | count`);
    // Method range [20, 30] retains only line 25.
    expect(res.result).toBe(1);
  });

  it("String without '#' → fetcher invoked directly (no liftNode)",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | "my-project"
      | @problemsVia(
          [{:m "1"} {:m "2"} {:m "3"}],
          ["lift-should-not-be-used"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // No range filter for project-scope; all 3 markers pass.
    expect(res.result).toBe(3);
  });

  it("Null subject → fetcher invoked once", async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | null
      | @problemsVia(
          [{:m "workspace1"} {:m "workspace2"}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    expect(res.result).toBe(2);
  });

  it("Inner type boundary through DI — marker on startLine passes",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "type" :fqn "O.I" :declaringType "O"
         :location {:file "F.java" :startLine 20 :endLine 50}}
      | @problemsVia(
          [{:location {:startLine 20 :endLine 20}}
           {:location {:startLine 50 :endLine 50}}
           {:location {:startLine 19 :endLine 19}}
           {:location {:startLine 51 :endLine 51}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // Lines 20 and 50 inclusive; 19 and 51 excluded.
    expect(res.result).toBe(2);
  });

  it("Top-level type through DI — no range filter, all markers pass",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "type" :fqn "pkg.Top"
         :declaringType null
         :location {:file "F.java" :startLine 10 :endLine 20}}
      | @problemsVia(
          [{:location {:startLine 1   :endLine 1}}
           {:location {:startLine 15  :endLine 15}}
           {:location {:startLine 999 :endLine 999}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])
      | count`);
    // No filter — all three pass.
    expect(res.result).toBe(3);
  });

  it("Fetcher can observe the subject — records :fqn for assertion",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "file" :fqn "D:/proj/X.java"}
      | @problemsVia(
          as(:s) | [{:observed-fqn s | /fqn}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])`);
    expect(res.result).toHaveLength(1);
    expect(res.result[0].get(keyword("observed-fqn")))
        .toBe("D:/proj/X.java");
  });

  it("Method node — fetcher receives the file path, not the node",
        async () => {
    const session = await loadSession(async () => {
      throw new Error("primitive should not hit HTTP");
    });
    const res = await session.evalCell(`
      use(:jdt/graph)
      | {:kind "method" :fqn "pkg.F#m"
         :location {:file "M.java" :startLine 1 :endLine 5}}
      | @problemsVia(
          as(:s) | [{:captured-fetcher-subject s
                     :location {:startLine 2 :endLine 2}}],
          ["lift-unused"],
          ["file-unused"],
          ["types-unused"])`);
    // Fetcher is called with the :location/file string, not the
    // whole method node. Range filter retains the marker because
    // its startLine (2) is within [1, 5].
    expect(res.result).toHaveLength(1);
    expect(res.result[0].get(
        keyword("captured-fetcher-subject"))).toBe("M.java");
  });
});
