import { describe, it, expect } from "vitest";
import { OcrQueue, type IdleScheduler } from "../src/services/search/ocr/ocr-queue";
import { OcrCache } from "../src/services/search/ocr/ocr-cache";
import type { App } from "obsidian";

// A synchronous scheduler so queue behaviour is deterministic in tests.
const sync: IdleScheduler = (fn) => fn();

describe("OcrQueue — one job at a time, priority, and drop", () => {
  it("runs queued jobs and reports size", async () => {
    const ran: string[] = [];
    const q = new OcrQueue(sync);
    q.push("a", async () => void ran.push("a"), "low");
    q.push("b", async () => void ran.push("b"), "low");
    await Promise.resolve();
    await Promise.resolve();
    expect(ran).toEqual(["a", "b"]);
    expect(q.size).toBe(0);
  });

  it("runs high-priority jobs before low ones once a slot frees", async () => {
    const ran: string[] = [];
    const deferred: (() => void)[] = [];
    const defer: IdleScheduler = (fn) => void deferred.push(fn);
    const q = new OcrQueue(defer);
    q.push("blocker", async () => void ran.push("blocker"), "low"); // takes the running slot
    q.push("low1", async () => void ran.push("low1"), "low"); // both now wait
    q.push("high1", async () => void ran.push("high1"), "high");
    while (deferred.length > 0) {
      deferred.shift()!();
      await Promise.resolve();
      await Promise.resolve();
    }
    expect(ran).toEqual(["blocker", "high1", "low1"]);
  });

  it("replaces a waiting job for the same key (newest closure wins)", async () => {
    const ran: string[] = [];
    const deferred: (() => void)[] = [];
    const defer: IdleScheduler = (fn) => void deferred.push(fn);
    const q = new OcrQueue(defer);
    q.push("blocker", async () => void ran.push("blocker"), "low");
    q.push("x", async () => void ran.push("old"), "low");
    q.push("x", async () => void ran.push("new"), "low");
    while (deferred.length > 0) {
      deferred.shift()!();
      await Promise.resolve();
      await Promise.resolve();
    }
    expect(ran).toEqual(["blocker", "new"]);
  });

  it("drops a waiting job", async () => {
    const ran: string[] = [];
    const deferred: (() => void)[] = [];
    const defer: IdleScheduler = (fn) => void deferred.push(fn);
    const q = new OcrQueue(defer);
    q.push("blocker", async () => void ran.push("blocker"), "low");
    q.push("a", async () => void ran.push("a"), "low");
    q.push("b", async () => void ran.push("b"), "low");
    q.drop("a");
    while (deferred.length > 0) {
      deferred.shift()!();
      await Promise.resolve();
      await Promise.resolve();
    }
    expect(ran).toEqual(["blocker", "b"]);
  });

  it("swallows a job that throws and keeps going", async () => {
    const ran: string[] = [];
    const q = new OcrQueue(sync);
    q.push("boom", async () => {
      throw new Error("fail");
    }, "low");
    q.push("ok", async () => void ran.push("ok"), "low");
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(ran).toEqual(["ok"]);
  });
});

describe("OcrCache — signature-keyed, persisted", () => {
  function fakeApp(store: Map<string, string>): App {
    return {
      vault: {
        adapter: {
          exists: async (p: string) => store.has(p),
          read: async (p: string) => store.get(p) ?? "",
          write: async (p: string, data: string) => void store.set(p, data),
        },
      },
    } as unknown as App;
  }

  it("returns cached text only when the signature matches", async () => {
    const cache = new OcrCache(fakeApp(new Map()), "dir");
    cache.set("a.png", { mtime: 10, size: 100, langs: "eng", text: "hello" });
    expect(cache.get("a.png", 10, 100, "eng")).toBe("hello");
    expect(cache.get("a.png", 11, 100, "eng")).toBeNull(); // mtime changed → re-OCR
    expect(cache.get("a.png", 10, 100, "eng+deu")).toBeNull(); // langs changed
    expect(cache.get("b.png", 10, 100, "eng")).toBeNull(); // unknown
  });

  it("persists and reloads through the vault adapter", async () => {
    const store = new Map<string, string>();
    const c1 = new OcrCache(fakeApp(store), "dir");
    c1.set("a.png", { mtime: 1, size: 2, langs: "eng", text: "cached text" });
    await c1.save();
    const c2 = new OcrCache(fakeApp(store), "dir");
    await c2.load();
    expect(c2.get("a.png", 1, 2, "eng")).toBe("cached text");
  });

  it("renames and removes entries", async () => {
    const cache = new OcrCache(fakeApp(new Map()), "dir");
    cache.set("old.png", { mtime: 1, size: 1, langs: "eng", text: "t" });
    cache.rename("old.png", "new.png");
    expect(cache.get("old.png", 1, 1, "eng")).toBeNull();
    expect(cache.get("new.png", 1, 1, "eng")).toBe("t");
    cache.remove("new.png");
    expect(cache.get("new.png", 1, 1, "eng")).toBeNull();
  });
});
