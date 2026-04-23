// Loader for the :jdt/graph qlang module under test. Two ways to
// keep tests off real HTTP / Eclipse:
//
//   mockedGet      — async (path) => responseValue. Stubs the
//                    client.mjs get() at the HTTP-path layer; tests
//                    that need to verify URL formation observe
//                    `path` per call.
//
//   implsOverrides — { '@operandName': qlangImpl } merged over
//                    graph.impl.mjs#createImpls(). resolveNamespaceEnv
//                    patches each builtin descriptor's :qlang/impl
//                    with the supplied value, so tests that care
//                    about conduit semantics (not URL paths) can
//                    bypass the HTTP layer entirely.
//
// When neither is supplied, get() throws loud — no test silently
// hits the real bridge.

import { vi } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, "..", "..", "lib");
const GRAPH_QLANG = join(MODULE_LIB, "jdt", "graph.qlang");

export async function loadGraphSession({ implsOverrides = {}, mockedGet } = {}) {
  process.env.JDT_GRAPH_CACHE = "0";
  vi.resetModules();
  vi.doMock("../../src/client.mjs", () => ({
    get: mockedGet ?? (async () => {
      throw new Error("HTTP must not be hit; supply mockedGet or implsOverrides");
    }),
    currentInstance: () => null,
    BridgeNotRunningError: class extends Error {},
    isConnectionError: () => false,
  }));
  const graphImpl = await import(
      "../../lib/jdt/graph.impl.mjs?v=" + Date.now());
  const { createSession } = await import("@kaluchi/qlang-core/session");
  const session = await createSession({
    locator: (ns) => ns !== "jdt/graph" ? null : ({
      source: readFileSync(GRAPH_QLANG, "utf8"),
      impls: { ...graphImpl.createImpls(), ...implsOverrides },
    }),
  });
  // Pre-import :jdt/graph so tests can call its conduits directly
  // via session.evalCell without a `use(:jdt/graph) |` prefix.
  const { error } = await session.evalCell("use(:jdt/graph)");
  if (error) throw error;
  return session;
}

export function resetGraphSession() {
  delete process.env.JDT_GRAPH_CACHE;
  vi.resetModules();
  vi.doUnmock("../../src/client.mjs");
}
