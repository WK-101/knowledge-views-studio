import { inferColumnType, inferFieldRole, isVirtualField, VIRTUAL_FIELDS, type ColumnConfig, type EnumOption, type FieldRole, type Row } from "../domain/index";
import type { Profile } from "../services/index";

/** A column resolved for display, including whether it can be edited in place. */
export interface ResolvedColumn {
  readonly name: string;
  readonly label: string;
  readonly typeId: string;
  readonly width?: number;
  readonly editable: boolean;
  readonly options?: readonly EnumOption[];
  readonly role: FieldRole;
}

export /** Comfortable per-column width (px) for "wide" mode, by role then type. */
function defaultWideWidth(typeId: string, role: string): number {
  switch (role) {
    case "title":
      return 260;
    case "tags":
      return 200;
    case "status":
      return 150;
    case "date":
      return 130;
    case "priority":
      return 120;
    default:
      break;
  }
  switch (typeId) {
    case "number":
    case "rating":
    case "checkbox":
      return 100;
    case "date":
      return 130;
    case "select":
      return 150;
    case "link":
    case "relation":
    case "url":
      return 190;
    case "image":
      return 120;
    case "markdown":
      return 280;
    default:
      return 200;
  }
}

const MAX_SAMPLES = 20;

/** Names of columns that are computed/rolled up (derived), so never written back. */
function derivedFieldNames(profile: Profile): Set<string> {
  const names = new Set<string>();
  for (const c of profile.computed) names.add(c.name.trim().toLowerCase());
  for (const r of profile.rollups) names.add(r.name.trim().toLowerCase());
  return names;
}

/** Virtual fields (note/created/…) are never editable; data columns default to editable. */
function isEditable(name: string, configEditable: boolean | undefined): boolean {
  return !isVirtualField(name) && configEditable !== false;
}

/**
 * Decide which columns a view shows. Configured columns win (in order, honouring
 * `visible`); otherwise columns are discovered from the rows and their types
 * inferred — the zero-config path.
 */
export function resolveColumns(profile: Profile, rows: readonly Row[]): ResolvedColumn[] {
  const derived = derivedFieldNames(profile);
  const hidden = new Set((profile.hiddenColumns ?? []).map((n) => n.toLowerCase()));
  const widths = profile.columnWidths ?? {};
  const widthFor = (name: string, configWidth?: number): number | undefined => widths[name.toLowerCase()] ?? configWidth;

  const toResolved = (c: ColumnConfig): ResolvedColumn => {
    const column: ResolvedColumn = {
      name: c.name,
      label: c.label ?? c.name,
      typeId: c.type,
      editable: isVirtualField(c.name) || derived.has(c.name.trim().toLowerCase()) ? false : isEditable(c.name, c.editable),
      role: c.role ?? inferFieldRole(c.type, c.name),
    };
    const w = widthFor(c.name, c.width);
    const withWidth = w !== undefined ? { ...column, width: w } : column;
    return c.options !== undefined ? { ...withWidth, options: c.options } : withWidth;
  };

  // "Configured mode" is driven by real data columns; adding only virtual fields (note/created/…)
  // must NOT collapse a discovery view down to a single column, so those are handled additively.
  const hasRealColumns = profile.columns.some((c) => !isVirtualField(c.name));
  if (hasRealColumns) {
    return profile.columns.filter((c) => !hidden.has(c.name.toLowerCase())).map(toResolved);
  }

  // Discovery mode: every field found in the rows, plus any virtual fields the user turned on.
  const order: string[] = [];
  const samples = new Map<string, string[]>();
  for (const row of rows) {
    for (const key of Object.keys(row.cells)) {
      let bucket = samples.get(key);
      if (!bucket) {
        bucket = [];
        samples.set(key, bucket);
        order.push(key);
      }
      if (bucket.length < MAX_SAMPLES) bucket.push(row.cells[key] ?? "");
    }
  }

  const discovered = order
    .filter((name) => !hidden.has(name.toLowerCase()))
    .map((name): ResolvedColumn => {
      const typeId = inferColumnType(name, samples.get(name) ?? []);
      const column: ResolvedColumn = {
        name,
        label: name,
        typeId,
        editable: derived.has(name.trim().toLowerCase()) ? false : isEditable(name, undefined),
        role: inferFieldRole(typeId, name),
      };
      const w = widthFor(name);
      return w !== undefined ? { ...column, width: w } : column;
    });

  const virtualColumns = profile.columns
    .filter((c) => isVirtualField(c.name) && !hidden.has(c.name.toLowerCase()))
    .map(toResolved);

  return [...discovered, ...virtualColumns];
}

