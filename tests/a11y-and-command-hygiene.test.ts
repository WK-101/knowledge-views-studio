import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Phase 117 added accessibility affordances and fixed command hygiene — both DOM-level behaviours that
 * the other gates cannot see (tsc/eslint don't know an ARIA attribute is missing; the unit suite doesn't
 * render the view). These are source-level guards: they prove the wiring is present so it cannot be
 * quietly dropped in a later edit. They are deliberately about *presence*, not correctness of runtime
 * behaviour, which is the honest limit of a static check.
 */

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (rel: string): string => readFileSync(join(root, "src", rel), "utf8");

describe("accessibility wiring is present", () => {
  it("the virtualized table exposes grid + row semantics so a windowed table isn't a lie to a screen reader", () => {
    const table = read("views/table/table-view.ts");
    // Without these, a screen reader counts only the rows currently in the DOM (a dozen) and reports the
    // wrong total for a 500-row table.
    expect(table).toContain('setAttribute("role", "grid")');
    expect(table).toMatch(/setAttribute\("aria-rowcount"/);
    expect(table).toMatch(/setAttribute\("aria-rowindex"/);
    // The header is ARIA row 1; a stray off-by-one here is exactly what the count/index guard below covers.
    expect(table).toMatch(/headRow\.setAttribute\("aria-rowindex", "1"\)/);
  });

  it("aria-rowcount includes every indexed row, so no index can exceed the count", () => {
    // The subtle bug this pins: group-header rows also receive an aria-rowindex, so counting only data
    // rows would let an index run past the stated total whenever grouping is on.
    const table = read("views/table/table-view.ts");
    expect(table).toMatch(/aria-rowcount", String\(items\.length \+ 1\)/);
  });

  it("the save-status indicator is a live region — a silent save/fail is invisible otherwise", () => {
    const view = read("workspace/dashboard-view.ts");
    expect(view).toMatch(/saveStatusEl\.setAttribute\("role", "status"\)/);
    expect(view).toMatch(/saveStatusEl\.setAttribute\("aria-live", "polite"\)/);
  });

  it("the search result count is announced", () => {
    const search = read("workspace/search-view.ts");
    expect(search).toMatch(/countEl\.setAttribute\("aria-live", "polite"\)/);
  });

  it("popovers manage focus: dialog role, focus moves in, focus returns on close", () => {
    const pop = read("workspace/popover.ts");
    expect(pop).toContain('setAttribute("role", "dialog")');
    expect(pop).toMatch(/returnFocusTo/); // captured on open
    expect(pop).toMatch(/returnFocusTo\.focus\(\)/); // restored on close
    expect(pop).toMatch(/firstFocusable \?\? el\)\.focus\(\)/); // focus moved in on open
  });
});

describe("command hygiene: context-dependent commands hide instead of scolding", () => {
  const main = read("main.ts");

  it("no command fires an 'Open a Knowledge View first' Notice — they use checkCallback", () => {
    // The whole point: a command that needs a view open should be absent from the palette when there
    // isn't one, per Obsidian convention — not present, then failing with a toast when invoked.
    expect(main).not.toContain("Open a Knowledge View first");
    expect(main).not.toContain("Open a Knowledge View to use focus mode");
  });

  it("the view-dependent commands are all checkCallback-gated", () => {
    // Each should return false when getActiveViewOfType(DashboardView) is null.
    for (const id of [
      "toggle-focus-mode",
      "add-papers-by-doi",
      "fill-missing-from-doi",
      "find-duplicate-dois",
      "find-citation-links",
      "shard-library",
    ]) {
      // Slice from this command's id to the next addCommand call, so a longer comment can't push the
      // guard out of a fixed-width window.
      const start = main.indexOf(`id: "${id}"`);
      const next = main.indexOf("addCommand({", start);
      const block = main.slice(start, next === -1 ? start + 600 : next);
      expect(block, `${id} should use checkCallback`).toMatch(/checkCallback: \(checking\) =>/);
      expect(block, `${id} should return false when no view is active`).toMatch(/if \(!view\) return false;/);
    }
  });
});
