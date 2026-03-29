import { describe, it, expect } from "vitest";

describe("sandbox", () => {
  it("module loads without error", async () => {
    const mod = await import("../src/commands/sandbox.mjs");
    expect(mod.sandboxRun).toBeDefined();
    expect(mod.help).toBeDefined();
  });
});
