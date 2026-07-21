import { describe, it, expect } from "vitest";
import { extractFields, findDoi, type PageSnapshot } from "../shared/extract";
import {
  enqueue,
  enqueueUnique,
  next,
  remove,
  markFailed,
  prune,
  retryDelayMs,
  isDuplicateOf,
  MAX_ATTEMPTS,
  MAX_AGE_MS,
  MAX_QUEUED,
  type QueuedCapture,
} from "../shared/queue";
import type { CaptureRequest } from "../shared/protocol";

const page = (patch: Partial<PageSnapshot> = {}): PageSnapshot => ({
  url: "https://example.com/article",
  ...patch,
});

const valueFor = (fields: readonly { key: string; value: string }[], key: string): string | undefined =>
  fields.find((f) => f.key === key)?.value;

describe("extract · meta tags", () => {
  it("lifts the OpenGraph tags a page actually publishes", () => {
    const fields = extractFields(
      page({
        meta: [
          { key: "og:title", content: "A Headline" },
          { key: "og:description", content: "Some summary" },
          { key: "article:published_time", content: "2026-07-18T09:00:00Z" },
        ],
      }),
    );
    expect(valueFor(fields, "og:title")).toBe("A Headline");
    expect(valueFor(fields, "og:article:published_time")).toBe("2026-07-18T09:00:00Z");
  });

  it("matches meta keys case-insensitively, since pages are inconsistent", () => {
    const fields = extractFields(page({ meta: [{ key: "OG:TITLE", content: "Shouty" }] }));
    expect(valueFor(fields, "og:title")).toBe("Shouty");
  });

  it("keeps academic citation tags, which is what a paper page offers", () => {
    const fields = extractFields(
      page({
        meta: [
          { key: "citation_title", content: "On Something" },
          { key: "citation_doi", content: "10.1000/abc123" },
        ],
      }),
    );
    expect(valueFor(fields, "citation_title")).toBe("On Something");
    expect(valueFor(fields, "citation_doi")).toBe("10.1000/abc123");
  });

  it("ignores meta tags that mean nothing to us", () => {
    const fields = extractFields(page({ meta: [{ key: "viewport", content: "width=device-width" }] }));
    expect(fields.some((f) => f.value.includes("device-width"))).toBe(false);
  });

  it("always records the page url", () => {
    expect(valueFor(extractFields(page()), "url")).toBe("https://example.com/article");
  });

  it("collapses whitespace and drops empty values", () => {
    const fields = extractFields(page({ meta: [{ key: "description", content: "  a   b  " }, { key: "author", content: "   " }] }));
    expect(valueFor(fields, "description")).toBe("a b");
    expect(valueFor(fields, "author")).toBeUndefined();
  });
});

describe("extract · JSON-LD", () => {
  it("reads a plain Schema.org object", () => {
    const fields = extractFields(
      page({ jsonLd: [{ "@type": "Article", name: "A Paper", datePublished: "2026-01-02" }] }),
    );
    expect(valueFor(fields, "schema:name")).toBe("A Paper");
    expect(valueFor(fields, "schema:datepublished")).toBe("2026-01-02");
  });

  it("flattens an author given as an object, which is how most sites publish it", () => {
    const fields = extractFields(page({ jsonLd: [{ author: { "@type": "Person", name: "Ada Lovelace" } }] }));
    expect(valueFor(fields, "schema:author")).toBe("Ada Lovelace");
  });

  it("joins a list of authors", () => {
    const fields = extractFields(
      page({ jsonLd: [{ author: [{ name: "Ada" }, { name: "Grace" }] }] }),
    );
    expect(valueFor(fields, "schema:author")).toBe("Ada, Grace");
  });

  it("looks inside an @graph container", () => {
    const fields = extractFields(page({ jsonLd: [{ "@graph": [{ "@type": "Article", headline: "Buried" }] }] }));
    expect(valueFor(fields, "schema:headline")).toBe("Buried");
  });

  it("survives nonsense without throwing", () => {
    expect(() => extractFields(page({ jsonLd: [null, 42, "text", { author: () => 1 }] }))).not.toThrow();
  });
});

describe("extract · precedence", () => {
  it("prefers a user's selection for the description, since they chose it", () => {
    const fields = extractFields(
      page({
        selection: "the part I highlighted",
        meta: [{ key: "og:description", content: "the generic blurb" }],
        excerpt: "the first paragraph",
      }),
    );
    expect(valueFor(fields, "description")).toBe("the part I highlighted");
  });

  it("prefers Schema.org over a generic meta tag for the same idea", () => {
    // Both are present; the structured one is the more deliberate statement.
    const fields = extractFields(
      page({
        jsonLd: [{ datePublished: "2026-01-02" }],
        meta: [{ key: "dc.date", content: "2020-01-01" }],
      }),
    );
    expect(valueFor(fields, "schema:datepublished")).toBe("2026-01-02");
  });

  it("never records the same key twice", () => {
    const fields = extractFields(
      page({ meta: [{ key: "og:title", content: "First" }, { key: "og:title", content: "Second" }] }),
    );
    expect(fields.filter((f) => f.key === "og:title")).toHaveLength(1);
    expect(valueFor(fields, "og:title")).toBe("First");
  });
});

