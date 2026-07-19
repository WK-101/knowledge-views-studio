import { normalizePath, TFile, type App } from "obsidian";
import { getField, type Row } from "../../domain/index";
import { normalizeText } from "./normalize";
import { renderTemplate, safeName as templateSafeName } from "../../../shared/template";
import { mapToColumns, applyDefaults } from "./map";
import { appendCapturedRow, appendCapturedRows } from "./capture-table";
import { appendToNote, capturedAppendBlock } from "./append-note";
import { findDedicatedNote } from "../notes/dedicated-note";
import { noteLinkColumnName } from "../../views/promoted-detect";
import type { CaptureColumn, CapturePayload, CaptureResult, CaptureTarget, MappedCapture } from "./types";

/**
 * Committing a capture to the vault.
 *
 * Deliberately transport-agnostic: it takes a payload and a target and knows nothing about where the payload
 * came from. That's what lets an in-app command and, later, the browser companion share one path — including
 * one set of rules about duplicates and one place where a mistake can be fixed.
 */

/** Frontmatter keys that carry a captured item's identity, used to spot something already captured. */
const IDENTITY_KEYS = ["url", "doi", "isbn"] as const;

export interface CaptureContext {
  readonly app: App;
  readonly columns: readonly CaptureColumn[];
  /** Rows already in the view, for duplicate detection. */
  readonly existingRows?: readonly Row[];
}

export interface DuplicateMatch {
  readonly row: Row;
  /** Which field matched — shown to the person so the decision is theirs, not ours. */
  readonly on: string;
  readonly value: string;
}

/**
 * Find an existing row that already represents this capture.
 *
 * Matching is on identity fields only (url, doi, isbn) — never on a title, because two papers can share a
 * title and refusing a legitimate second item is worse than allowing a duplicate. The result is advisory:
 * capture reports the match and lets the caller decide whether to add, update, or abandon.
 */
export function findDuplicate(
  values: Readonly<Record<string, string>>,
  columns: readonly CaptureColumn[],
  rows: readonly Row[],
): DuplicateMatch | null {
  const identityColumns = columns.filter((c) => {
    const name = c.name.toLowerCase().replace(/[\s_-]+/g, "");
    return IDENTITY_KEYS.some((k) => name === k) || c.typeId === "url" || c.typeId === "doi";
  });

  for (const column of identityColumns) {
    const candidate = normalizeText(values[column.name] ?? "").toLowerCase();
    if (candidate === "") continue;
    for (const row of rows) {
      const existing = normalizeText(getField(row, column.name)).toLowerCase();
      if (existing !== "" && existing === candidate) {
        return { row, on: column.name, value: candidate };
      }
    }
  }
  return null;
}

/** Map a payload onto the view's columns, then fill in whatever defaults the columns declare. */
export function prepareCapture(payload: CapturePayload, columns: readonly CaptureColumn[]): MappedCapture {
  const mapped = mapToColumns(payload, columns);
  return { values: applyDefaults(mapped.values, columns), unmapped: mapped.unmapped };
}

