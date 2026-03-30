import { describe, it, expect } from "vitest";
import { parseFlags, extractPositional } from "../src/args.mjs";

describe("parseFlags", () => {
  it("parses key-value flags", () => {
    expect(parseFlags(["--field", "name"])).toEqual({ field: "name" });
  });

  it("parses boolean flags", () => {
    expect(parseFlags(["--source-only"])).toEqual({ "source-only": true });
  });

  it("parses mixed flags", () => {
    expect(parseFlags(["--arity", "2", "--no-refresh"])).toEqual({
      arity: "2",
      "no-refresh": true,
    });
  });

  it("skips non-flag arguments", () => {
    expect(parseFlags(["app.Foo", "bar", "--arity", "3"])).toEqual({
      arity: "3",
    });
  });

  it("returns empty for no args", () => {
    expect(parseFlags([])).toEqual({});
  });
});

describe("extractPositional", () => {
  it("extracts positional arguments", () => {
    expect(extractPositional(["app.Foo", "bar"])).toEqual(["app.Foo", "bar"]);
  });

  it("skips flags and their values", () => {
    expect(
      extractPositional(["app.Foo", "--arity", "2", "method"]),
    ).toEqual(["app.Foo", "method"]);
  });

  it("skips boolean flags", () => {
    expect(
      extractPositional(["app.Foo", "--source-only"]),
    ).toEqual(["app.Foo"]);
  });

  it("returns empty for flags only", () => {
    expect(extractPositional(["--project", "my-server"])).toEqual([]);
  });
});
