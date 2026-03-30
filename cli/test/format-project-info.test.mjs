import { describe, it, expect } from "vitest";
import { formatProjectInfo } from "../src/format/project-info.mjs";

function makeData(overrides = {}) {
  return {
    name: "test-project",
    location: "/home/user/workspace/test-project",
    natures: ["org.eclipse.jdt.core.javanature"],
    dependencies: ["my-core", "my-shared"],
    totalTypes: 5,
    membersIncluded: false,
    sourceRoots: [
      {
        path: "src/main/java",
        typeCount: 5,
        packages: [
          {
            name: "app.test",
            types: [
              { name: "Foo", kind: "class", fields: 2, methods: {} },
              { name: "Bar", kind: "interface", fields: 0, methods: {} },
            ],
          },
          {
            name: "app.test.util",
            types: [
              { name: "Helper", kind: "class", fields: 1, methods: {} },
            ],
          },
        ],
      },
    ],
    ...overrides,
  };
}

describe("formatProjectInfo", () => {
  it("always includes header", () => {
    const output = formatProjectInfo(makeData(), 6);
    expect(output).toContain("test-project");
    expect(output).toContain("Location:");
    expect(output).toContain("Total: 5 types");
  });

  it("shows packages tier for small budget", () => {
    const output = formatProjectInfo(makeData(), 10);
    expect(output).toContain("app.test (2)");
    expect(output).toContain("app.test.util (1)");
  });

  it("shows types tier for large budget", () => {
    const output = formatProjectInfo(makeData(), 50);
    expect(output).toContain("Foo");
    expect(output).toContain("Bar (interface)");
    expect(output).toContain("Helper");
  });

  it("shows dependencies", () => {
    const output = formatProjectInfo(makeData(), 50);
    expect(output).toContain("my-core, my-shared");
  });

  it("shows (none) when no dependencies", () => {
    const output = formatProjectInfo(makeData({ dependencies: [] }), 50);
    expect(output).toContain("(none)");
  });

  it("shows natures", () => {
    const output = formatProjectInfo(makeData(), 50);
    expect(output).toContain("org.eclipse.jdt.core.javanature");
  });

  it("skips natures line when empty", () => {
    const output = formatProjectInfo(makeData({ natures: [] }), 50);
    expect(output).not.toContain("Natures:");
  });

  it("truncates packages when budget exceeded", () => {
    const manyPkgs = Array.from({ length: 50 }, (_, i) => ({
      name: `pkg.p${i}`,
      types: [{ name: `T${i}`, kind: "class", fields: 0, methods: {} }],
    }));
    const data = makeData({
      sourceRoots: [{ path: "src", typeCount: 50, packages: manyPkgs }],
    });
    const output = formatProjectInfo(data, 15);
    expect(output).toContain("... and");
    expect(output).toContain("more packages");
  });

  it("shows method signatures when membersIncluded and budget allows", () => {
    const data = makeData({
      membersIncluded: true,
      totalTypes: 1,
      sourceRoots: [
        {
          path: "src",
          typeCount: 1,
          packages: [
            {
              name: "app",
              types: [
                {
                  name: "Svc",
                  kind: "class",
                  fields: 0,
                  methods: {
                    public: ["void doWork()", "String getName()"],
                    protected: [],
                    default: [],
                    private: ["void init()"],
                  },
                },
              ],
            },
          ],
        },
      ],
    });
    const output = formatProjectInfo(data, 100);
    expect(output).toContain("void doWork()");
    expect(output).toContain("String getName()");
    expect(output).toContain("void init()");
  });

  it("falls back to types when methods don't fit", () => {
    const data = makeData({
      membersIncluded: true,
      totalTypes: 2,
      sourceRoots: [
        {
          path: "src",
          typeCount: 2,
          packages: [
            {
              name: "app",
              types: [
                {
                  name: "A",
                  kind: "class",
                  fields: 0,
                  methods: {
                    public: Array.from({ length: 100 }, (_, i) => `void m${i}()`),
                    protected: [],
                    default: [],
                    private: [],
                  },
                },
                { name: "B", kind: "class", fields: 0, methods: {} },
              ],
            },
          ],
        },
      ],
    });
    // Budget 15 is too small for 100 methods
    const output = formatProjectInfo(data, 15);
    expect(output).toContain("A");
    expect(output).toContain("B");
    expect(output).not.toContain("void m0()");
  });

  it("returns just header when budget is zero", () => {
    const output = formatProjectInfo(makeData(), 0);
    // Should at least produce header lines even if budget is 0
    expect(output).toContain("test-project");
  });
});
