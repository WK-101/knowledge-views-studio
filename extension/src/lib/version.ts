/**
 * Whether the vault's plugin is new enough for this companion.
 *
 * The two halves ship separately — the extension from a zip, the plugin from a release — and nothing
 * guaranteed they matched. The failure mode was every new endpoint returning "not found", which surfaced
 * as highlights silently vanishing and captures behaving by years-old rules: three sessions of fixes that
 * never ran, invisible precisely because nothing compared versions and said so.
 */

/**
 * The oldest plugin this companion fully works with.
 *
 * 0.164.0, not 0.162.0 as first shipped: version reporting itself only works from 0.164 (the 0.162 wiring
 * silently never ran), so requiring anything older names a version that cannot satisfy the check.
 */
export const REQUIRED_PLUGIN_VERSION = "0.179.0";

function triple(version: string): [number, number, number] | null {
  const match = /^(\d+)\.(\d+)\.(\d+)/.exec(version.trim());
  if (match === null) return null;
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

/** True when the reported version meets the requirement. Missing or unparseable reads as too old. */
export function pluginIsCurrent(reported: string | undefined, required = REQUIRED_PLUGIN_VERSION): boolean {
  const have = triple(reported ?? "");
  const want = triple(required);
  if (have === null || want === null) return false;
  for (let i = 0; i < 3; i++) {
    const a = have[i] ?? 0;
    const b = want[i] ?? 0;
    if (a !== b) return a > b;
  }
  return true;
}

/** The sentence shown when the plugin is behind — one place, so every surface says the same thing. */
export function outdatedPluginMessage(reported: string | undefined): string {
  const have = reported === undefined || reported === "" ? "an older version" : `version ${reported}`;
  return `Your vault is running ${have} of Knowledge Views Studio, but this companion needs ${REQUIRED_PLUGIN_VERSION} or newer. Update the plugin in Obsidian, then reload it — until then, captures and highlights will misbehave in confusing ways.`;
}
