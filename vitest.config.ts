import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // The neural sandbox is inlined as a string by esbuild; vitest must not try to parse it as JS.
  assetsInclude: ["**/*.html"],
  test: {
    setupFiles: ["./tests/setup.ts"],
    environment: "node",
    include: ["tests/**/*.test.ts"],
  },
  resolve: {
    alias: {
      // Value imports from "obsidian" resolve to a tiny mock in tests; type imports are erased.
      obsidian: fileURLToPath(new URL("./tests/mocks/obsidian.ts", import.meta.url)),
    },
  },
});
