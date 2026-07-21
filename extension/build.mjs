import esbuild from "esbuild";
import { cpSync, mkdirSync, rmSync } from "fs";
import { dirname, resolve } from "path";
import { fileURLToPath } from "url";

// Resolve everything against this file, not the shell's working directory, so `npm run ext:build` from the
// repository root behaves exactly like running it from inside extension/.
const here = dirname(fileURLToPath(import.meta.url));
const from = (p) => resolve(here, p);

// Builds the companion into extension/dist, which is the folder you point "Load unpacked" at.
// Kept separate from the plugin's build on purpose: the extension ships on its own schedule, and a browser
// store review should never be able to hold up a plugin release.

const production = process.argv.includes("production");
const outdir = from("dist");

rmSync(outdir, { recursive: true, force: true });
mkdirSync(outdir, { recursive: true });

await esbuild.build({
  entryPoints: [from("src/popup.ts"), from("src/options.ts"), from("src/background.ts"), from("src/content.ts"), from("src/serp.ts"), from("src/sidebar.ts"), from("src/annotate.ts"), from("src/table-capture.ts"), from("src/welcome.ts")],
  bundle: true,
  format: "iife",
  target: "es2020",
  platform: "browser",
  minify: production,
  sourcemap: production ? false : "inline",
  outdir,
  logLevel: "info",
});

for (const file of ["manifest.json", "popup.html", "sidebar.html", "options.html", "welcome.html", "style.css"]) {
  cpSync(from(file), `${outdir}/${file}`);
}
cpSync(from("icons"), `${outdir}/icons`, { recursive: true });

console.log(`Companion built into ${outdir} — load that folder unpacked.`);
