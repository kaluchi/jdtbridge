// Behavioural tests for the :jdt/graph @untested predicate via
// :qlang/impl override on @incomingRefs — the single builtin under
// @untested → @callers → @incomingRefs. stubOp parses a qlang-literal
// source string and wires it as the operand's stub impl in one step.

import { describe, it, expect, afterEach } from "vitest";
import { loadGraphSession, resetGraphSession }
    from "./helpers/graph-session.mjs";
import { stubOp } from "./helpers/operand-stub.mjs";

describe("@untested — test-coverage gap predicate", () => {
  afterEach(resetGraphSession);

  it("true when the member has no callers at all", async () => {
    const { result } = await (await loadGraphSession({
      implsOverrides: await stubOp("@incomingRefs", `[]`),
    })).evalCell(`{:fqn "pkg.Foo#bar()" :kind "method"} | @untested`);
    expect(result).toBe(true);
  });

  it("true when every caller is production (no test-scope)", async () => {
    const { result } = await (await loadGraphSession({
      implsOverrides: await stubOp("@incomingRefs", `[
        {:refKind "call" :from {:fqn "pkg.A#x()" :isTestScope false}}
        {:refKind "call" :from {:fqn "pkg.B#y()" :isTestScope false}}
      ]`),
    })).evalCell(`{:fqn "pkg.Foo#bar()" :kind "method"} | @untested`);
    expect(result).toBe(true);
  });

  it("false when at least one caller is test-scope", async () => {
    const { result } = await (await loadGraphSession({
      implsOverrides: await stubOp("@incomingRefs", `[
        {:refKind "call" :from {:fqn "pkg.A#x()"        :isTestScope false}}
        {:refKind "call" :from {:fqn "pkg.FooTest#t1()" :isTestScope true}}
      ]`),
    })).evalCell(`{:fqn "pkg.Foo#bar()" :kind "method"} | @untested`);
    expect(result).toBe(false);
  });

  it("false when ALL callers are test-scope (covered)", async () => {
    const { result } = await (await loadGraphSession({
      implsOverrides: await stubOp("@incomingRefs", `[
        {:refKind "call" :from {:fqn "pkg.FooTest#t1()" :isTestScope true}}
        {:refKind "call" :from {:fqn "pkg.FooTest#t2()" :isTestScope true}}
      ]`),
    })).evalCell(`{:fqn "pkg.Foo#bar()" :kind "method"} | @untested`);
    expect(result).toBe(false);
  });

  it("ignores non-call refKinds — a test-scope read is not a caller",
        async () => {
    const { result } = await (await loadGraphSession({
      implsOverrides: await stubOp("@incomingRefs", `[
        {:refKind "read" :from {:fqn "pkg.FooTest#peek()" :isTestScope true}}
      ]`),
    })).evalCell(`{:fqn "pkg.Foo#bar()" :kind "method"} | @untested`);
    expect(result).toBe(true);
  });
});
