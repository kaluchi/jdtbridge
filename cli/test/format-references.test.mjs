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

  it("formats source reference with file:line, in, content", () => {
    formatReferences([
      { file: "/my-server/src/Foo.java", line: 42, in: "Bar.baz(String)", content: "foo.doStuff();" },
    ]);
    expect(logs[0]).toBe("my-server/src/Foo.java:42");
    expect(logs[1]).toContain("in Bar.baz(String)");
    expect(logs[2]).toContain("| foo.doStuff();");
  });

  it("groups binary references by project and jar", () => {
    formatReferences([
      { file: "/libs/some.jar", line: -1, project: "my-core", in: "SomeClass.method()", content: null },
    ]);
    expect(logs[0]).toContain("my-core");
    expect(logs[0]).toContain("some.jar");
    expect(logs[1]).toContain("SomeClass.method()");
  });

  it("separates groups with blank line", () => {
    formatReferences([
      { file: "/my-app/src/A.java", line: 10, in: "A.m()", content: "x" },
      { file: "/my-app/src/B.java", line: 20, in: "B.m()", content: "y" },
    ]);
    expect(logs.includes("")).toBe(true);
  });

  it("handles source ref without in or content", () => {
    formatReferences([
      { file: "/my-app/src/A.java", line: 5, in: null, content: null },
    ]);
    expect(logs[0]).toBe("my-app/src/A.java:5");
    expect(logs).toHaveLength(1);
  });

  it("handles binary ref with content", () => {
    formatReferences([
      { file: "/libs/x.jar", line: -1, project: "dep", in: "C.m()", content: "call()" },
    ]);
    expect(logs[0]).toContain("dep");
    expect(logs[1]).toContain("C.m()");
    expect(logs[2]).toContain("| call()");
  });

  it("handles binary ref without project", () => {
    formatReferences([
      { file: "/libs/x.jar", line: 0, project: null, in: null, content: null },
    ]);
    expect(logs[0]).toContain("?");
  });
});
