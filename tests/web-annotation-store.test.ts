import { describe, it, expect } from "vitest";
import { parseStore, serializeStore } from "../src/services/web-annotations/web-annotation-service";
import type { PageAnnotations, StoredAnnotation } from "../shared/annotations";

const ann = (id: string): StoredAnnotation => ({
  id,
  url: "https://example.com/a",
  anchor: { exact: "quoted text" },
  color: "yellow",
  style: "highlight",
  createdAt: "2026-07-20T00:00:00.000Z",
});

describe("web annotations · the sidecar file", () => {
  it("round-trips through serialize and parse", () => {
    const store = new Map<string, PageAnnotations>([
      ["https://example.com/a", { url: "https://example.com/a", annotations: [ann("one"), ann("two")] }],
    ]);
    const back = parseStore(serializeStore(store));
    expect(back.get("https://example.com/a")?.annotations).toHaveLength(2);
  });

  it("survives a corrupt file with an empty store, not a broken vault", () => {
    expect(parseStore("{not json").size).toBe(0);
    expect(parseStore("null").size).toBe(0);
    expect(parseStore("").size).toBe(0);
  });

  it("drops malformed entries and keeps the rest of the page's highlights", () => {
    const raw = JSON.stringify({
      "https://x/a": { annotations: [ann("good"), { id: "bad" }, 7, null] },
    });
    expect(parseStore(raw).get("https://x/a")?.annotations).toHaveLength(1);
  });

  it("doesn't write pages whose last annotation was removed", () => {
    const store = new Map<string, PageAnnotations>([
      ["https://x/a", { url: "https://x/a", annotations: [] }],
      ["https://x/b", { url: "https://x/b", annotations: [ann("kept")] }],
    ]);
    const parsed = parseStore(serializeStore(store));
    expect(parsed.has("https://x/a")).toBe(false);
    expect(parsed.has("https://x/b")).toBe(true);
  });
});
