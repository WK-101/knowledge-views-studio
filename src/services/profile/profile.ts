import { DEFAULT_THEME_SPEC } from "../annotations/themes";
import { DEFAULT_RELEVANCE, type RelevanceWeights } from "../search/relevance";
import type { ColumnMatchMode, RowMerge } from "../../domain/index";
import {
  DEFAULT_SCOPE,
  TABLE_EXTRACTOR_ID,
  type ColumnConfig,
  type ComputedColumn,
  type RollupColumn,
  type FilterGroup,
  type GroupSpec,
  type ScopeConfig,
  type SortKey,
  type TransformSpec,
} from "../../domain/index";
import { createId } from "../../util/id";

/** The current persisted-schema version. Bump when the shape changes. */
export const SCHEMA_VERSION = 1;

/**
 * Which view renders a profile, plus that view's own options. Kept generic
 * (a type id + an opaque options bag) so views remain a pluggable registry —
 * adding Kanban / pivot / calendar later requires no change here.
 */
export interface ViewConfig {
  readonly type: string;
  readonly options: Readonly<Record<string, unknown>>;
}

/**
 * A saved view. It is essentially a {@link TransformSpec} plus a scope, an
 * extractor selection, and a view choice — one cohesive object, unlike the
 * legacy dual nested/flat profile twins.
 */
export interface Profile {
  readonly id: string;
  readonly name: string;
  /** Optional category label, for organising many views. */
  readonly category?: string;
  readonly scope: ScopeConfig;
  readonly extractors: readonly string[];
  /** How several sources combine within a note: keep their rows separate, or fold note-level values
   *  (properties, inline fields) into each item row from the same note. */
  readonly rowMerge: RowMerge;
  readonly columns: readonly ColumnConfig[];
  readonly computed: readonly ComputedColumn[];
  /** Rollup columns: aggregate a field across the rows of notes a relation links to. */
  readonly rollups: readonly RollupColumn[];
  readonly filter: FilterGroup | null;
  readonly advancedQuery: string | null;
  /** Header-matching strictness for aggregation (loose | contains | exact). */
  readonly columnMatch: ColumnMatchMode;
  /** Max rows drawn per group before a 'Show N more' control. 0 = unlimited. */
  readonly groupLimit: number;
  /** Freeze the first data column (and leading cells) while scrolling horizontally. */
  readonly frozenFirstColumn: boolean;
  /** Keep the header row visible while scrolling the table vertically. */
  readonly frozenHeader: boolean;
  /** Table row density. */
  readonly rowHeight: RowHeight;
  /** Table horizontal sizing: fit the pane, or give columns room and scroll. */
  readonly tableWidth: TableWidth;
  /** Column names hidden from the view (lightweight show/hide, independent of column config). */
  readonly hiddenColumns: readonly string[];
  /** Show the leading source-note link column. */
  readonly sourceColumn: boolean;
  /** Show the leading row-selection checkbox column (enables bulk edit). */
  readonly rowSelection: boolean;
  /** Hide columns whose value is blank for every matching row (page-independent). */
  readonly hideEmptyColumns: boolean;
  /** Opt-in per view: apply the Academic Research kit (academic column types + styling). */
  readonly academicKit: boolean;
  /** Maps kit semantic fields (title/year/venue/summary) to column names, so renaming a column
   *  doesn't break DOI/OpenAlex lookups. Type-based fields (doi/citekey/authors/tags/cites) auto-resolve. */
  readonly fieldMap?: Readonly<Record<string, string>>;
  /** Folder for promoted paper notes. Empty = default ("{first scope folder}/Papers"). */
  readonly promotedNotesFolder?: string;
  /** Template for this view's promoted notes. Empty = fall back to the global template, then default. */
  readonly promotedNoteTemplate?: string;
  /** File whose table receives new rows (toolbar "Add row" / "Add row below"). Empty = the row's own
   *  file, else the first row's file. The file must already contain a compatible table. */
  readonly newRowFile?: string;
  readonly sort: readonly SortKey[];
  readonly group: GroupSpec | null;
  readonly view: ViewConfig;
  readonly pageSize: number | null;
  /** Per-view column width overrides from header drag-resize, keyed by lowercased column name.
   *  Kept separate from the column definitions so resizing never alters which columns exist. */
  readonly columnWidths?: Readonly<Record<string, number>>;
  /** Per-source options keyed by extractor id (e.g. xlsx → { sheet, headerRow }). */
  readonly sourceOptions?: Readonly<Record<string, Readonly<Record<string, string>>>>;
  /**
   * Optional named layouts ("tabs") over this view's shared data. When present, each layout renders
   * the *same* filtered rows in its own way (view type, sort, grouping, visible columns, widths).
   * When absent, the presentation fields above act as the single, default layout — so existing
   * single-layout views are unaffected.
   */
  readonly layouts?: readonly Layout[];
}

