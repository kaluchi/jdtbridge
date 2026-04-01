// ESLint flat config (v9+) for jdt CLI.
// Minimal rules — catch real bugs, enforce file size limits.

import globals from "globals";

export default [
  {
    files: ["src/**/*.mjs"],
    languageOptions: {
      ecmaVersion: 2025,
      sourceType: "module",
      globals: globals.node,
    },
    rules: {
      // Catch bugs
      "no-undef": "error",
      "no-unused-vars": ["warn", { argsIgnorePattern: "^_", caughtErrors: "none" }],
      "no-constant-condition": "warn",
      "no-debugger": "error",
      "no-dupe-args": "error",
      "no-dupe-keys": "error",
      "no-duplicate-case": "error",
      "no-unreachable": "warn",

      // Style consistency
      "eqeqeq": ["warn", "always", { null: "ignore" }],
      "no-var": "error",
      "prefer-const": ["warn", { destructuring: "all" }],

      // File size — prevent monoliths
      "max-lines": ["warn", { max: 500, skipBlankLines: true, skipComments: true }],
    },
  },
  {
    files: ["test/**/*.mjs"],
    languageOptions: {
      ecmaVersion: 2025,
      sourceType: "module",
      globals: globals.node,
    },
    rules: {
      // Relaxed for tests
      "no-unused-vars": "off",
      "max-lines": ["warn", { max: 600, skipBlankLines: true, skipComments: true }],
    },
  },
  {
    ignores: ["node_modules/", "coverage/"],
  },
];
