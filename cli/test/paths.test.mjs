import { describe, it, expect } from "vitest";
import { stripProject, toWsPath } from "../src/paths.mjs";

describe("stripProject", () => {
  it("strips leading slash", () => {
    expect(stripProject("/my-server/src/main/java/Foo.java")).toBe(
      "my-server/src/main/java/Foo.java",
    );
  });

  it("returns path as-is if no leading slash", () => {
    expect(stripProject("my-server/src/Foo.java")).toBe(
      "my-server/src/Foo.java",
    );
  });
});

describe("toWsPath", () => {
  it("adds leading slash", () => {
    expect(toWsPath("my-server/src/Foo.java")).toBe(
      "/my-server/src/Foo.java",
    );
  });

  it("keeps existing leading slash", () => {
    expect(toWsPath("/my-server/src/Foo.java")).toBe(
      "/my-server/src/Foo.java",
    );
  });
});
