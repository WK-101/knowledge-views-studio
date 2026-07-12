import { readFileSync, writeFileSync } from "node:fs";
import process from "node:process";

const targetVersion = process.env.npm_package_version;
if (!targetVersion) {
  console.error("npm_package_version is not set. Run this via `npm version <x.y.z>`.");
  process.exit(1);
}

const manifest = JSON.parse(readFileSync("manifest.json", "utf8"));
const { minAppVersion } = manifest;
manifest.version = targetVersion;
writeFileSync("manifest.json", JSON.stringify(manifest, null, "\t") + "\n");

const versions = JSON.parse(readFileSync("versions.json", "utf8"));
versions[targetVersion] = minAppVersion;
writeFileSync("versions.json", JSON.stringify(versions, null, "\t") + "\n");

console.log(`Bumped manifest + versions.json to ${targetVersion} (minAppVersion ${minAppVersion}).`);
