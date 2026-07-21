import {
  canonicalizeOperator,
  type ColumnConfig,
  type FilterCondition,
  type FilterGroup,
  type ScopeConfig,
  type SortKey,
} from "../../domain/index";
import {
  DEFAULT_DATA,
  DEFAULT_SETTINGS,
  DEFAULT_ANNOTATION_WRITEBACK,
  DEFAULT_PALETTE_OVERRIDE,
  type AnnotationWriteback,
  SCHEMA_VERSION,
  createProfile,
  type GlobalSettings,
  type PluginData,
  type Profile,
} from "./profile";
import { normalizeWeights } from "../search/relevance";
import { ZOTERO_PALETTE, hexToRgb255, type PaletteOverride } from "../../../shared/annotations";

export interface MigrationOutcome {
  readonly data: PluginData;
  readonly warnings: string[];
}

// ---- defensive readers -----------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
const asArray = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const asString = (value: unknown, fallback = ""): string =>
  typeof value === "string" ? value : fallback;
const asBool = (value: unknown, fallback = false): boolean =>
  typeof value === "boolean" ? value : fallback;
const asNumber = (value: unknown, fallback: number): number =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback;

// ---- legacy field mapping --------------------------------------------------

/** Map a legacy HeaderRole/FieldKind onto a current column type id. */
function legacyColumnType(role: string, fieldKind: string): string {
  switch (role) {
    case "note-link":
      return "link";
    case "source":
      return "url";
    case "year":
      return "number";
    case "tags":
      return "tags";
    case "content":
    case "notes":
      return "markdown";
    case "created":
    case "modified":
      return "date";
  }
  switch (fieldKind) {
    case "note-title":
    case "link":
      return "link";
    case "source":
      return "url";
    case "year":
    case "metric":
    case "value":
      return "number";
    case "tags":
      return "tags";
    case "content":
    case "notes":
      return "markdown";
    case "date":
    case "created":
    case "modified":
      return "date";
    case "status":
    case "priority":
      return "select";
    default:
      return "text";
  }
}

function legacyScope(profile: Record<string, unknown>): ScopeConfig {
  const source = isRecord(profile.source) ? profile.source : profile;
  const mode = asString(source.mode ?? profile.scanMode, "full-vault");
  const includeSubfolders = asBool(source.includeSubfolders ?? profile.includeSubfolders, true);
  const single = asString(source.singleFolder ?? profile.singleFolder);
  const multi = asArray(source.multiFolders ?? profile.multiFolders).map((f) => asString(f));

  if (mode === "single-folder") {
    return { mode: "folders", folders: single ? [single] : [], includeSubfolders };
  }
  if (mode === "multi-folder") {
    return { mode: "folders", folders: multi.filter((f) => f !== ""), includeSubfolders };
  }
  return { mode: "vault", folders: [], includeSubfolders };
}

function legacyColumns(profile: Record<string, unknown>): ColumnConfig[] {
  const match = isRecord(profile.match) ? profile.match : null;
  const headers = asArray(match?.headers ?? profile.headers);

  if (headers.length > 0) {
    return headers
      .filter(isRecord)
      .map((h) => ({
        name: asString(h.name),
        type: legacyColumnType(asString(h.role), asString(h.fieldKind)),
      }))
      .filter((c) => c.name !== "");
  }

  // Older flat form: targetHeaders[] + headerRoles{}.
  const targets = asArray(profile.targetHeaders).map((t) => asString(t));
  const roles = isRecord(profile.headerRoles) ? profile.headerRoles : {};
  return targets
    .filter((name) => name !== "")
    .map((name) => ({ name, type: legacyColumnType(asString(roles[name]), "") }));
}

function legacyFilter(profile: Record<string, unknown>): FilterGroup | null {
  const transform = isRecord(profile.transform) ? profile.transform : null;
  const raw = asArray(transform?.filters ?? profile.filters).filter(isRecord);
  if (raw.length === 0) return null;

  const conditions: FilterCondition[] = raw.map((f) => ({
    field: asString(f.field),
    operator: canonicalizeOperator(asString(f.operator, "contains")),
    value: asString(f.value),
  }));
  return { combinator: "and", conditions, groups: [] };
}