/**
 * One presentation of a view's shared data — a "tab". It carries only presentation; the data source
 * (scope, extractors, columns, computed, rollups, filter, advanced query, column matching) is shared
 * across all layouts of the view, so editing the data once updates every layout.
 */
export interface Layout {
  readonly id: string;
  readonly name: string;
  readonly view: ViewConfig;
  readonly sort: readonly SortKey[];
  readonly group: GroupSpec | null;
  readonly pageSize: number | null;
  readonly hiddenColumns: readonly string[];
  readonly columnWidths?: Readonly<Record<string, number>>;
  readonly frozenFirstColumn: boolean;
  readonly frozenHeader: boolean;
  readonly rowHeight: RowHeight;
  readonly tableWidth: TableWidth;
  readonly sourceColumn: boolean;
  readonly rowSelection: boolean;
  readonly hideEmptyColumns: boolean;
}

export type RowHeight = "compact" | "normal" | "comfortable";
export type TableWidth = "fit" | "wide";

export interface GlobalSettings {
  /** Live re-render of open views when their source notes change. */
  readonly autoRefresh: boolean;
  /** Coalescing window for refreshes, in milliseconds. */
  readonly refreshDebounceMs: number;
  readonly defaultPageSize: number;
  readonly defaultView: string;
  /** Allow editing cells in views, writing back to the source table. */
  readonly inlineEditing: boolean;
  /** Safety cap on rows handed to a single view (0 = unlimited). */
  readonly maxRows: number;
  /** Max rendered image height in px (0 = no cap). */
  readonly imageMaxHeight: number;
  /** Max rendered image width in px (0 = fit container). */
  readonly imageMaxWidth: number;
  /** Opt-in: let `.xlsx` files be used as (read-only) data sources. Off = fully inert. */
  readonly enableExcelSources: boolean;
  /** Build a search index at all. Off = KVS never reads your vault for search. */
  readonly enableSearch: boolean;
  /**
   * Also read the full text of attachments (PDF, Word, PowerPoint, EPUB — and Excel, if Excel sources
   * are enabled). Off by default: parsing every PDF in a vault is expensive, and nobody should pay for
   * it before asking for it.
   */
  readonly indexAttachments: boolean;
  /** Index attachments on phones and tablets too. Separate from the above on purpose: settings sync,
   *  and "read every PDF" is a very different promise on a laptop than on a battery. */
  readonly indexAttachmentsOnMobile: boolean;
  /**
   * Which engine produces the semantic vectors.
   *   "builtin" — learns from your vault. Downloads nothing, ever. Weaker at synonyms it has never
   *               seen you use.
   *   "neural"  — a real sentence-transformer. Much better at meaning, but fetches a ~25 MB model
   *               once. Your notes are still never sent anywhere.
   */
  readonly semanticEngine: "builtin" | "neural";
  /**
   * The numbers that decide which result you see first. Exposed rather than buried: they were guesses,
   * and a guess someone can disagree with is better than a guess they cannot see.
   */
  readonly relevance: RelevanceWeights;
  /**
   * Where the search index lives.
   *   "local" — IndexedDB. Fast, invisible, and confined to this device.
   *   "vault" — a file in your vault, so whatever syncs your notes syncs your index too. This is what
   *             makes search work on mobile without re-indexing there.
   */
  readonly indexLocation: "local" | "vault";
  /** The folder the in-vault index is written to. */
  readonly indexFolder: string;
  /** Back up an Excel workbook (once per file per day) before KVS's first edit to it that day. */
  readonly enableExcelBackup: boolean;
  /** Opt-in: make the Academic Research kit available (academic column types, actions, styling,
   *  templates) for views that turn it on. Off = the kit is entirely hidden. */
  readonly enableAcademicKit: boolean;
  /** Allow DOI/OpenAlex network lookups (metadata fill, capture, citation links). */
  readonly researchLookupEnabled: boolean;
  /** Contact email for the Crossref/OpenAlex "polite pool" (optional, improves rate limits). */
  readonly researchEmail: string;
  /** Delay between lookup requests, in ms (politeness / rate-limit control). */
  readonly researchRequestDelayMs: number;
  /** Display nested tags (#a/b/c) by their last segment only (full tag kept in data). */
  readonly shortenNestedTags: boolean;
  /** Template for promoted paper notes (empty falls back to the built-in default). */
  readonly promotedNoteTemplate: string;
  /** Pull annotations from Zotero's local API (for zotero:// attachments). */
  readonly zoteroApiEnabled: boolean;
  /** Base URL of Zotero's local API (adjust if your default differs). */
  readonly zoteroApiBase: string;
  /** Highlight colour → research theme mapping ("color=Theme; …"). */
  readonly annotationThemes: string;
  /** When ZotFlow is installed, offer its reader for PDFs/EPUBs and collect its `.zf.json` annotations. */
  readonly zotflowInteropEnabled: boolean;
  /** Include the Zotero library and its annotations in vault search (reads Zotero's local API). */
  readonly indexZotero: boolean;
  /** Folder where new literature notes (one per Zotero paper) are created. */
  readonly literatureNotesFolder: string;
  /** Custom literature-note template with {{placeholders}}; empty means the built-in default. */
  readonly literatureNoteTemplate: string;
  /** Whether the first-run welcome has been shown (so it appears only once). */
  readonly onboardingSeen: boolean;
  /** Ids of one-time contextual hints the user has dismissed. */
  readonly seenHints: readonly string[];

