import esbuild from "esbuild";
import process from "node:process";
import { builtinModules as builtins } from "node:module";
import { readFileSync, copyFileSync } from "node:fs";

const manifest = JSON.parse(readFileSync("manifest.json", "utf8"));
const production = process.argv.includes("production");

// Bundle the pdf.js worker as a text asset so the annotation parser can spin it up from a blob URL
// (no separate worker file to ship). Regenerated on every build from the installed pdfjs-dist.
copyFileSync("node_modules/pdfjs-dist/legacy/build/pdf.worker.min.mjs", "src/services/annotations/pdf.worker.txt");

const banner = `/*
 * ${manifest.name} v${manifest.version}
 * ${manifest.description}
 * Bundled with esbuild — edit files in src/ instead.
 */`;

const context = await esbuild.context({
  entryPoints: ["src/main.ts"],
  bundle: true,
  format: "cjs",
  target: "es2018",
  platform: "browser",
  logLevel: "info",
  sourcemap: production ? false : "inline",
  treeShaking: true,
  minify: production,
  outfile: "main.js",
  loader: { ".txt": "text" },
  banner: { js: banner },
  external: [
    "obsidian",
    "electron",
    "@codemirror/autocomplete",
    "@codemirror/collab",
    "@codemirror/commands",
    "@codemirror/language",
    "@codemirror/lint",
    "@codemirror/search",
    "@codemirror/state",
    "@codemirror/view",
    "@lezer/common",
    "@lezer/highlight",
    "@lezer/lr",
    ...builtins,
  ],
});

if (production) {
  await context.rebuild();
  await context.dispose();
  console.log("Production build complete.");
} else {
  await context.watch();
  console.log("Watching for changes...");
}