function legacySort(profile: Record<string, unknown>): SortKey[] {
  const transform = isRecord(profile.transform) ? profile.transform : null;
  const field = asString(transform?.defaultSortField ?? profile.defaultSortField);
  if (field === "") return [];
  const dir = asString(transform?.defaultSortDirection ?? profile.defaultSortDirection, "asc");
  return [{ field, direction: dir === "desc" ? "desc" : "asc" }];
}

function migrateLegacyProfile(raw: unknown): Profile | null {
  if (!isRecord(raw)) return null;
  const transform = isRecord(raw.transform) ? raw.transform : {};
  const advanced = asString(transform.queryExpression ?? raw.queryExpression);
  const pageSize = asNumber(transform.defaultPageSize ?? raw.defaultPageSize, 0);

  return createProfile({
    id: asString(raw.id) || undefined,
    name: asString(raw.label ?? raw.name, "Imported view"),
    scope: legacyScope(raw),
    columns: legacyColumns(raw),
    filter: legacyFilter(raw),
    advancedQuery: advanced !== "" ? advanced : null,
    sort: legacySort(raw),
    pageSize: pageSize > 0 ? pageSize : null,
    view: { type: "table", options: {} },
  });
}

// ---- normalization of already-current data ---------------------------------

/**
 * Read the bridge settings from saved data.
 *
 * A vault that predates the bridge has nothing stored, and gets the defaults — which means *off*. Enabling a
 * local server as a side effect of updating a plugin would be indefensible, so the absence of a setting is
 * always read as "no".
 */
function normalizeBridge(raw: unknown): GlobalSettings["bridge"] {
  const d = DEFAULT_SETTINGS.bridge;
  if (!isRecord(raw)) return d;
  const origins = Array.isArray(raw.allowedOrigins)
    ? raw.allowedOrigins.filter((o): o is string => typeof o === "string")
    : d.allowedOrigins;
  const exposed = Array.isArray(raw.exposedViewIds)
    ? raw.exposedViewIds.filter((o): o is string => typeof o === "string")
    : null;
  return {
    enabled: asBool(raw.enabled, d.enabled),
    port: asNumber(raw.port, d.port),
    allowRead: asBool(raw.allowRead, d.allowRead),
    allowWrite: asBool(raw.allowWrite, d.allowWrite),
    // Defaults to off for an existing setup as well as a new one: search is the broadest grant here, and
    // nobody should acquire it by updating.
    allowSearch: asBool(raw.allowSearch, d.allowSearch),
    exposedViewIds: exposed,
    allowedOrigins: origins,
    token: typeof raw.token === "string" && raw.token !== "" ? raw.token : null,
    maxBodyBytes: asNumber(raw.maxBodyBytes, d.maxBodyBytes),
    logRequests: asBool(raw.logRequests, d.logRequests),
  };
}

function normalizeWriteback(raw: unknown): AnnotationWriteback {
  const d = DEFAULT_ANNOTATION_WRITEBACK;
  if (!isRecord(raw)) return d;
  return {
    noteToCell: asBool(raw.noteToCell, d.noteToCell),
    noteToNote: asBool(raw.noteToNote, d.noteToNote),
    tagsToCell: asBool(raw.tagsToCell, d.tagsToCell),
    tagsToNoteInline: asBool(raw.tagsToNoteInline, d.tagsToNoteInline),
    tagsToNoteProperty: asBool(raw.tagsToNoteProperty, d.tagsToNoteProperty),
  };
}

/**
 * A stored palette override, made safe: `enabled` coerced to a boolean, and every one of the eight colour
 * slots resolved to a valid hex — the stored value if it parses, otherwise that colour's Zotero default. A
 * garbage or missing entry can never survive into the running palette.
 */