  // ---- Row copying (opt-in; fully inert when disabled) ----
  /** Master switch: enable multi-format copy of selected rows. Off = no Copy button, no shortcut. */
  readonly enableRowCopy: boolean;
  /** How wikilinks are rendered in copied text: keep [[...]], reduce to display text, or use the path. */
  readonly copyLinkHandling: "keep" | "text" | "path";
  /** Include a header row in the copied table. */
  readonly copyIncludeHeader: boolean;
  /** Also place an HTML table on the clipboard (so Word / Docs / Excel get a formatted table). */
  readonly copyIncludeHtml: boolean;
  /** Let Cmd/Ctrl+C copy the selected rows when the table is focused (the Copy button always works). */
  readonly copyUseShortcut: boolean;
}

export interface PluginData {
  readonly version: number;
  readonly profiles: readonly Profile[];
  readonly settings: GlobalSettings;
  readonly activeProfileId: string | null;
}

export const DEFAULT_SETTINGS: GlobalSettings = {
  autoRefresh: true,
  refreshDebounceMs: 400,
  defaultPageSize: 50,
  defaultView: "table",
  inlineEditing: true,
  maxRows: 1000,
  imageMaxHeight: 320,
  imageMaxWidth: 0,
  enableExcelSources: false,
  enableSearch: true,
  indexAttachments: false,
  indexAttachmentsOnMobile: false,
  semanticEngine: "builtin",
  relevance: DEFAULT_RELEVANCE,
  indexLocation: "local",
  indexFolder: "KVS Index",
  enableExcelBackup: true,
  enableAcademicKit: false,
  researchLookupEnabled: true,
  researchEmail: "",
  researchRequestDelayMs: 300,
  shortenNestedTags: false,
  promotedNoteTemplate: "",
  zoteroApiEnabled: false,
  zoteroApiBase: "http://127.0.0.1:23119/api/users/0",
  annotationThemes: DEFAULT_THEME_SPEC,
  zotflowInteropEnabled: false,
  indexZotero: false,
  literatureNotesFolder: "Literature",
  literatureNoteTemplate: "",
  onboardingSeen: false,
  seenHints: [],
  enableRowCopy: false,
  copyLinkHandling: "keep",
  copyIncludeHeader: true,
  copyIncludeHtml: true,
  copyUseShortcut: true,
};

export const DEFAULT_DATA: PluginData = {
  version: SCHEMA_VERSION,
  profiles: [],
  settings: DEFAULT_SETTINGS,
  activeProfileId: null,
};

/** Build a complete profile from a partial, filling defaults and a fresh id. */
const LAYOUT_TYPE_LABELS: Readonly<Record<string, string>> = {
  table: "Table",
  kanban: "Board",
  calendar: "Calendar",
  cards: "Cards",
  pivot: "Summary",
};

/** A friendly default name for a layout, from its view type. */
export function layoutTypeLabel(type: string): string {
  return LAYOUT_TYPE_LABELS[type] ?? (type ? type.charAt(0).toUpperCase() + type.slice(1) : "Layout");
}

