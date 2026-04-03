import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { formatReferences } from "../src/format/references.mjs";

describe("formatReferences", () => {
  let logs;
  const origLog = console.log;

  beforeEach(() => {
    logs = [];
    console.log = (...args) => logs.push(args.join(" "));
  });

  afterEach(() => {
    console.log = origLog;
  });

  it("formats source reference as markdown snippet", () => {
    formatReferences([
      { file: "/my-server/src/Foo.java", line: 42, in: "Bar.baz(String)", content: "foo.doStuff();" },
    ]);
    const out = logs[0];
    expect(out).toContain("#### `Bar.baz(String)`");
    expect(out).toContain("`my-server/src/Foo.java:42`");
    expect(out).toContain("```java");
    expect(out).toContain("foo.doStuff();");
  });

  it("formats binary reference: header + location on separate lines", () => {
    formatReferences([
      { file: "/libs/some.jar", line: -1, project: "my-core", in: "SomeClass.method()", content: null },
    ]);
    const out = logs[0];
    expect(out).toContain("#### `SomeClass.method()`");
    expect(out).toContain("`my-core (some.jar)`");
  });

  it("separates multiple refs with ---", () => {
    formatReferences([
      { file: "/my-app/src/A.java", line: 10, in: "A.m()", content: "x" },
      { file: "/my-app/src/B.java", line: 20, in: "B.m()", content: "y" },
    ]);
    const out = logs[0];
    expect(out).toContain("---");
  });

  it("handles source ref without in or content", () => {
    formatReferences([
      { file: "/my-app/src/A.java", line: 5, in: null, content: null },
    ]);
    const out = logs[0];
    expect(out).toContain("#### my-app/src/A.java");
    expect(out).toContain("`my-app/src/A.java:5`");
    expect(out).not.toContain("```");
  });

  it("handles binary ref with content", () => {
    formatReferences([
      { file: "/libs/x.jar", line: -1, project: "dep", in: "C.m()", content: "call()" },
    ]);
    const out = logs[0];
    expect(out).toContain("#### `C.m()`");
    expect(out).toContain("`dep (x.jar)`");
    expect(out).toContain("```java");
    expect(out).toContain("call()");
  });

  it("handles binary ref without project", () => {
    formatReferences([
      { file: "/libs/x.jar", line: 0, project: null, in: null, content: null },
    ]);
    const out = logs[0];
    expect(out).toContain("?");
  });
});
