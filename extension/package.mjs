import { execFileSync } from "child_process";
import { existsSync, mkdirSync, rmSync } from "fs";
import { dirname, resolve } from "path";
import { fileURLToPath } from "url";
import { readFileSync } from "fs";

/**
 * Produce the archives the two stores actually want.
 *
 * Chrome takes the built extension. Firefox takes that too, but AMO additionally requires the *source* when
 * the submitted code has been bundled or minified — which ours has — so reviewers can reproduce the build.
 * Both are made here so there's no question later about which zip was uploaded where.
 */

const here = dirname(fileURLToPath(import.meta.url));
const from = (p) => resolve(here, p);
const root = resolve(here, "..");

const dist = from("dist");
if (!existsSync(dist)) {
  console.error("Build first: npm run ext:build");
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(from("manifest.json"), "utf8"));
const version = manifest.version;
const out = from("packages");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const zip = (cwd, target, args) =>
  execFileSync("zip", ["-q", "-r", target, ...args], { cwd, stdio: "inherit" });

// 1. The extension itself, for both stores.
const built = `${out}/kvs-companion-${version}.zip`;
zip(dist, built, ["."]);

// 2. Source, for AMO's reviewers: everything needed to reproduce the build, and nothing else.
const source = `${out}/kvs-companion-${version}-source.zip`;
zip(root, source, [
  "extension/src",
  "extension/icons",
  "extension/manifest.json",
  "extension/popup.html",
  "extension/sidebar.html",
  "extension/options.html",
  "extension/welcome.html",
  "extension/style.css",
  "extension/build.mjs",
  "extension/tsconfig.json",
  "extension/README.md",
  "shared",
  "package.json",
  "package-lock.json",
  "-x",
  "*/node_modules/*",
]);

console.log(`\nReady to upload, both in extension/packages:\n  ${built}\n  ${source}   (Firefox only — AMO asks for source)\n`);
