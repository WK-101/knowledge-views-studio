import { TFile, type App, type WorkspaceLeaf } from "obsidian";
import { annotationId, type AnnotationKind, type KvsAnnotation } from "../../domain/index";

/**
 * Optional interoperation with the ZotFlow plugin, when a user has it installed.
 *
 * The reasoning here matters as much as the code. ZotFlow embeds Zotero's real reader — a far richer
 * PDF/EPUB/HTML experience than our pdf.js annotator, and one we have no intention of reimplementing. If
 * a user already has that reader in their vault, they should be able to use it *from* our academic kit,
 * so the two plugins compose into one workflow instead of competing. If they don't have it, nothing here
 * fires and our own reader is used exactly as before.
 *
 * Two rules govern every line below, because ZotFlow exposes **no public API** and ships very frequently:
 *
 *   1. **Public seams only.** We touch three things, all of which ZotFlow exposed to the world rather than
 *      kept private: whether the plugin is enabled (`app.plugins`), its registered *view type* (opened
 *      through Obsidian's own `setViewState`, exactly as ZotFlow's own code does), and its documented
 *      on-disk `.zf.json` annotation sidecar. We never import ZotFlow's modules, call its worker bridge,
 *      or depend on the shape of its internal classes — those change without warning and are not ours to
 *      reach into.
 *
 *   2. **Enhance, never break.** Every entry point degrades to "feature quietly unavailable" — never an
 *      error — if ZotFlow is absent, disabled, or has renamed the seam we hoped to use. Our own reader is
 *      always the fallback. Interop is a bonus that can vanish; it is never load-bearing.
 *
 * These identifiers are ZotFlow's, current as of its v1.2. They are the one genuine fragility in this
 * file: if ZotFlow renames them, detection simply returns false and we fall back. That is the correct
 * failure mode, and the reason the whole surface is guarded rather than assumed.
 */

const ZOTFLOW_PLUGIN_ID = "zotflow";
/** ZotFlow's view type for reading a plain vault file (not a Zotero-library item). */
const ZOTFLOW_LOCAL_READER_VIEW = "zotflow-local-zotero-reader-view";
/** ZotFlow co-locates annotations for a local file in a sidecar named `<file>.zf.json`. */
const ZOTFLOW_SIDECAR_SUFFIX = ".zf.json";

/** Minimal shape of the `app.plugins` registry we rely on — public, but untyped in Obsidian's API. */
interface PluginsRegistry {
  readonly enabledPlugins?: Set<string>;
  readonly plugins?: Record<string, unknown>;
}

function pluginsRegistry(app: App): PluginsRegistry | undefined {
  return (app as unknown as { plugins?: PluginsRegistry }).plugins;
}

/** Untyped access to Obsidian's view registry, to confirm ZotFlow actually registered its reader view. */
function viewRegisteredTypes(app: App): Record<string, unknown> | undefined {
  return (app as unknown as { viewRegistry?: { viewByType?: Record<string, unknown> } }).viewRegistry?.viewByType;
}

/**
 * Is ZotFlow present and usable *right now*? Checks both that the plugin is enabled and that its reader
 * view is actually registered — a plugin can be enabled mid-load before its views exist, and we only want
 * to offer the integration when opening a file in it would truly work.
 */
export function isZotFlowAvailable(app: App): boolean {
  const reg = pluginsRegistry(app);
  const enabled = reg?.enabledPlugins?.has(ZOTFLOW_PLUGIN_ID) ?? false;
  if (!enabled) return false;
  const views = viewRegisteredTypes(app);
  // If we can read the registry, require the view to be there; if we can't (API shape changed), fall back
  // to the enabled check alone rather than falsely reporting unavailable.
  return views ? ZOTFLOW_LOCAL_READER_VIEW in views : true;
}

/**
 * Open a vault file in ZotFlow's reader, in a new leaf.
 *
 * Uses `leaf.setViewState({ type, state: { file } })` — the exact public mechanism ZotFlow's own
 * `LocalReaderView.setState` consumes (`state.file` is a vault path). Returns whether it succeeded; a
 * caller that gets `false` should fall back to its own reader rather than surface an error.
 */
export async function openInZotFlow(app: App, file: TFile): Promise<boolean> {
  if (!isZotFlowAvailable(app)) return false;
  try {
    const leaf: WorkspaceLeaf = app.workspace.getLeaf("tab");
    await leaf.setViewState({ type: ZOTFLOW_LOCAL_READER_VIEW, state: { file: file.path }, active: true });
    await app.workspace.revealLeaf(leaf);
    return true;
  } catch {
    // ZotFlow's view rejected the state (renamed type, changed state shape, load error). Not our problem
    // to diagnose — the caller falls back to our reader.
    return false;
  }
}

// ---------------------------------------------------------------------------
// Reading ZotFlow's annotation sidecar
// ---------------------------------------------------------------------------

/**
 * One annotation as ZotFlow stores it — the subset we can use, from its documented `.zf.json` schema. Its
 * `AnnotationJSON` carries more (positions, tags, image bytes); we take only the fields that translate
 * into a note callout, and treat everything as optional because it is another program's file on disk.
 */
export interface ZotFlowAnnotation {
  readonly id: string;
  readonly type: string; // highlight | underline | note | image | ink | text
  readonly text?: string; // the quoted passage, for text-based annotations
  readonly comment?: string; // the user's note on it
  readonly color?: string; // hex
  readonly pageLabel?: string; // display page number
  readonly dateModified?: string;
}