function normalizePaletteOverride(raw: unknown): PaletteOverride {
  const d = DEFAULT_PALETTE_OVERRIDE;
  if (!isRecord(raw)) return d;
  const storedColors = isRecord(raw.colors) ? raw.colors : {};
  const colors: Record<string, string> = {};
  for (const c of ZOTERO_PALETTE) {
    const stored = storedColors[c.name];
    colors[c.name] = typeof stored === "string" && hexToRgb255(stored) !== null ? stored : c.hex;
  }
  return { enabled: asBool(raw.enabled, d.enabled), colors };
}

function normalizeSettings(raw: unknown): GlobalSettings {
  if (!isRecord(raw)) return DEFAULT_SETTINGS;
  return {
    bridge: normalizeBridge(raw.bridge),
    autoRefresh: asBool(raw.autoRefresh, DEFAULT_SETTINGS.autoRefresh),
    refreshDebounceMs: asNumber(raw.refreshDebounceMs, DEFAULT_SETTINGS.refreshDebounceMs),
    defaultPageSize: asNumber(raw.defaultPageSize, DEFAULT_SETTINGS.defaultPageSize),
    defaultView: asString(raw.defaultView, DEFAULT_SETTINGS.defaultView),
    inlineEditing: asBool(raw.inlineEditing, DEFAULT_SETTINGS.inlineEditing),
    maxRows: asNumber(raw.maxRows, DEFAULT_SETTINGS.maxRows),
    imageMaxHeight: asNumber(raw.imageMaxHeight, DEFAULT_SETTINGS.imageMaxHeight),
    imageMaxWidth: asNumber(raw.imageMaxWidth, DEFAULT_SETTINGS.imageMaxWidth),
    enableExcelSources: asBool(raw.enableExcelSources, DEFAULT_SETTINGS.enableExcelSources),
    enableSearch: asBool(raw.enableSearch, DEFAULT_SETTINGS.enableSearch),
    indexAttachments: asBool(raw.indexAttachments, DEFAULT_SETTINGS.indexAttachments),
    ocrEnabled: asBool(raw.ocrEnabled, DEFAULT_SETTINGS.ocrEnabled),
    ocrLanguages: Array.isArray(raw.ocrLanguages) ? raw.ocrLanguages.filter((x): x is string => typeof x === "string") : [...DEFAULT_SETTINGS.ocrLanguages],
    indexAttachmentsOnMobile: asBool(raw.indexAttachmentsOnMobile, DEFAULT_SETTINGS.indexAttachmentsOnMobile),
    semanticEngine: raw.semanticEngine === "neural" ? "neural" : "builtin",
    indexLocation: raw.indexLocation === "vault" ? "vault" : "local",
    indexFolder: asString(raw.indexFolder, DEFAULT_SETTINGS.indexFolder),
    relevance: normalizeWeights(isRecord(raw.relevance) ? (raw.relevance) : undefined),
    enableExcelBackup: asBool(raw.enableExcelBackup, DEFAULT_SETTINGS.enableExcelBackup),
    enableAcademicKit: asBool(raw.enableAcademicKit, DEFAULT_SETTINGS.enableAcademicKit),
    researchLookupEnabled: asBool(raw.researchLookupEnabled, DEFAULT_SETTINGS.researchLookupEnabled),
    researchEmail: typeof raw.researchEmail === "string" ? raw.researchEmail : DEFAULT_SETTINGS.researchEmail,
    researchRequestDelayMs: asNumber(raw.researchRequestDelayMs, DEFAULT_SETTINGS.researchRequestDelayMs),
    shortenNestedTags: asBool(raw.shortenNestedTags, DEFAULT_SETTINGS.shortenNestedTags),
    promotedNoteTemplate: typeof raw.promotedNoteTemplate === "string" ? raw.promotedNoteTemplate : DEFAULT_SETTINGS.promotedNoteTemplate,
    zoteroApiEnabled: asBool(raw.zoteroApiEnabled, DEFAULT_SETTINGS.zoteroApiEnabled),
    zoteroApiBase: asString(raw.zoteroApiBase, DEFAULT_SETTINGS.zoteroApiBase),
    annotationThemes: asString(raw.annotationThemes, DEFAULT_SETTINGS.annotationThemes),
    zotflowInteropEnabled: asBool(raw.zotflowInteropEnabled, DEFAULT_SETTINGS.zotflowInteropEnabled),
    indexZotero: asBool(raw.indexZotero, DEFAULT_SETTINGS.indexZotero),
    literatureNotesFolder: asString(raw.literatureNotesFolder, DEFAULT_SETTINGS.literatureNotesFolder),
    literatureNoteTemplate: asString(raw.literatureNoteTemplate, DEFAULT_SETTINGS.literatureNoteTemplate),
    onboardingSeen: asBool(raw.onboardingSeen, DEFAULT_SETTINGS.onboardingSeen),
    annotationWriteback: normalizeWriteback(raw.annotationWriteback),
    paletteOverride: normalizePaletteOverride(raw.paletteOverride),
    seenHints: Array.isArray(raw.seenHints) ? raw.seenHints.filter((h): h is string => typeof h === "string") : [],
    enableRowCopy: asBool(raw.enableRowCopy, DEFAULT_SETTINGS.enableRowCopy),
    copyLinkHandling:
      raw.copyLinkHandling === "text" || raw.copyLinkHandling === "path" ? raw.copyLinkHandling : DEFAULT_SETTINGS.copyLinkHandling,
    copyIncludeHeader: asBool(raw.copyIncludeHeader, DEFAULT_SETTINGS.copyIncludeHeader),
    copyIncludeHtml: asBool(raw.copyIncludeHtml, DEFAULT_SETTINGS.copyIncludeHtml),
    copyUseShortcut: asBool(raw.copyUseShortcut, DEFAULT_SETTINGS.copyUseShortcut),
  };
}

