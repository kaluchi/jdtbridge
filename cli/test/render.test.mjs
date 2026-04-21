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
    expect(md).not.toContain("Outgoing");
    expect(md).not.toContain("Incoming");
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
    expect(md).toContain("#### Outgoing calls:");
    expect(md).toContain("[M] `pkg.Other#doStuff(int)` → `void`");
    expect(md).toContain("#### Incoming calls:");
    expect(md).toContain("[M] `pkg.Caller#invoke()`");
  });

  it("renders field refs with ': <type>' suffix (erased type)",
        async () => {
    // Method refs carry :returnType and render `→ void`. Field refs
    // carry :type (erased — generics stripped per JDT convention)
    // and render `: java.util.Map`. Both suffixes visible at once
    // when the outgoing Vec mixes methods and fields.
    const methodRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["to", map([
        ["fqn", "pkg.Other#doStuff(int)"],
        ["kind", "method"],
        ["returnType", "int"],
      ])],
    ]);
    const fieldRef = map([
      ["kind", "reference"],
      ["refKind", "read"],
      ["to", map([
        ["fqn", "pkg.Foo#queues"],
        ["kind", "field"],
        ["type", "java.util.Map"],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Foo#bar()"],
        ["kind", "method"],
      ])],
      ["outgoing", [methodRef, fieldRef]],
    ]));
    expect(md).toContain("[M] `pkg.Other#doStuff(int)` → `int`");
    expect(md).toContain("[F] `pkg.Foo#queues` : `java.util.Map`");
  });

  it("field ref without :type renders bare fqn line", async () => {
    // Degenerate skeleton — no :type on the field. Line is just
    // badge + fqn, no type-suffix noise.
    const fieldRef = map([
      ["kind", "reference"],
      ["refKind", "read"],
      ["to", map([
        ["fqn", "pkg.Foo#count"],
        ["kind", "field"],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Foo#bar()"],
        ["kind", "method"],
      ])],
      ["outgoing", [fieldRef]],
    ]));
    expect(md).toContain("[F] `pkg.Foo#count`");
    expect(md).not.toContain("[F] `pkg.Foo#count` :");
    expect(md).not.toContain("[F] `pkg.Foo#count` →");
  });

  it("enclosing-synthetic lambda context — `(lambda: Interface)` suffix",
        async () => {
    // Plugin collapses a call from inside a lambda body to the
    // nearest addressable enclosing method, then records the
    // synthetic IType's SAM interface in :enclosingSynthetic.
    // Renderer surfaces it as `(lambda: \`Runnable\`)` after the
    // returnType suffix — line stays one logical entry.
    const lambdaRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["from", map([
        ["fqn", "pkg.Outer#enclose()"],
        ["kind", "method"],
        ["returnType", "void"],
        ["enclosingSynthetic", map([
          ["kind", "lambda"],
          ["interface", "java.lang.Runnable"],
        ])],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Target#hit()"],
        ["kind", "method"],
      ])],
      ["incoming", [lambdaRef]],
    ]));
    expect(md).toContain(
        "[M] `pkg.Outer#enclose()` → `void` "
      + "(lambda: `java.lang.Runnable`)");
  });

  it("enclosing-synthetic anonymous with :interface — `(anon: Iface)`",
        async () => {
    const anonRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["from", map([
        ["fqn", "pkg.Outer#enclose()"],
        ["kind", "method"],
        ["returnType", "void"],
        ["enclosingSynthetic", map([
          ["kind", "anonymous"],
          ["interface", "java.lang.Runnable"],
        ])],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Target#hit()"],
        ["kind", "method"],
      ])],
      ["incoming", [anonRef]],
    ]));
    expect(md).toContain(
        "[M] `pkg.Outer#enclose()` → `void` "
      + "(anon: `java.lang.Runnable`)");
  });

  it("enclosing-synthetic anonymous with :super — `(anon: SuperType)`",
        async () => {
    // No SAM interface → plugin records :super with the anonymous
    // class's superclass name (the `new Foo() {...}` case).
    const anonRef = map([
      ["kind", "reference"],
      ["refKind", "call"],
      ["from", map([
        ["fqn", "pkg.Outer#enclose()"],
        ["kind", "method"],
        ["enclosingSynthetic", map([
          ["kind", "anonymous"],
          ["super", "pkg.BaseHandler"],
        ])],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Target#hit()"],
        ["kind", "method"],
      ])],
      ["incoming", [anonRef]],
    ]));
    expect(md).toContain(
        "[M] `pkg.Outer#enclose()` (anon: `pkg.BaseHandler`)");
  });

  it("type refs get no type suffix (neither :returnType nor :type)",
        async () => {
    // Type skeletons don't carry either field — the suffix stays
    // empty so the line is just `[C] fqn`. Verifies field-type
    // rendering doesn't leak into type kinds through attribute
    // coincidence.
    const typeRef = map([
      ["kind", "reference"],
      ["refKind", "typeUse"],
      ["to", map([
        ["fqn", "pkg.Target"],
        ["kind", "type"],
        ["typeKind", "class"],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Foo"],
        ["kind", "type"],
        ["typeKind", "class"],
      ])],
      ["outgoing", [typeRef]],
    ]));
    expect(md).toContain("[C] `pkg.Target`");
    expect(md).not.toContain("[C] `pkg.Target` :");
    expect(md).not.toContain("[C] `pkg.Target` →");
  });

  it("labels refs by subject kind — type subject → references", async () => {
    const typeUseRef = map([
      ["kind", "reference"],
      ["refKind", "typeUse"],
      ["from", map([
        ["fqn", "pkg.Caller"],
        ["kind", "type"],
        ["typeKind", "class"],
      ])],
    ]);
    const md = await runWithBundle("mdSource", map([
      ["node", map([
        ["fqn", "pkg.Foo"],
        ["kind", "type"],
        ["typeKind", "class"],
      ])],
      ["incoming", [typeUseRef]],
    ]));
    expect(md).toContain("#### Incoming references:");
    expect(md).not.toContain("Incoming calls");
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

  it(":direction \"incoming\" renders :from side (callers)", async () => {
    // Simulates `"Foo" | @incomingRefs` — server stamps every
    // record with :direction "incoming"; mdRefs surfaces :from
    // without any fqn-fixity heuristic.
    const subject = map([["fqn", "pkg.Foo"], ["kind", "type"],
                         ["typeKind", "class"]]);
    const caller1 = map([["fqn", "pkg.Caller1"], ["kind", "type"],
                         ["typeKind", "class"]]);
    const caller2 = map([["fqn", "pkg.Caller2"], ["kind", "type"],
                         ["typeKind", "class"]]);
    const ref1 = map([
      ["kind", "reference"],
      ["direction", "incoming"],
      ["refKind", "typeUse"],
      ["from", caller1],
      ["to", subject],
    ]);
    const ref2 = map([
      ["kind", "reference"],
      ["direction", "incoming"],
      ["refKind", "typeUse"],
      ["from", caller2],
      ["to", subject],
    ]);
    const md = await runWithBundle("mdRefs", [ref1, ref2]);
    expect(md).toContain("pkg.Caller1");
    expect(md).toContain("pkg.Caller2");
    // Subject should appear zero times — we're not repeating it
    // on every row.
    const subjectHits = md.match(/pkg\.Foo/g) ?? [];
    expect(subjectHits.length).toBe(0);
  });

  it(":direction \"outgoing\" renders :to side (targets)", async () => {
    // Simulates `"pkg.Foo#bar()" | @outgoingRefs`.
    const subject = map([["fqn", "pkg.Foo#bar()"], ["kind", "method"]]);
    const target1 = map([["fqn", "pkg.Target1#a()"], ["kind", "method"]]);
    const target2 = map([["fqn", "pkg.Target2#b()"], ["kind", "method"]]);
    const ref1 = map([
      ["kind", "reference"],
      ["direction", "outgoing"],
      ["refKind", "call"],
      ["from", subject],
      ["to", target1],
    ]);
    const ref2 = map([
      ["kind", "reference"],
      ["direction", "outgoing"],
      ["refKind", "call"],
      ["from", subject],
      ["to", target2],
    ]);
    const md = await runWithBundle("mdRefs", [ref1, ref2]);
    expect(md).toContain("pkg.Target1#a()");
    expect(md).toContain("pkg.Target2#b()");
    expect(md).not.toContain("pkg.Foo#bar()");
  });

  it("skips directionless rows and picks side from the next :direction", async () => {
    // Mixed Vec — a hand-crafted row without :direction precedes
    // server-shaped rows. The directionless row must not short-
    // circuit the side pick; the first :direction-bearing row wins.
    const subject = map([["fqn", "pkg.Foo"], ["kind", "type"],
                         ["typeKind", "class"]]);
    const caller = map([["fqn", "pkg.Caller"], ["kind", "type"],
                        ["typeKind", "class"]]);
    const directionless = map([
      ["kind", "reference"],
      ["refKind", "typeUse"],
      ["from", caller],
      ["to", subject],
    ]);
    const incoming = map([
      ["kind", "reference"],
      ["direction", "incoming"],
      ["refKind", "typeUse"],
      ["from", caller],
      ["to", subject],
    ]);
    const md = await runWithBundle("mdRefs",
        [directionless, incoming]);
    expect(md).toContain("pkg.Caller");
    expect(md).not.toContain("pkg.Foo");
  });

  it("single-record incoming still renders caller", async () => {
    // N=1 edge case — :direction removes the fqn-fixity heuristic
    // ambiguity that used to require N≥2.
    const ref = map([
      ["kind", "reference"],
      ["direction", "incoming"],
      ["refKind", "typeUse"],
      ["from", map([["fqn", "pkg.Caller"], ["kind", "type"],
                    ["typeKind", "class"]])],
      ["to", map([["fqn", "pkg.Foo"], ["kind", "type"],
                  ["typeKind", "class"]])],
    ]);
    const md = await runWithBundle("mdRefs", [ref]);
    expect(md).toContain("pkg.Caller");
    expect(md).not.toContain("pkg.Foo");
  });
});
