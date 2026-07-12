import { createProfile, type Profile } from "./profile/profile";

/**
 * A saved Knowledge View file (extension `.kvsview`) — KVS's answer to an Obsidian `.base`
 * file. It is fully self-contained and, like a Base, can hold several views (tabs): the file
 * stores an ordered list of complete view profiles plus which one is active, so it opens as a
 * multi-tab dashboard in its own pane without depending on the plugin's stored views.
 *
 * The on-disk format is JSON so the rich, nested profile round-trips with perfect fidelity and
 * needs no YAML dependency. Version 1 files (a single `profile`) are read transparently.
 */
export const KVS_VIEW_EXTENSION = "kvsview";

/** The parsed contents of a `.kvsview` file: one or more views and the active view's id. */
export interface ViewFileDoc {
  readonly views: Profile[];
  readonly activeView: string;
}

/** Serialize a multi-view document to the on-disk `.kvsview` format. */
export function serializeViewDoc(doc: ViewFileDoc): string {
  const active = doc.views.some((v) => v.id === doc.activeView) ? doc.activeView : (doc.views[0]?.id ?? "");
  const envelope = { knowledgeView: 2, activeView: active, views: doc.views };
  return `${JSON.stringify(envelope, null, 2)}\n`;
}

/** Parse a `.kvsview` file into a normalized document; returns null when it isn't valid. */
export function parseViewDoc(text: string): ViewFileDoc | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  let raw: unknown;
  try {
    raw = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (typeof raw !== "object" || raw === null) return null;
  const obj = raw as { views?: unknown; activeView?: unknown; profile?: unknown };

  // v2: a list of views.
  if (Array.isArray(obj.views)) {
    const views = obj.views
      .filter((v): v is object => typeof v === "object" && v !== null)
      .map((v) => createProfile(v as Partial<Profile>));
    if (views.length === 0) return null;
    const activeView =
      typeof obj.activeView === "string" && views.some((v) => v.id === obj.activeView)
        ? obj.activeView
        : views[0]!.id;
    return { views, activeView };
  }

  // v1: a single profile.
  if (typeof obj.profile === "object" && obj.profile !== null) {
    const profile = createProfile(obj.profile as Partial<Profile>);
    return { views: [profile], activeView: profile.id };
  }

  return null;
}

/** Serialize a single view as a one-tab `.kvsview` document. */
export function serializeViewFile(profile: Profile): string {
  return serializeViewDoc({ views: [profile], activeView: profile.id });
}

/** Parse a `.kvsview` file and return its active view (convenience for single-view callers). */
export function parseViewFile(text: string): Profile | null {
  const doc = parseViewDoc(text);
  if (!doc) return null;
  return doc.views.find((v) => v.id === doc.activeView) ?? doc.views[0] ?? null;
}