describe("extract · findDoi", () => {
  it("finds a DOI in the page url", () => {
    const p = page({ url: "https://doi.org/10.1000/abc123" });
    expect(findDoi(p, extractFields(p))).toBe("10.1000/abc123");
  });

  it("finds a DOI in a citation tag", () => {
    const p = page({ meta: [{ key: "citation_doi", content: "10.1145/1234.5678" }] });
    expect(findDoi(p, extractFields(p))).toBe("10.1145/1234.5678");
  });

  it("trims trailing punctuation a sentence would leave behind", () => {
    const p = page({ meta: [{ key: "description", content: "see 10.1000/abc123." }] });
    expect(findDoi(p, extractFields(p))).toBe("10.1000/abc123");
  });

  it("returns null when there is no DOI", () => {
    const p = page();
    expect(findDoi(p, extractFields(p))).toBeNull();
  });
});

describe("queue · holding captures the bridge couldn't take", () => {
  const request = (viewId = "papers", url = "https://example.com/a"): CaptureRequest => ({
    viewId,
    fields: [{ key: "title", value: "A" }],
    url,
  });

  it("queues in the order things were captured", () => {
    let q = enqueue([], request("papers", "https://a"), 1, "1");
    q = enqueue(q, request("papers", "https://b"), 2, "2");
    expect(q.map((e) => e.id)).toEqual(["1", "2"]);
  });

  it("hands back the oldest capture still worth trying", () => {
    const q = enqueue(enqueue([], request(), 1, "1"), request("papers", "https://b"), 2, "2");
    expect(next(q)?.id).toBe("1");
  });

  it("skips one that has already failed too often", () => {
    let q: QueuedCapture[] = enqueue([], request(), 1, "1");
    for (let i = 0; i < MAX_ATTEMPTS; i++) q = markFailed(q, "1", "offline");
    expect(next(q)).toBeNull();
  });

  it("records why an attempt failed, so the person can be told", () => {
    const q = markFailed(enqueue([], request(), 1, "1"), "1", "connection refused");
    expect(q[0]?.attempts).toBe(1);
    expect(q[0]?.lastError).toBe("connection refused");
  });

  it("removes a capture once it lands", () => {
    expect(remove(enqueue([], request(), 1, "1"), "1")).toHaveLength(0);
  });

  it("doesn't stack the same page waiting for the same view", () => {
    const q = enqueueUnique(enqueueUnique([], request(), 1, "1"), request(), 2, "2");
    expect(q).toHaveLength(1);
  });

  it("does allow the same page to go to two different views", () => {
    const q = enqueueUnique(enqueueUnique([], request("papers"), 1, "1"), request("reading"), 2, "2");
    expect(q).toHaveLength(2);
  });

  it("treats captures with no url as distinct unless their fields match", () => {
    const a: CaptureRequest = { viewId: "v", fields: [{ key: "title", value: "A" }] };
    const b: CaptureRequest = { viewId: "v", fields: [{ key: "title", value: "B" }] };
    expect(isDuplicateOf(a, a)).toBe(true);
    expect(isDuplicateOf(a, b)).toBe(false);
  });

  it("caps how much it will hold through a long offline stretch", () => {
    let q: QueuedCapture[] = [];
    for (let i = 0; i < MAX_QUEUED + 25; i++) q = enqueue(q, request("v", `https://x/${i}`), i, String(i));
    expect(q).toHaveLength(MAX_QUEUED);
    // The most recent survive: an old capture matters less than one just made.
    expect(q[q.length - 1]?.id).toBe(String(MAX_QUEUED + 24));
  });

  it("gives up on what's too old or tried too often, and says what it dropped", () => {
    let q = enqueue([], request(), 0, "old");
    q = enqueue(q, request("v", "https://fresh"), MAX_AGE_MS, "fresh");
    for (let i = 0; i < MAX_ATTEMPTS; i++) q = markFailed(q, "fresh", "x");
    const { kept, dropped } = prune(q, MAX_AGE_MS + 1);
    expect(kept).toHaveLength(0);
    expect(dropped.map((d) => d.id).sort()).toEqual(["fresh", "old"]);
  });

  it("keeps a recent capture that has only failed once", () => {
    const q = markFailed(enqueue([], request(), 1000, "1"), "1", "offline");
    expect(prune(q, 2000).kept).toHaveLength(1);
  });

  it("backs off between attempts but stays bounded", () => {
    expect(retryDelayMs(0)).toBeLessThan(retryDelayMs(1));
    expect(retryDelayMs(1)).toBeLessThan(retryDelayMs(3));
    // "Obsidian isn't open yet" resolves in minutes, so the wait must never stretch into hours.
    expect(retryDelayMs(99)).toBeLessThanOrEqual(10 * 60 * 1000);
  });
});

describe("extract · canonical fields for academic capture", () => {
  it("emits a canonical doi from citation metadata, so a DOI column fills itself", () => {
    const fields = extractFields(
      page({ meta: [{ key: "citation_doi", content: "10.1039/D0EY00001A" }] }),
    );
    expect(valueFor(fields, "doi")).toBe("10.1039/D0EY00001A");
  });

  it("finds a DOI embedded in the URL when the page declares none in metadata", () => {
    const fields = extractFields(page({ url: "https://doi.org/10.1234/abcd.5678" }));
    expect(valueFor(fields, "doi")).toBe("10.1234/abcd.5678");
  });

  it("emits canonical published and author from whatever vocabulary the page used", () => {
    const fields = extractFields(
      page({
        meta: [
          { key: "citation_publication_date", content: "2024-05-01" },
          { key: "citation_author", content: "A. Researcher" },
        ],
      }),
    );
    expect(valueFor(fields, "published")).toBe("2024-05-01");
    expect(valueFor(fields, "author")).toBe("A. Researcher");
  });

  it("adds no canonical fields when the page has none of them", () => {
    const fields = extractFields(page({ meta: [{ key: "og:title", content: "T" }] }));
    expect(valueFor(fields, "doi")).toBeUndefined();
    expect(valueFor(fields, "published")).toBeUndefined();
  });
});
