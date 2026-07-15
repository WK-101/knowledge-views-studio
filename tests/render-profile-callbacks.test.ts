import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const renderProfileSrc = readFileSync(resolve(here, "../src/views/render-profile.ts"), "utf8");

/**
 * Regression guard for a real bug: "Fill details from Zotero" was wired end to end — the dashboard set the
 * callback and the table view read it — but the render-profile options layer *between* them silently
 * dropped it, because the callback was never added to that layer's passthrough. Endpoint checks missed it;
 * only tracing the whole chain would have caught it.
 *
 * These row-action callbacks must survive the trip from options to the view context. This test parses
 * render-profile.ts and asserts each one is both a declared option and forwarded into the context, so a new
 * row action can't be added at the ends while being dropped in the middle again.
 */
const ROW_ACTION_CALLBACKS = ["onFetchDoi", "onFetchZotero", "onPromote", "onCite", "onFetchDoiValues"];

describe("render-profile forwards every row-action callback", () => {
  for (const cb of ROW_ACTION_CALLBACKS) {
    it(`declares and forwards ${cb}`, () => {
      // Declared as an option on the interface.
      expect(renderProfileSrc).toMatch(new RegExp(`readonly ${cb}\\?:`));
      // Forwarded into the ViewRenderContext (the `...(options.X ? { X: options.X } : {})` spread).
      expect(renderProfileSrc).toContain(`options.${cb} ? { ${cb}: options.${cb}`);
    });
  }
});
