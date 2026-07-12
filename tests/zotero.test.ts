import { describe, it, expect } from "vitest";
import { parseZoteroAnnotations, zoteroKeysFromAttachments, zoteroDeepLink } from "../src/services/annotations/zotero";
import { fetchZoteroAnnotations } from "../src/services/annotations/zotero-client";
import type { ZoteroItem } from "../src/services/annotations/zotero";

const anno = (over: Record<string, unknown>): ZoteroItem => ({
  key: "AN1",
  data: {
    itemType: "annotation",
    annotationType: "highlight",
    annotationText: "self-attention",
    annotationComment: "key",
    annotationColor: "#ffd400",
    annotationPageLabel: "3",
    annotationPosition: JSON.stringify({ pageIndex: 2, rects: [[10, 700, 200, 712]] }),
    ...over,
  },
});

describe("Zotero annotation parsing", () => {
  it("maps type, page (pageIndex+1), colour, text, comment, rects", () => {
    const [a] = parseZoteroAnnotations([anno({})], "zotero:ATT");
    expect(a!.kind).toBe("highlight");
    expect(a!.page).toBe(3); // pageIndex 2 → page 3
    expect(a!.text).toBe("self-attention");
    expect(a!.comment).toBe("key");
    expect(a!.color).toBe("#ffd400");
    expect(a!.source).toBe("zotero");
    expect(a!.rects[0]).toMatchObject({ x0: 10, y0: 700, x1: 200, y1: 712 });
  });

  it("ignores non-annotation items and maps note/underline types", () => {
    const items: ZoteroItem[] = [
      { data: { itemType: "attachment" } },
      anno({ annotationType: "note", annotationText: "" }),
      anno({ annotationType: "underline" }),
    ];
    const parsed = parseZoteroAnnotations(items, "zotero:ATT");
    expect(parsed).toHaveLength(2);
    expect(parsed.map((p) => p.kind).sort()).toEqual(["note", "underline"]);
  });

  it("extracts keys from zotero:// attachment links", () => {
    const atts = [
      { target: "zotero://open-pdf/library/items/ABCD1234", isLink: false, kind: "web" as const },
      { target: "zotero://select/library/items/WXYZ7890", isLink: false, kind: "web" as const },
      { target: "[[local.pdf]]", isLink: true, kind: "pdf" as const },
    ];
    expect(zoteroKeysFromAttachments(atts).sort()).toEqual(["ABCD1234", "WXYZ7890"]);
  });

  it("builds a Zotero deep link", () => {
    expect(zoteroDeepLink("ATT", 5, "AN1")).toBe("zotero://open-pdf/library/items/ATT?page=5&annotation=AN1");
  });
});


import { findZoteroKeysByDoi, testZoteroConnection } from "../src/services/annotations/zotero-client";
import { vi as vitest } from "vitest";

describe("Zotero DOI matching + diagnostics", () => {
  it("finds item keys whose DOI matches (normalising URL forms)", async () => {
    const fetcher = vitest.fn(async (url: string) => {
      if (url.includes("/items?q=")) {
        return {
          status: 200,
          json: [
            { key: "MATCH", data: { itemType: "journalArticle", DOI: "https://doi.org/10.5555/3295222" } },
            { key: "OTHER", data: { itemType: "journalArticle", DOI: "10.9999/nope" } },
          ],
        };
      }
      return { status: 404 };
    });
    const keys = await findZoteroKeysByDoi("http://127.0.0.1:23119/api/users/0", "10.5555/3295222", fetcher);
    expect(keys).toEqual(["MATCH"]);
  });

  it("gives actionable diagnostics per status", async () => {
    expect(await testZoteroConnection("http://x", async () => ({ status: 0 }))).toMatch(/running/i);
    expect(await testZoteroConnection("http://x", async () => ({ status: 403 }))).toMatch(/local API is off/i);
    expect(await testZoteroConnection("http://x", async () => ({ status: 200 }))).toMatch(/Connected/i);
  });
});

describe("Zotero client: attachment resolution + parent filtering", () => {
  it("resolves a regular item's attachments, then keeps annotations whose parentItem matches", async () => {
    const fetcher = vitest.fn(async (url: string) => {
      if (/\/items\/I1\?/.test(url)) return { status: 200, json: { key: "I1", data: { itemType: "journalArticle" } } };
      if (/\/items\/I1\/children/.test(url)) return { status: 200, json: [{ key: "A1", data: { itemType: "attachment" } }] };
      if (/itemType=annotation/.test(url)) {
        return {
          status: 200,
          json: [
            { key: "AN1", data: { itemType: "annotation", annotationType: "highlight", annotationText: "mine", parentItem: "A1", annotationPosition: JSON.stringify({ pageIndex: 0, rects: [[1, 2, 3, 4]] }) } },
            { key: "AN2", data: { itemType: "annotation", annotationType: "highlight", annotationText: "someone else", parentItem: "ZZ", annotationPosition: JSON.stringify({ pageIndex: 0, rects: [[1, 2, 3, 4]] }) } },
          ],
        };
      }
      return { status: 404 };
    });
    const out = await fetchZoteroAnnotations("http://127.0.0.1:23119/api/users/0", ["I1"], fetcher);
    expect(out).toHaveLength(1);
    expect(out[0]!.text).toBe("mine");
    expect(out[0]!.attachment).toBe("zotero:A1");
  });

  it("uses an attachment key directly (never calls /children on it)", async () => {
    const calls: string[] = [];
    const fetcher = vitest.fn(async (url: string) => {
      calls.push(url);
      if (/\/items\/A1\?/.test(url)) return { status: 200, json: { key: "A1", data: { itemType: "attachment" } } };
      if (/itemType=annotation/.test(url)) return { status: 200, json: [{ key: "AN1", data: { itemType: "annotation", annotationType: "note", annotationComment: "n", parentItem: "A1", annotationPosition: JSON.stringify({ pageIndex: 1, rects: [] }) } }] };
      return { status: 404 };
    });
    const out = await fetchZoteroAnnotations("http://x/api/users/0", ["A1"], fetcher);
    expect(out).toHaveLength(1);
    expect(calls.some((u) => /\/items\/A1\/children/.test(u))).toBe(false); // never called /children on the attachment
  });
});
