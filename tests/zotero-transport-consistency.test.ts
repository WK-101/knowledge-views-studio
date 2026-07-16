import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const controllerSrc = readFileSync(resolve(here, "../src/workspace/academic-controller.ts"), "utf8");

/**
 * Regression guard for a real bug: "Fill details from Zotero" reported Zotero unreachable even when it was
 * running and the library view worked. The cause was the transport — the controller talked to Zotero's
 * *local* API through Obsidian's `requestUrl`, which rejects the local server's responses, while the
 * working library view used the Node-http `createZoteroFetcher`. Two code paths, two transports, one of
 * them broken.
 *
 * The fix routes the controller's Zotero access through the same `createZoteroFetcher` the rest of the
 * Zotero code uses. This test pins that: the controller must build its Zotero fetcher from
 * createZoteroFetcher, and must not construct a requestUrl-based fetcher for the local API. (requestUrl is
 * still fine for the Crossref DOI lookup, which is a normal HTTPS endpoint.)
 */
describe("academic controller Zotero transport", () => {
  it("uses the shared createZoteroFetcher for Zotero, matching the library view", () => {
    expect(controllerSrc).toContain("createZoteroFetcher");
  });

  it("keeps requestUrl only for the Crossref DOI lookup, not the Zotero local API", () => {
    // requestUrl appears at most for the DOI (Crossref) call; never against the local API (:23119).
    const requestUrlLines = controllerSrc.split("\n").filter((l) => l.includes("requestUrl(") && !l.includes("*"));
    for (const line of requestUrlLines) {
      expect(line).not.toContain("23119");
    }
  });

  it("decides reachability from the real DOI query status, not a separate ping probe", () => {
    // The fill/promote paths must use the status-aware lookup and must NOT gate on a separate ping() to a
    // different endpoint — that endpoint mismatch is what falsely reported Zotero unreachable.
    expect(controllerSrc).toContain("zoteroDoiLookup");
    expect(controllerSrc).not.toContain("provider.ping()");
  });
});