function frontmatterValue(raw: string): string {
  // Quote anything that YAML would otherwise misread — a colon, a leading marker, or a bare number that
  // shouldn't become one. Cheap insurance against writing a note that won't parse.
  const value = raw.replace(/\r?\n/g, " ").trim();
  if (value === "") return '""';
  if (/^[-?:#&*!|>%@`[{]/.test(value) || value.includes(": ") || value.endsWith(":")) {
    return `"${value.replace(/"/g, '\\"')}"`;
  }
  return value;
}

/** Build a note whose frontmatter carries the captured fields, so views can read it back as a row. */
export function buildCapturedNote(
  values: Readonly<Record<string, string>>,
  options: { readonly url?: string; readonly body?: string } = {},
): string {
  const lines = ["---"];
  for (const [name, value] of Object.entries(values)) {
    if (normalizeText(value) === "") continue;
    lines.push(`${name}: ${frontmatterValue(value)}`);
  }
  if (options.url !== undefined && options.url.trim() !== "" && values["url"] === undefined) {
    lines.push(`url: ${frontmatterValue(options.url)}`);
  }
  lines.push("---", "");
  if (options.body !== undefined && options.body.trim() !== "") lines.push(options.body.trim(), "");
  return lines.join("\n");
}

/** Strip characters a vault file name can't hold, and keep it to a sane length. */
export function safeFileName(raw: string, fallback = "Captured"): string {
  const cleaned = normalizeText(raw)
    .replace(/[\\/:*?"<>|#^[\]]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return (cleaned === "" ? fallback : cleaned).slice(0, 80);
}

export class CaptureService {
  constructor(private readonly app: App) {}

  /**
   * Commit a prepared set of values to a view's capture target.
   *
   * The row path addresses the table directly rather than through an existing row, so a view whose table is
   * still empty — or not yet written — can receive its first item. That's the case the older add-row path
   * can't serve.
   */
  async commit(
    target: CaptureTarget,
    values: Readonly<Record<string, string>>,
    columns: readonly CaptureColumn[],
    payload: CapturePayload,
  ): Promise<CaptureResult> {
    // A capture may ask for a shape the view wasn't set up for. Honouring that is what makes keeping a whole
    // page possible from any view, rather than only from one somebody thought to configure for notes.
    const shape = payload.shape ?? target.shape;
    if (shape === "note") {
      const asNote: CaptureTarget =
        target.shape === "note" ? target : { ...target, shape: "note", folder: target.folder ?? "Captured" };
      return this.commitNote(asNote, values, payload);
    }
    return this.commitRow(target, values, columns);
  }

  /**
   * Commit several rows at once.
   *
   * Only meaningful for the row shape — capturing twenty notes from one page would be twenty files, which is
   * a different intention from filling in a table. When the target is note-shaped, this says so rather than
   * quietly producing something the caller didn't ask for.
   */
  async commitMany(
    target: CaptureTarget,
    rows: readonly Readonly<Record<string, string>>[],
    columns: readonly CaptureColumn[],
  ): Promise<CaptureResult & { written?: number }> {
    if (target.shape === "note") {
      return { ok: false, reason: "This view captures to notes, so it takes one item at a time." };
    }
    if (rows.length === 0) return { ok: false, reason: "No rows to write." };

    const path = normalizePath((target.notePath ?? "").trim());
    if (path === "" || path === ".") return { ok: false, reason: "This view has no capture target set." };

    let file = this.app.vault.getAbstractFileByPath(path);
    if (file === null && target.createIfMissing === true) {
      await this.ensureFolder(path);
      file = await this.app.vault.create(path, "");
    }
    if (!(file instanceof TFile)) return { ok: false, reason: `Capture target not found: ${path}` };

    const content = await this.app.vault.read(file);
    const result = appendCapturedRows(content, rows, {
      ...(target.heading !== undefined ? { heading: target.heading } : {}),
      ...(target.createIfMissing !== undefined ? { createIfMissing: target.createIfMissing } : {}),
      columns: columns.map((c) => c.name),
    });
    if (!result.ok) return { ok: false, reason: result.reason ?? "Couldn't write the rows." };

    await this.app.vault.modify(file, result.content);
    return { ok: true, path, createdTable: result.createdTable, written: rows.length };
  }

  private async commitRow(
    target: CaptureTarget,
    values: Readonly<Record<string, string>>,
    columns: readonly CaptureColumn[],
  ): Promise<CaptureResult> {
    const path = normalizePath((target.notePath ?? "").trim());
    if (path === "" || path === ".") return { ok: false, reason: "This view has no capture target set." };

    let file = this.app.vault.getAbstractFileByPath(path);
    if (file === null && target.createIfMissing === true) {
      await this.ensureFolder(path);
      file = await this.app.vault.create(path, "");
    }
    if (!(file instanceof TFile)) {
      return { ok: false, reason: `Capture target not found: ${path}` };
    }

    const content = await this.app.vault.read(file);
    const result = appendCapturedRow(content, values, {
      ...(target.heading !== undefined ? { heading: target.heading } : {}),
      ...(target.createIfMissing !== undefined ? { createIfMissing: target.createIfMissing } : {}),
      columns: columns.map((c) => c.name),
    });
    if (!result.ok) return { ok: false, reason: result.reason ?? "Couldn't write the row." };

    await this.app.vault.modify(file, result.content);
    return { ok: true, path, createdTable: result.createdTable };
  }

  /**
   * Write a captured note.
   *
   * The template does the work, using the same engine the companion previews with — so what someone saw
   * before pressing save is what lands. Without a template we fall back to frontmatter plus the body, which
   * is what the previous version produced, except that now there *is* a body.
   */
  private async commitNote(
    target: CaptureTarget,
    values: Readonly<Record<string, string>>,
    payload: CapturePayload,
  ): Promise<CaptureResult> {
    const note = payload.note;

    // Appending into an existing note is its own path: no template, no filename, no de-duplication —
    // the capture goes inside something that already exists, under a heading when one was named.
    const appendPath = (note?.appendTo?.path ?? "").trim();
    if (appendPath !== "") {
      const path = normalizePath(appendPath.endsWith(".md") ? appendPath : `${appendPath}.md`);
      const file = this.app.vault.getAbstractFileByPath(path);
      if (!(file instanceof TFile)) return { ok: false, reason: `No note at ${path}.` };

      const block = capturedAppendBlock(values, payload.url ?? "", payload.note?.body ?? "");
      if (block === "") return { ok: false, reason: "Nothing to append." };

      const existing = await this.app.vault.read(file);
      const result = appendToNote(existing, block, {
        ...(note?.appendTo?.heading !== undefined ? { heading: note.appendTo.heading } : {}),
        ...(note?.appendTo?.createHeading !== undefined ? { createHeading: note.appendTo.createHeading } : {}),
      });
      if (!result.ok) return { ok: false, reason: result.reason ?? "Couldn't append." };
      await this.app.vault.modify(file, result.content);
      return { ok: true, path };
    }
    const body = note?.body ?? "";
    const variables: Record<string, string> = { ...values, content: body };
    if (payload.url !== undefined && variables["url"] === undefined) variables["url"] = payload.url;
    if (variables["date"] === undefined) variables["date"] = new Date().toISOString();

    const template = note?.template ?? target.noteTemplate ?? "";
    const content =
      template.trim() === ""
        ? buildCapturedNote(values, {
            ...(payload.url !== undefined ? { url: payload.url } : {}),
            ...(body !== "" ? { body } : {}),
          })
        : renderTemplate(template, variables);

    // A name sent by the caller wins: they showed it to the person before saving.
    const fromCaller = (note?.fileName ?? "").trim();
    const fromTemplate =
      target.fileNameTemplate !== undefined && target.fileNameTemplate.trim() !== ""
        ? renderTemplate(target.fileNameTemplate, variables)
        : "";
    const titleKey = Object.keys(values).find((k) => k.toLowerCase() === "title");
    const base = templateSafeName(
      fromCaller !== "" ? fromCaller : fromTemplate !== "" ? fromTemplate : (titleKey ? values[titleKey] ?? "" : ""),
    );

    const folder = normalizePath((target.folder ?? "").trim());
    const dir = folder === "" || folder === "." ? "" : `${folder}/`;

    let path = normalizePath(`${dir}${base}.md`);
    let n = 2;
    while (this.app.vault.getAbstractFileByPath(path) !== null) {
      path = normalizePath(`${dir}${base} ${String(n)}.md`);
      n++;
    }

    await this.ensureFolder(path);
    await this.app.vault.create(path, content);
    return { ok: true, path };
  }

  /**
   * Link a new row to a note that already exists for the same page.
   *
   * The other direction of promotion: someone captured the note first — perhaps weeks ago — and is now
   * adding the row. The identities match (same frontmatter key, same value), so the wikilink is written at
   * capture time rather than left for anyone to notice and repair. Purely additive: no note, no link, no
   * change; and a link column the values already fill is left alone.
   */
  linkExistingNote(
    values: Readonly<Record<string, string>>,
    columns: readonly CaptureColumn[],
    matchKey: string,
  ): Record<string, string> {
    const out = { ...values };
    if (matchKey.trim() === "") return out;

    const linkColumn = noteLinkColumnName(columns.map((c) => ({ name: c.name, type: c.typeId })));
    if (linkColumn === null || (out[linkColumn] ?? "").trim() !== "") return out;

    const identity =
      out[matchKey] ??
      Object.entries(out).find(([name]) => name.toLowerCase() === matchKey.toLowerCase())?.[1] ??
      (matchKey.toLowerCase() === "source"
        ? Object.entries(out).find(([name]) => /^(url|link)$/i.test(name))?.[1]
        : undefined) ??
      "";
    if (identity.trim() === "") return out;

    const note = findDedicatedNote(this.app, matchKey, identity);
    if (note === null) return out;
    const name = note.path.replace(/\.md$/, "").split("/").pop() ?? note.basename;
    out[linkColumn] = `[[${name}]]`;
    return out;
  }

  /** Create any missing parent folders for a file path. */
  private async ensureFolder(filePath: string): Promise<void> {
    const parts = filePath.split("/");
    parts.pop();
    if (parts.length === 0) return;
    let sofar = "";
    for (const part of parts) {
      sofar = sofar === "" ? part : `${sofar}/${part}`;
      if (this.app.vault.getAbstractFileByPath(sofar) === null) {
        await this.app.vault.createFolder(sofar).catch(() => undefined);
      }
    }
  }
}
