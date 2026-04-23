// Behavioural tests for the :jdt/graph annotation predicates.
//
//   @annotatedWith(fqn) — true iff subject :annotations contains fqn
//   @deprecated         — alias of @annotatedWith("java.lang.Deprecated")
//
// Both run on inline node-Map subjects, so no HTTP / Eclipse — the
// default loadGraphSession (which throws on any HTTP) catches any
// accidental network attempt.

import { describe, it, expect, beforeAll, afterEach } from "vitest";
import { loadGraphSession, resetGraphSession }
    from "./helpers/graph-session.mjs";

describe("@annotatedWith — annotation-FQN predicate", () => {
  let session;
  beforeAll(async () => { session = await loadGraphSession(); });
  afterEach(resetGraphSession);

  it("true when :annotations contains the FQN", async () => {
    const { result } = await session.evalCell(`
      {:annotations ["org.springframework.stereotype.Service"]}
      | @annotatedWith("org.springframework.stereotype.Service")
    `);
    expect(result).toBe(true);
  });

  it("false when :annotations does not contain the FQN", async () => {
    const { result } = await session.evalCell(`
      {:annotations ["org.springframework.stereotype.Service"]}
      | @annotatedWith("java.lang.Deprecated")
    `);
    expect(result).toBe(false);
  });

  it("false on empty :annotations", async () => {
    const { result } = await session.evalCell(`
      {:annotations []} | @annotatedWith("java.lang.Deprecated")
    `);
    expect(result).toBe(false);
  });

  it("matches exact FQN — substring does not match", async () => {
    const { result } = await session.evalCell(`
      {:annotations ["org.springframework.stereotype.Service"]}
      | @annotatedWith("Service")
    `);
    expect(result).toBe(false);
  });

  it("composes into filter over a Vec of node-Maps", async () => {
    const { result } = await session.evalCell(`
      [{:fqn "A" :annotations ["S.Service"]}
       {:fqn "B" :annotations ["S.Controller"]}
       {:fqn "C" :annotations ["S.Service" "X"]}]
      | filter(@annotatedWith("S.Service")) * /fqn
    `);
    expect(result).toEqual(["A", "C"]);
  });
});

describe("@deprecated — alias predicate", () => {
  let session;
  beforeAll(async () => { session = await loadGraphSession(); });
  afterEach(resetGraphSession);

  it("true on a node with java.lang.Deprecated annotation", async () => {
    const { result } = await session.evalCell(`
      {:annotations ["java.lang.Deprecated"]} | @deprecated
    `);
    expect(result).toBe(true);
  });

  it("false on a node without it", async () => {
    const { result } = await session.evalCell(`
      {:annotations ["java.lang.Override"]} | @deprecated
    `);
    expect(result).toBe(false);
  });
});