/** Fill a layout's defaults (and a fresh id / name when missing). */
export function normalizeLayout(partial: Partial<Layout> = {}): Layout {
  const view = partial.view ?? { type: "table", options: {} };
  return {
    id: partial.id ?? createId("layout"),
    name: partial.name && partial.name.trim() !== "" ? partial.name : layoutTypeLabel(view.type),
    view,
    sort: partial.sort ?? [],
    group: partial.group ?? null,
    pageSize: partial.pageSize ?? null,
    hiddenColumns: partial.hiddenColumns ?? [],
    ...(partial.columnWidths ? { columnWidths: partial.columnWidths } : {}),
    frozenFirstColumn: partial.frozenFirstColumn ?? false,
    frozenHeader: partial.frozenHeader ?? false,
    rowHeight: partial.rowHeight ?? "normal",
    tableWidth: partial.tableWidth ?? "fit",
    sourceColumn: partial.sourceColumn ?? true,
    rowSelection: partial.rowSelection ?? true,
    hideEmptyColumns: partial.hideEmptyColumns ?? false,
  };
}

/** Input to {@link createProfile}: any Profile field may be partial, and layouts may be partial too
 *  (createProfile normalizes each one, filling defaults and a fresh id). */
export type ProfileInput = Partial<Omit<Profile, "layouts">> & { readonly layouts?: readonly Partial<Layout>[] };

export function createProfile(partial: ProfileInput = {}): Profile {
  // Per-view column visibility lives in ONE place: `hiddenColumns`. Older data stored it as a
  // per-column `visible:false` flag; fold that into hiddenColumns so the two never disagree.
  const hiddenColumns: string[] = [];
  const hiddenSeen = new Set<string>();
  const addHidden = (name: string): void => {
    const k = name.toLowerCase();
    if (!hiddenSeen.has(k)) {
      hiddenSeen.add(k);
      hiddenColumns.push(name);
    }
  };
  for (const n of partial.hiddenColumns ?? []) addHidden(n);
  const columns = (partial.columns ?? []).map((c) => {
    if (c.visible === false) {
      addHidden(c.name);
      const copy = { ...c };
      delete (copy as { visible?: boolean }).visible;
      return copy;
    }
    return c;
  });

  return {
    id: partial.id ?? createId("profile"),
    name: partial.name ?? "Untitled view",
    scope: partial.scope ?? { ...DEFAULT_SCOPE },
    extractors: partial.extractors ?? [TABLE_EXTRACTOR_ID],
    rowMerge: partial.rowMerge === "enrich" ? "enrich" : "separate",
    columns,
    computed: partial.computed ?? [],
    rollups: partial.rollups ?? [],
    filter: partial.filter ?? null,
    advancedQuery: partial.advancedQuery ?? null,
    columnMatch: partial.columnMatch ?? "loose",
    groupLimit: typeof partial.groupLimit === "number" && partial.groupLimit >= 0 ? Math.floor(partial.groupLimit) : 0,
    frozenFirstColumn: partial.frozenFirstColumn ?? false,
    frozenHeader: partial.frozenHeader ?? false,
    rowHeight: partial.rowHeight ?? "normal",
    tableWidth: partial.tableWidth ?? "fit",
    hiddenColumns,
    sourceColumn: partial.sourceColumn ?? true,
    rowSelection: partial.rowSelection ?? true,
    hideEmptyColumns: partial.hideEmptyColumns ?? false,
    academicKit: partial.academicKit ?? false,
    ...(partial.fieldMap ? { fieldMap: { ...partial.fieldMap } } : {}),
    ...(partial.promotedNotesFolder ? { promotedNotesFolder: partial.promotedNotesFolder } : {}),
    ...(partial.promotedNoteTemplate ? { promotedNoteTemplate: partial.promotedNoteTemplate } : {}),
    ...(partial.newRowFile ? { newRowFile: partial.newRowFile } : {}),
    ...(partial.category !== undefined && partial.category !== "" ? { category: partial.category } : {}),
    sort: partial.sort ?? [],
    group: partial.group ?? null,
    view: partial.view ?? { type: "table", options: {} },
    pageSize: partial.pageSize ?? null,
    ...(partial.columnWidths ? { columnWidths: partial.columnWidths } : {}),
    ...(partial.sourceOptions ? { sourceOptions: partial.sourceOptions } : {}),
    ...(partial.layouts && partial.layouts.length > 0
      ? { layouts: partial.layouts.map((l) => normalizeLayout(l)) }
      : {}),
  };
}

/** Derive the transform spec a profile describes (scope/extractors are applied earlier). */
export function profileToTransformSpec(profile: Profile): TransformSpec {
  return {
    columns: profile.columns,
    computed: profile.computed,
    rollups: profile.rollups,
    filter: profile.filter,
    advancedQuery: profile.advancedQuery,
    columnMatch: profile.columnMatch,
    sort: profile.sort,
    group: profile.group,
    page: profile.pageSize !== null ? { size: profile.pageSize, index: 0 } : null,
  };
}
