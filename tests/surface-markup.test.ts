import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

/**
 * The two surfaces are driven by one script, so what that script reaches for has to be present on both.
 *
 * This exists because it wasn't. The tab wiring registered a dashboard panel unconditionally while only the
 * sidebar had one, which put a null in the panel map — and the first tab click threw as soon as the loop
 * reached it. Everything registered after that point, Search included, silently never appeared. Typechecking
 * couldn't catch it: the element was cast as present.
 */

const read = (name: string): string =>
  readFileSync(resolve(__dirname, "..", "extension", name), "utf8");

const popup = read("popup.html");
const sidebar = read("sidebar.html");
const surface = readFileSync(resolve(__dirname, "..", "extension", "src", "lib", "surface.ts"), "utf8");

const idsIn = (html: string): Set<string> =>
  new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1] ?? ""));

const tabsIn = (html: string): string[] =>
  [...html.matchAll(/data-tab="([^"]+)"/g)].map((m) => m[1] ?? "");

describe("surface markup · both pages carry what the shared script needs", () => {
  const core = ["app", "form", "view", "save", "controls", "status", "panels", "settings"];

  it("the popup has every core element", () => {
    const ids = idsIn(popup);
    for (const id of core) expect(ids.has(id), `popup is missing #${id}`).toBe(true);
  });

  it("the sidebar has every core element", () => {
    const ids = idsIn(sidebar);
    for (const id of core) expect(ids.has(id), `sidebar is missing #${id}`).toBe(true);
  });

  it("every tab button has the panel it switches to, on the same page", () => {
    for (const [name, html] of [["popup", popup], ["sidebar", sidebar]] as const) {
      const ids = idsIn(html);
      for (const tab of tabsIn(html)) {
        expect(ids.has(`tab-${tab}`), `${name}: tab "${tab}" has no #tab-${tab}`).toBe(true);
      }
    }
  });

  it("every panel has a button that reaches it", () => {
    for (const [name, html] of [["popup", popup], ["sidebar", sidebar]] as const) {
      const tabs = new Set(tabsIn(html));
      for (const id of idsIn(html)) {
        if (!id.startsWith("tab-")) continue;
        expect(tabs.has(id.slice(4)), `${name}: #${id} is unreachable`).toBe(true);
      }
    }
  });

  it("the dashboard is the sidebar's alone, and the popup doesn't pretend otherwise", () => {
    expect(idsIn(sidebar).has("tab-dashboard")).toBe(true);
    expect(idsIn(popup).has("tab-dashboard")).toBe(false);
  });

  it("the shared script tolerates a panel that isn't on this page", () => {
    // The specific defect: a missing element must be absent from the map, never stored as null.
    expect(surface).toContain("if (panel !== null) panels.set(name, panel)");
  });

  it("the whole-page tab exists on both, since keeping an article isn't a sidebar luxury", () => {
    expect(idsIn(popup).has("tab-note")).toBe(true);
    expect(idsIn(sidebar).has("tab-note")).toBe(true);
  });
});