/** The path of ZotFlow's sidecar for a given attachment, whether or not it exists. */
export function zotflowSidecarPath(attachmentPath: string): string {
  const dot = attachmentPath.lastIndexOf(".");
  const base = dot === -1 ? attachmentPath : attachmentPath.slice(0, dot);
  return `${base}${ZOTFLOW_SIDECAR_SUFFIX}`;
}

/**
 * Read the annotations ZotFlow saved for a local attachment, if any.
 *
 * This is the genuinely two-way part of the integration: a user reads and highlights a PDF in ZotFlow's
 * superior reader, and our academic kit can pull those highlights into the note as callouts, the same way
 * it collects annotations from our own reader. Returns an empty array for any file that has no sidecar or
 * whose sidecar we cannot parse — a foreign file format is never trusted to be well-formed.
 */
export async function readZotFlowAnnotations(app: App, attachmentPath: string): Promise<ZotFlowAnnotation[]> {
  const sidecarPath = zotflowSidecarPath(attachmentPath);
  const sidecar = app.vault.getAbstractFileByPath(sidecarPath);
  if (!(sidecar instanceof TFile)) return [];
  try {
    const raw = await app.vault.read(sidecar);
    return parseZotFlowSidecar(raw);
  } catch {
    return [];
  }
}

/** Does a ZotFlow sidecar exist for this attachment? Cheap check for deciding whether to offer collection. */
export function hasZotFlowAnnotations(app: App, attachmentPath: string): boolean {
  return app.vault.getAbstractFileByPath(zotflowSidecarPath(attachmentPath)) instanceof TFile;
}

/**
 * ZotFlow's reader is Zotero's reader, so its annotation type vocabulary is Zotero's — the same mapping
 * our existing Zotero collector uses. `text` (Zotero's "text") maps to a highlight/underline; `note`/`ink`
 * carry no quoted text.
 */
const ZOTFLOW_KIND: Record<string, AnnotationKind> = {
  highlight: "highlight",
  underline: "underline",
  note: "note",
  image: "image",
  ink: "ink",
  text: "note",
};

/**
 * Convert a parsed ZotFlow annotation into our `KvsAnnotation`, so it renders into a note callout
 * identically to one from our own reader or from Zotero. Geometry is not carried across — ZotFlow's
 * `position` is in Zotero's coordinate model and we do not reinterpret a foreign engine's geometry — so
 * these collect as text-and-comment annotations, deduplicated by the same content id everything else uses.
 */
export function zotflowToKvsAnnotation(a: ZotFlowAnnotation, attachment: string): KvsAnnotation {
  const kind = ZOTFLOW_KIND[a.type.toLowerCase()] ?? "highlight";
  const text = (a.text ?? "").trim();
  const comment = (a.comment ?? "").trim();
  // No reliable page number without decoding ZotFlow's position; page 1 keeps the id stable and the label
  // (which ZotFlow does give us) carries the human-facing page.
  const page = 1;
  const base = { attachment, page, kind, text, rects: [] as const };
  return {
    id: annotationId(base),
    kind,
    text,
    comment,
    page,
    rects: [],
    source: "zotflow",
    attachment,
    ...(a.color ? { color: a.color } : {}),
    ...(a.pageLabel ? { pageLabel: a.pageLabel } : {}),
    ...(a.dateModified ? { createdAt: a.dateModified } : {}),
  };
}

/**
 * Read ZotFlow's sidecar for an attachment and return its annotations already mapped into our model,
 * ready to merge into a note. Empty when there is no sidecar or nothing parses — the caller treats a
 * ZotFlow-less vault and an un-annotated file identically.
 */
export async function collectZotFlowAnnotations(app: App, attachmentPath: string): Promise<KvsAnnotation[]> {
  const raw = await readZotFlowAnnotations(app, attachmentPath);
  return raw.map((a) => zotflowToKvsAnnotation(a, attachmentPath));
}

/**
 * Parse a `.zf.json` sidecar into the fields we use, defensively. Pure, so the parsing rules are tested
 * against real and malformed input rather than trusted. Anything unexpected yields `[]` rather than
 * throwing — this is another program's on-disk format and may change or be corrupt.
 */
export function parseZotFlowSidecar(raw: string): ZotFlowAnnotation[] {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return [];
  }
  if (typeof data !== "object" || data === null) return [];
  const anns = (data as { annotations?: unknown }).annotations;
  if (!Array.isArray(anns)) return [];

  const out: ZotFlowAnnotation[] = [];
  for (const a of anns) {
    if (typeof a !== "object" || a === null) continue;
    const r = a as Record<string, unknown>;
    const id = typeof r["id"] === "string" ? r["id"] : undefined;
    const type = typeof r["type"] === "string" ? r["type"] : undefined;
    if (!id || !type) continue; // an annotation with no id or type is not usable
    out.push({
      id,
      type,
      ...(typeof r["text"] === "string" ? { text: r["text"] } : {}),
      ...(typeof r["comment"] === "string" ? { comment: r["comment"] } : {}),
      ...(typeof r["color"] === "string" ? { color: r["color"] } : {}),
      ...(typeof r["pageLabel"] === "string" ? { pageLabel: r["pageLabel"] } : {}),
      ...(typeof r["dateModified"] === "string" ? { dateModified: r["dateModified"] } : {}),
    });
  }
  return out;
}