/** One selectable field for the Properties menu: its current visibility and order. */
export interface ColumnChoice {
  readonly name: string;
  readonly label: string;
  readonly typeId: string;
  readonly visible: boolean;
}

function collectSamples(rows: readonly Row[]): Map<string, string[]> {
  const samples = new Map<string, string[]>();
  for (const row of rows) {
    for (const key of Object.keys(row.cells)) {
      let bucket = samples.get(key);
      if (!bucket) {
        bucket = [];
        samples.set(key, bucket);
      }
      if (bucket.length < MAX_SAMPLES) bucket.push(row.cells[key] ?? "");
    }
  }
  return samples;
}

/**
 * The full set of fields a view could show — configured columns, fields discovered
 * in the rows, and virtual fields — each tagged with whether it is currently
 * visible and in display order. Powers the in-pane Properties menu and the field
 * dropdowns of the Sort/Filter menus. Pure.
 */
export function computeColumnChoices(profile: Profile, rows: readonly Row[]): ColumnChoice[] {
  const samples = collectSamples(rows);
  const configured = profile.columns;
  const configuredNames = new Set(configured.map((c) => c.name.toLowerCase()));
  // Discovery is driven by real data columns only — turning on a virtual field (which becomes a
  // configured column) must not flip every discovered field to hidden in this list.
  const discoveryMode = !configured.some((c) => !isVirtualField(c.name));
  const hidden = new Set((profile.hiddenColumns ?? []).map((n) => n.toLowerCase()));

  const choices: ColumnChoice[] = [];
  const seen = new Set<string>();
  const push = (name: string, label: string, typeId: string, visible: boolean): void => {
    const key = name.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    choices.push({ name, label, typeId, visible: visible && !hidden.has(key) });
  };

  for (const column of configured) {
    push(column.name, column.label ?? column.name, column.type, true);
  }
  for (const name of samples.keys()) {
    if (configuredNames.has(name.toLowerCase())) continue;
    push(name, name, inferColumnType(name, samples.get(name) ?? []), discoveryMode);
  }
  for (const field of VIRTUAL_FIELDS) {
    push(field, field, "text", false);
  }
  return choices;
}

/** First visible column carrying the given semantic role, if any. */
export function findColumnByRole(columns: readonly ResolvedColumn[], role: FieldRole): ResolvedColumn | undefined {
  if (role === "none") return undefined;
  return columns.find((column) => column.role === role);
}

/**
 * Fields present in the underlying rows that a *curated* view doesn't show and the user hasn't
 * explicitly hidden — i.e. columns worth offering to add. Returns nothing for a discovery view (which
 * already shows every field) so the suggestion only appears when it's actionable.
 */
export function suggestedColumns(profile: Profile, rows: readonly Row[]): { name: string; type: string }[] {
  const discoveryMode = !profile.columns.some((c) => !isVirtualField(c.name));
  if (discoveryMode) return [];
  const samples = collectSamples(rows);
  const configured = new Set(profile.columns.map((c) => c.name.toLowerCase()));
  const hidden = new Set((profile.hiddenColumns ?? []).map((n) => n.toLowerCase()));
  const out: { name: string; type: string }[] = [];
  for (const name of samples.keys()) {
    const key = name.toLowerCase();
    if (configured.has(key) || hidden.has(key) || isVirtualField(name)) continue;
    out.push({ name, type: inferColumnType(name, samples.get(name) ?? []) });
  }
  return out;
}
