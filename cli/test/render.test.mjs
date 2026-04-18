import { describe, it, expect } from "vitest";
import { createSession } from "@kaluchi/qlang-core/session";
import { keyword } from "@kaluchi/qlang-core";
import { bindJdtRenderOperands } from "../lib/jdt/render.impl.mjs";

// Pure unit tests for the host-bound markdown renderers. No HTTP,
// no Eclipse — each test constructs a bundle Map and invokes the
// renderer through a fresh qlang session so the public operand
// dispatch path is exercised.

async function runWithBundle(operandName, bundle) {
  const session = await createSession();
  bindJdtRenderOperands(session);
  session.bind("__bundle", bundle);
  const { result, error } = await session.evalCell(
      `__bundle | ${operandName}`);
  if (error) throw error;
  return result;
}

function map(entries) {
  return new Map(entries.map(([k, v]) => [keyword(k), v]));
}

describe("mdSource", () => {
  it("renders header + location for a method node", async () => {
    const node = map([
      ["fqn", "pkg.Foo#bar(java.lang.String)"],
      ["kind", "method"],
      ["location", map([
        ["file", "/src/Foo.java"],
        ["startLine", 10],
        ["endLine", 15],
      ])],
    ]);
    const md = await runWithBundle("mdSource",
        map([["node", node]]));
    expect(md).toContain("#### [M] pkg.Foo#bar(java.lang.String)");
    expect(md).toContain("`/src/Foo.java:10-15`");
  });

  it("embeds source text in a java code fence", async () => {
    const node = map([
      ["fqn", "pkg.Foo#bar()"],
      ["kind", "method"],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", node],
      ["text", "void bar() {\n  return;\n}"],
    ]));
    expect(md).toContain("```java\nvoid bar() {");
    expect(md).toMatch(/```\s*$/);
  });

  it("omits every section whose bundle slot is empty", async () => {
    const node = map([
      ["fqn", "pkg.Foo"],
      ["kind", "type"],
      ["typeKind", "class"],
    ]);
    const md = await runWithBundle("mdSource",
        map([["node", node]]));
    expect(md).not.toContain("Outgoing Calls");
    expect(md).not.toContain("Incoming Calls");
    expect(md).not.toContain("Hierarchy");
    expect(md).toContain("#### [C] pkg.Foo");
  });

  it("renders outgoing and incoming calls as flat lists", async () => {
    const outRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["to", map([
        ["fqn", "pkg.Other#doStuff(int)"],
        ["kind", "method"],
        ["returnType", "void"],
      ])],
    ]);
    const inRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["from", map([
        ["fqn", "pkg.Caller#invoke()"],
        ["kind", "method"],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Foo#bar()"],
        ["kind", "method"],
      ])],
      ["outgoing", [outRef]],
      ["incoming", [inRef]],
    ]));
    expect(md).toContain("#### Outgoing Calls:");
    expect(md).toContain("[M] `pkg.Other#doStuff(int)` → `void`");
    expect(md).toContain("#### Incoming Calls:");
    expect(md).toContain("[M] `pkg.Caller#invoke()`");
  });

  it("routes type-kind badge via :typeKind on types", async () => {
    const node = map([
      ["fqn", "pkg.Runnable"],
      ["kind", "type"],
      ["typeKind", "interface"],
    ]);
    const md = await runWithBundle("mdSource",
        map([["node", node]]));
    expect(md).toContain("#### [I] pkg.Runnable");
  });

  it("routes static+final field badge to [K]", async () => {
    const node = map([
      ["fqn", "pkg.Foo#THRESHOLD"],
      ["kind", "field"],
      ["modifiers", ["public", "static", "final"]],
    ]);
    const outgoingRef = map([
      ["kind", "reference"],
      ["refKind", "read"],
      ["to", node],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([["fqn", "pkg.Caller"], ["kind", "method"]])],
      ["outgoing", [outgoingRef]],
    ]));
    expect(md).toContain("[K] `pkg.Foo#THRESHOLD`");
  });
});

describe("mdHierarchy", () => {
  it("renders ↑ and ↓ sections with badges", async () => {
    const node = map([["fqn", "pkg.Foo"], ["kind", "type"],
                      ["typeKind", "class"]]);
    const md = await runWithBundle("mdHierarchy", map([
      ["node", node],
      ["supers", [map([["fqn", "pkg.Base"],
                       ["kind", "type"],
                       ["typeKind", "class"]])]],
      ["subtypes", [map([["fqn", "pkg.FooImpl"],
                         ["kind", "type"],
                         ["typeKind", "class"]])]],
    ]));
    expect(md).toContain("#### Supertypes:");
    expect(md).toContain("↑ [C] `pkg.Base`");
    expect(md).toContain("#### Subtypes:");
    expect(md).toContain("↓ [C] `pkg.FooImpl`");
  });
});

describe("mdOutline", () => {
  it("groups members by kind with signatures and modifiers", async () => {
    const node = map([["fqn", "pkg.Foo"],
                      ["kind", "type"],
                      ["typeKind", "class"]]);
    const field = map([
      ["fqn", "pkg.Foo#name"],
      ["kind", "field"],
      ["name", "name"],
      ["type", "java.lang.String"],
      ["modifiers", ["private", "final"]],
      ["location", map([["startLine", 5], ["endLine", 5]])],
    ]);
    const method = map([
      ["fqn", "pkg.Foo#greet(java.lang.String)"],
      ["kind", "method"],
      ["signature", "greet(java.lang.String)"],
      ["returnType", "void"],
      ["modifiers", ["public"]],
      ["location", map([["startLine", 10], ["endLine", 15]])],
    ]);
    const md = await runWithBundle("mdOutline", map([
      ["node", node],
      ["members", [field, method]],
    ]));
    expect(md).toContain("#### Fields:");
    expect(md).toContain(
        "[F] name : java.lang.String (private, final)  5");
    expect(md).toContain("#### Methods:");
    expect(md).toContain(
        "[M] greet(java.lang.String) : void (public)  10-15");
  });

  it("renders just the header when members is empty", async () => {
    const md = await runWithBundle("mdOutline", map([
      ["node", map([["fqn", "pkg.Empty"],
                    ["kind", "type"],
                    ["typeKind", "class"]])],
      ["members", []],
    ]));
    expect(md).toContain("#### [C] pkg.Empty");
    expect(md).not.toContain("#### Fields:");
    expect(md).not.toContain("#### Methods:");
  });
});

describe("mdRefs", () => {
  it("groups a mixed Vec by refKind", async () => {
    const callRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["to", map([["fqn", "pkg.Foo#a()"], ["kind", "method"]])],
    ]);
    const writeRef = map([
      ["kind", "reference"],
      ["refKind", "write"],
      ["to", map([["fqn", "pkg.Foo#b"], ["kind", "field"]])],
    ]);
    const md = await runWithBundle("mdRefs", [callRef, writeRef]);
    expect(md).toContain("#### Calls:");
    expect(md).toContain("[M] `pkg.Foo#a()`");
    expect(md).toContain("#### Writes:");
    expect(md).toContain("[F] `pkg.Foo#b`");
  });

  it("dedupes repeated targets within a kind", async () => {
    const ref = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["to", map([["fqn", "pkg.Foo#a()"], ["kind", "method"]])],
    ]);
    const md = await runWithBundle("mdRefs", [ref, ref, ref]);
    const hits = md.match(/pkg\.Foo#a\(\)/g) ?? [];
    expect(hits.length).toBe(1);
  });

  it("returns an empty string on an empty Vec", async () => {
    const md = await runWithBundle("mdRefs", []);
    expect(md).toBe("");
  });
});
