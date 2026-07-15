import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * chart.js is ~190 KB and only the chart view uses it. Phase 115 moved it off the plugin's startup path
 * by loading it with a dynamic `import()` inside the view's `prepare()` hook, instead of a static import
 * at module scope. Confirmed empirically that esbuild — even producing a single CJS bundle, where the
 * bytes stay inline — wraps a dynamically-imported module in a lazy init function, so chart.js does not
 * *execute* until the first chart renders.
 *
 * That deferral is one keystroke from silent reversal: a single `import { Chart } from "chart.js"` added
 * anywhere would pull the whole library back onto startup, and no other gate would notice (it would still
 * typecheck, test, build, and lint). This is that gate.
 *
 * These are source-level assertions rather than runtime ones: importing the view registry here would drag
 * in the entire Obsidian-dependent view layer (Menu, MarkdownRenderer, AbstractInputSuggest, …), far more
 * mock surface than the property is worth. Reading the source proves the same thing without that cost.
 */

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function readAllSource(dir: string): { file: string; text: string }[] {
  const out: { file: string; text: string }[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...readAllSource(full));
    else if (entry.endsWith(".ts")) out.push({ file: full, text: readFileSync(full, "utf8") });
  }
  return out;
}

describe("chart.js stays off the startup path", () => {
  const sources = readAllSource(join(root, "src"));

  it("is never statically imported — only via dynamic import() or type-only import", () => {
    const offenders: string[] = [];
    for (const { file, text } of sources) {
      for (const line of text.split("\n")) {
        if (!line.includes('"chart.js"')) continue;
        const dynamic = line.includes('import("chart.js")') || line.includes('typeof import("chart.js")');
        const typeOnly = line.trimStart().startsWith("import type");
        if (!dynamic && !typeOnly) offenders.push(`${file.replace(root, "")}: ${line.trim()}`);
      }
    }
    expect(
      offenders,
      `chart.js must load lazily; these static imports would drag it onto startup:\n${offenders.join("\n")}`,
    ).toEqual([]);
  });

  it("the chart view wires prepare() to its lazy loader", () => {
    const chartView = sources.find((s) => s.file.endsWith("chart/chart-view.ts"));
    expect(chartView, "chart-view.ts should exist").toBeDefined();
    // The hook is what renderProfile awaits before render(); loadChart is what pulls chart.js in.
    expect(chartView!.text).toMatch(/prepare:\s*loadChart/);
    expect(chartView!.text).toMatch(/await import\("chart\.js"\)/);
  });

  it("renderProfile awaits prepare() before rendering", () => {
    const rp = sources.find((s) => s.file.endsWith("render-profile.ts"));
    expect(rp, "render-profile.ts should exist").toBeDefined();
    // Without this await, a lazy view would render before its dependency finished loading.
    expect(rp!.text).toMatch(/if\s*\(view\.prepare\)\s*await view\.prepare\(\)/);
  });
});
