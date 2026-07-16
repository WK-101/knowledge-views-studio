import js from "@eslint/js";
import tseslint from "typescript-eslint";
import obsidianmd from "eslint-plugin-obsidianmd";

export default tseslint.config(
  { ignores: ["main.js", "dist/**", "node_modules/**", "coverage/**", "**/*.mjs", "scripts/**", "vitest.config.ts"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,

  // Obsidian's own plugin-review rules -- the same ones the community-directory reviewers run.
  // Better to fail in our gate than in their review.
  ...obsidianmd.configs.recommended,

  {
    // Typed linting applies to TypeScript only. obsidianmd also lints package.json, which must not be
    // handed to the TS parser.
    files: ["**/*.ts"],
    languageOptions: {
      parserOptions: { project: "./tsconfig.json", tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      "@typescript-eslint/no-empty-function": "off",
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],

      // Obsidian's reviewer report did not raise sentence-case, and the rule mangles acronyms we use
      // deliberately -- it wants "Kvs", "doi", "pdf", "apa". Our capitalisation is correct English.
      "obsidianmd/ui/sentence-case": "off",
      // The declarative settings API arrived in 1.13; minAppVersion is 1.10, so display() is still right.
      "obsidianmd/settings-tab/prefer-setting-definitions": "off",
      "@typescript-eslint/no-deprecated": "warn",
    },
  },

  // Tests are not plugin code: they run under Node, never ship, and deliberately poke at untyped
  // library internals (pdf-lib dictionaries, raw archive records) to assert on them.
  {
    files: ["tests/**/*.ts"],
    rules: {
      "obsidianmd/no-global-this": "off",
      "obsidianmd/prefer-window-timers": "off",
      // A test reading styles.css or walking src/ needs node:fs — and cannot run on mobile because it
      // does not run in the plugin at all. The rule guards runtime code; here it is a false positive.
      "obsidianmd/no-nodejs-modules": "off",
      "@typescript-eslint/no-unsafe-assignment": "off",
      "@typescript-eslint/no-unsafe-argument": "off",
      "@typescript-eslint/no-unsafe-member-access": "off",
      "@typescript-eslint/no-base-to-string": "off",
    },
  },
);
