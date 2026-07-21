import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Nothing else tests the stylesheet. `tsc` cannot see it, `vitest` does not render it, `eslint` lints
 * TypeScript. So a selector can point at a class the DOM never emits — a rule that silently does
 * nothing — and every gate stays green. That is not hypothetical: the first cut of the mobile work
 * (Phase 113) shipped `.kvs-toolbar`, `.kvs-tabs`, `.kvs-tab` and `.kvs-layout-tabs`, none of which the
 * dashboard actually creates. The rules were dead and no tool complained.
 *
 * This is the missing gate: parse every `.kvs-…` class the stylesheet targets, and assert each one is a
 * class some source file genuinely puts on an element. It cannot prove a rule *looks* right, but it
 * proves the rule can *match something*, which is the failure that just bit us.
 */

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function readAllSource(dir: string): string {
  let out = "";
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out += readAllSource(full);
    else if (entry.endsWith(".ts")) out += readFileSync(full, "utf8");
  }
  return out;
}

/** Classes assembled at runtime from a variable, e.g. `kvs-attach-${kind}` or `kvs-rows-${height}`. The
 *  stem is real even though the full class never appears as a literal, so the guard checks the stem. */
const DYNAMIC_STEMS = [
  "kvs-attach-", // `kvs-attach-${att.kind}` — pdf/word/excel/…
  "kvs-rows-", //   `kvs-rows-${rowHeight}`  — compact/comfortable/…
  "kvs-mark-", //   `kvs-mark-${annotation.color}` — highlight colour on a rendered <mark>
];

/**
 * Pre-existing dead selectors, inherited from earlier phases — rules whose markup was removed or renamed
 * long before this guard existed, which is precisely why they went unnoticed. They are listed here, not
 * silently skipped, so the debt is *visible*: the guard ships green and protects against **new** rot
 * today, and this list is the honest, itemised backlog of old rot to delete. Shrinking it to empty is a
 * standalone cleanup, deliberately kept out of the mobile/responsive work so that work stays reviewable.
 *
 * The rule for this list: nothing new goes in it. A dead selector introduced from now on is a test
 * failure to fix at the source, not an entry to add here.
 */
const KNOWN_DEAD_DEBT = new Set<string>([
  "kvs-tag", // bare; DOM only emits kvs-tag-token / kvs-tag-link / …
  "kvs-dashboard-header",
  "kvs-settings-profile",
  "kvs-settings-column",
  "kvs-settings-advanced",
  "kvs-settings-advanced-summary",
  "kvs-select-cell",
  "kvs-source-cell",
  "kvs-tb-right",
  "kvs-view-select",
  "kvs-layout-select",
  "kvs-folder-setting",
  "kvs-folder-chip-x",
  "kvs-folder-menu-item",
  "kvs-cards-card",
  "kvs-hl-flash",
  "kvs-hl-flash-layer",
  "kvs-sv-filterlabel",
  "kvs-editor-hint",
]);

/** A handful of classes are toggled by Obsidian or by state, not created by us; not the guard's concern. */
const ALLOWED_ORPHANS = new Set<string>([
  "kvs-icon-btn", // legacy rule kept for an older markup path; harmless, slated for cleanup
]);

describe("stylesheet selectors match the DOM (the gate tsc/eslint/vitest cannot provide)", () => {
  const css = readFileSync(join(root, "styles.css"), "utf8");
  const source = readAllSource(join(root, "src"));

  const cssClasses = [...new Set(css.match(/\.kvs-[a-z0-9-]+/g) ?? [])].map((c) => c.slice(1));
  const emitted = new Set(source.match(/kvs-[a-z0-9-]+/g) ?? []);

  const isReal = (cls: string): boolean =>
    emitted.has(cls) ||
    ALLOWED_ORPHANS.has(cls) ||
    KNOWN_DEAD_DEBT.has(cls) ||
    DYNAMIC_STEMS.some((stem) => cls.startsWith(stem));

  it("no NEW dead selectors — the debt list may shrink but must never grow", () => {
    const dead = cssClasses.filter((c) => !isReal(c));
    expect(dead, `dead selectors (in styles.css, never created by src): ${dead.join(", ")}`).toEqual([]);
  });

  it("the known-dead-debt list stays honest — every entry is genuinely still dead", () => {
    // If a debt entry has quietly become real again (its markup came back), drop it from the list rather
    // than carry a lie. This keeps the backlog from rotting in the other direction.
    const resurrected = [...KNOWN_DEAD_DEBT].filter((c) => emitted.has(c));
    expect(resurrected, `remove these from KNOWN_DEAD_DEBT — they exist again: ${resurrected.join(", ")}`).toEqual([]);
  });

  it("the container-query contexts are actually established on elements", () => {
    // The whole of Phase 114 is inert if these two classes are not on the DOM, and container queries fail
    // *silently* (they just behave like the container is the whole page). Worth its own assertion.
    expect(emitted.has("kvs-cq-root")).toBe(true);
    expect(emitted.has("kvs-cq-view")).toBe(true);
  });
});
