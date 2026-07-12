import { describe, it, expect, vi } from "vitest";
import { debounce } from "../src/util/debounce";
import { Emitter } from "../src/util/emitter";

describe("debounce", () => {
  it("collapses rapid calls into a single trailing invocation", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const d = debounce(fn, 100);
    d();
    d();
    d();
    expect(fn).not.toHaveBeenCalled();
    expect(d.isPending()).toBe(true);
    vi.advanceTimersByTime(150);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(d.isPending()).toBe(false);
    vi.useRealTimers();
  });

  it("flush runs immediately, cancel discards", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const d = debounce(fn, 100);
    d();
    d.flush();
    expect(fn).toHaveBeenCalledTimes(1);
    d();
    d.cancel();
    vi.advanceTimersByTime(200);
    expect(fn).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});

describe("Emitter", () => {
  it("notifies listeners and supports unsubscribe", () => {
    const emitter = new Emitter<number>();
    const seen: number[] = [];
    const off = emitter.on((n) => seen.push(n));
    emitter.emit(1);
    off();
    emitter.emit(2);
    expect(seen).toEqual([1]);
    expect(emitter.size).toBe(0);
  });
});
