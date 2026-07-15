import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Phase 116 pulled the academic-research kit (~540 lines of DOI capture/fill, dedup, citation linking,
 * library sharding, reference import) out of the 3,130-line `DashboardView` god object into a standalone
 * `AcademicController`, reached only through a narrow `AcademicHost` interface. The value of that split
 * is entirely in the boundary holding: if the controller starts importing the view, or the view starts
 * reaching back into the kit's internals, the god object quietly reassembles and the refactor was for
 * nothing. These assertions make the boundary a tested invariant rather than a hope.
 */

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (rel: string): string => readFileSync(join(root, "src/workspace", rel), "utf8");

describe("the academic controller boundary holds", () => {
  const controller = read("academic-controller.ts");
  const view = read("dashboard-view.ts");

  it("the controller never imports the view (dependency points one way only)", () => {
    expect(controller).not.toMatch(/from ["'].*dashboard-view["']/);
  });

  it("the controller never reaches into Obsidian view internals — it talks only to its host", () => {
    // If any of these appear, the kit has grown a second, hidden coupling to the view's guts, defeating
    // the point of the host interface.
    for (const forbidden of ["TextFileView", "WorkspaceLeaf", "this.leaf", "this.contentEl"]) {
      expect(controller, `controller should not reference ${forbidden}`).not.toContain(forbidden);
    }
  });

  it("everything the controller needs from the view goes through this.host", () => {
    // The kit reads live view state (profiles, rows, search) and the shared write path only via the host.
    // A bare `this.<view-member>` that isn't a controller-owned method or `this.host.` would be leakage.
    // Spot-check the members that used to be direct view fields.
    for (const hostOnly of ["this.renderedProfile", "this.currentProfile", "this.lastRows", "this.search"]) {
      // These may appear only as `this.host.renderedProfile()` etc., never bare.
      const bare = new RegExp(`(?<!host\\.)${hostOnly.replace(".", "\\.")}\\b`);
      expect(controller, `${hostOnly} must be reached via this.host, not directly`).not.toMatch(bare);
    }
  });

  it("the view delegates the public academic commands rather than reimplementing them", () => {
    // main.ts calls these on the view; each must forward to the controller. If the body grew back into a
    // real implementation, this catches it (a delegator is a single `return this.academic.X(...)`).
    for (const cmd of [
      "bulkFillFromDoi",
      "captureByDoi",
      "findDuplicateDois",
      "findCitationLinks",
      "importReferences",
      "openShardModal",
    ]) {
      expect(view, `${cmd} should delegate to this.academic`).toMatch(
        new RegExp(`${cmd}\\([^)]*\\)[^{]*\\{\\s*return this\\.academic\\.${cmd}\\(`),
      );
    }
  });
});
