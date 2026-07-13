import type { Profile } from "../profile/profile";

/**
 * A view's display configuration, embedded as an HTML comment in Markdown exports
 * so that re-importing the file restores the columns, types, layout, sort, and
 * derived columns automatically — no re-configuring by hand.
 */
const MARKER = /<!--\s*kvs:view\s+([A-Za-z0-9+/=]+)\s*-->/;

function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function fromBase64(b64: string): string {
  const binary = atob(b64);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** The portable, display-relevant slice of a profile (identity + scope are re-derived on import). */
export type EmbeddedView = Omit<Profile, "id" | "name" | "scope">;

/** Build the `<!-- kvs:view … -->` comment that encodes a profile's view settings. */
export function embedViewComment(profile: Profile): string {
  const { id: _id, name: _name, scope: _scope, ...rest } = profile;
  void _id;
  void _name;
  void _scope;
  return `<!-- kvs:view ${toBase64(JSON.stringify(rest))} -->`;
}

/** Recover embedded view settings from exported Markdown, or null if none/invalid. */
export function readEmbeddedView(text: string): Partial<EmbeddedView> | null {
  const match = text.match(MARKER);
  if (!match) return null;
  try {
    const data: unknown = JSON.parse(fromBase64(match[1] ?? ""));
    return data && typeof data === "object" ? (data) : null;
  } catch {
    return null;
  }
}
