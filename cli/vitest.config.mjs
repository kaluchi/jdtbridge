import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.mjs"],
    env: {
      // Clear inherited env vars that interfere with mock-based tests.
      // Tests mock bridge-env.mjs and discovery.mjs instead.
      FORCE_COLOR: "",
    },
  },
});