function normalizeCurrent(raw: Record<string, unknown>): PluginData {
  const profiles = asArray(raw.profiles).map((p) => createProfile(isRecord(p) ? (p as Partial<Profile>) : {}));
  return {
    version: SCHEMA_VERSION,
    profiles,
    settings: normalizeSettings(raw.settings),
    activeProfileId: typeof raw.activeProfileId === "string" ? raw.activeProfileId : null,
  };
}

/**
 * Turn whatever was persisted — current data, a legacy `PluginSettingsV2`, or
 * unrecognizable junk — into valid {@link PluginData}. Never throws; anything it
 * cannot interpret is reported as a warning and replaced with defaults.
 */
export function migrateData(raw: unknown): MigrationOutcome {
  const warnings: string[] = [];

  if (!isRecord(raw)) {
    return { data: DEFAULT_DATA, warnings };
  }

  if (raw.version === SCHEMA_VERSION) {
    return { data: normalizeCurrent(raw), warnings };
  }

  // Legacy path: a PluginSettingsV2 has a `profiles` array.
  if (Array.isArray(raw.profiles)) {
    const profiles: Profile[] = [];
    raw.profiles.forEach((entry, index) => {
      try {
        const migrated = migrateLegacyProfile(entry);
        if (migrated) profiles.push(migrated);
        else warnings.push(`Skipped unrecognizable profile at index ${index}.`);
      } catch {
        warnings.push(`Failed to migrate profile at index ${index}.`);
      }
    });

    const metaProfiles = asArray(raw.metaProfiles);
    if (metaProfiles.length > 0) {
      warnings.push(`Dropped ${metaProfiles.length} legacy meta-profile(s); recreate as perspectives.`);
    }

    const data: PluginData = {
      version: SCHEMA_VERSION,
      profiles,
      settings: {
        ...DEFAULT_SETTINGS,
        autoRefresh: asBool(raw.interactiveAutoRefresh, DEFAULT_SETTINGS.autoRefresh),
      },
      activeProfileId: typeof raw.activeProfileId === "string" ? raw.activeProfileId : null,
    };
    return { data, warnings };
  }

  warnings.push("Unrecognized saved data; starting fresh.");
  return { data: DEFAULT_DATA, warnings };
}
