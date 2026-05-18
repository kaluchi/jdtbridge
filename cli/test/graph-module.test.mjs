import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword, makeTagKeyword } from "@kaluchi/qlang-core";

// End-to-end coverage for the :jdt/graph qlang module. Loads
// graph.qlang with its conduits (@asNode, @detail, @sourceCard,
// @callers etc.) and mocks the plugin client so dispatches can
// be verified by the HTTP paths they emit.

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
  vi.doMock("../src/client.mjs", () => ({ get: mockedGet }));
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

describe("@asNode routing on String subjects", () => {
  it("routes 'pkg.Type' (no #) to @type", async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return { fqn: "pkg.Foo", kind: "type", origin: "source" };
    });
    await session.evalCell(
        'use(:jdt/graph) | "pkg.Foo" | @asNode');
    expect(calls).toEqual(["/type?of=pkg.Foo"]);
  });

  it("routes 'pkg.Type#method(Arg)' (# with '(') to @method",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return { fqn: "pkg.Foo#bar(java.lang.String)",
               kind: "method", origin: "source" };
    });
    await session.evalCell(
        'use(:jdt/graph) '
      + '| "pkg.Foo#bar(java.lang.String)" | @asNode');
    expect(calls).toEqual([
      "/method?of=pkg.Foo%23bar(java.lang.String)"]);
  });

  it("routes 'pkg.Type#name' (# without '(') to @field",
        async () => {
    // Bare `Type#name` is syntactically ambiguous between a
    // field reference and a method bare-name; the syntactic
    // dispatcher sends it to @field. Callers who mean a method
    // must write the signature form `Type#name(Arg…)`.
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      return { fqn: "pkg.Foo#count",
               kind: "field", origin: "source" };
    });
    await session.evalCell(
        'use(:jdt/graph) | "pkg.Foo#count" | @asNode');
    expect(calls).toEqual(["/field?of=pkg.Foo%23count"]);
  });

  it("passes Map subjects through unchanged (no HTTP)",
        async () => {
    const calls = [];
    const session = await loadSession(async (path) => {
      calls.push(path);
      throw new Error("should not be called");
    });
    const { result, error } = await session.evalCell(
        'use(:jdt/graph) '
      + '| {:fqn "pkg.Foo" :kind "type" :origin "source"} | @asNode');
    expect(error).toBeNull();
    expect(calls).toEqual([]);
    expect(result).toBeInstanceOf(Map);
    expect(result.get("fqn")).toBe("pkg.Foo");
  });
});

describe(":jdt/graph descriptor consistency", () => {
  // qlang 0.7 surface: `:name | spec` returns the env-side
  // declaration descriptor — for `::builtin` catalog entries it
  // carries `:kind ::builtin :impl <fn> :category … :subject …
  // :modifiers … :returns … :throws … :captured [min max]
  // :effectful <bool>`. For a `:conduit` BindStep it carries the
  // Conduit value-class Map with `:kind ::conduit :name … :params
  // [...] :source <body-source>`. Map keys are plain Strings;
  // discriminator values are TagKeyword for `:kind` and Keyword
  // for `:category` / `:returns` / individual `:modifiers`.
  async function spec(name) {
    const session = await loadSession(async () => null);
    const { result, error } = await session.evalCell(
        `use(:jdt/graph) | :${name} | spec`);
    if (error) throw error;
    return result;
  }

  function field(desc, keyName) {
    return desc.get(keyName);
  }

  it("@source :returns :string (raw text, not a Map bundle)",
        async () => {
    const d = await spec("@source");
    expect(field(d, "returns")).toEqual(keyword("string"));
  });

  it("@source :modifiers is empty",
        async () => {
    const d = await spec("@source");
    expect(field(d, "modifiers")).toEqual([]);
  });

  it("@types :modifiers is empty (impl is nullary)",
        async () => {
    const d = await spec("@types");
    // Previously advertised [:keyword] implying an unsupported
    // :sourceOnly modifier; impl is nullaryOp.
    expect(field(d, "modifiers")).toEqual([]);
  });

  it("@types :returns :vec",
        async () => {
    const d = await spec("@types");
    expect(field(d, "returns")).toEqual(keyword("vec"));
  });

  it("@incomingRefs :modifiers carries :keyword "
      + "(optional refKind)", async () => {
    const d = await spec("@incomingRefs");
    expect(field(d, "modifiers"))
        .toEqual([keyword("keyword")]);
  });

  it("@outgoingRefs :modifiers is empty",
        async () => {
    const d = await spec("@outgoingRefs");
    expect(field(d, "modifiers")).toEqual([]);
  });

  it("@problems is a conduit (no :modifiers, no captured)",
      async () => {
    // @problems was rebuilt as a polymorphic qlang conduit over
    // the @problemMarkers primitive and existing navigation
    // axes. The captured-arg (:scope) modifier is gone.
    const d = await spec("@problems");
    expect(field(d, "kind")).toEqual(makeTagKeyword("conduit"));
    expect(field(d, "params")).toEqual([]);
  });

  it("@problemMarkers primitive carries no modifiers", async () => {
    const d = await spec("@problemMarkers");
    expect(field(d, "kind")).toEqual(makeTagKeyword("builtin"));
    expect(field(d, "modifiers")).toEqual([]);
  });

  it("@type / @method / @field — nullary modifiers, :map return",
        async () => {
    for (const name of ["@type", "@method", "@field"]) {
      const d = await spec(name);
      expect(field(d, "modifiers"))
          .toEqual([], name + " modifiers");
      expect(field(d, "returns"))
          .toEqual(keyword("map"), name + " returns");
    }
  });
});
