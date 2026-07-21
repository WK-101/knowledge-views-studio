import { getField, type Row } from "../../domain/index";
import { asString } from "../../util/coerce";
import type { Profile } from "../profile/profile";
import { findDuplicate, prepareCapture, type CaptureService } from "../capture/capture-service";
import { effectiveTarget } from "../capture/parse";
import type { CaptureColumn, CapturePayload } from "../capture/types";
import { isViewExposed } from "./policy";
import type { Route } from "./router";
import {
  BRIDGE_PROTOCOL,
  type BridgeSettings,
  type CaptureRequest,
  type CaptureResponse,
  type LookupMatch,
  type LookupRequest,
  type LookupResponse,
  type SchemaColumn,
  type KnownRequest,
  type KnownResponse,
  type AnnotationsClearRequest,
  type AnnotationsClearResponse,
  type NoteDeleteRequest,
  type NoteDeleteResponse,
  type RowDeleteRequest,
  type RowDeleteResponse,
  type AnnotateRequest,
  type AnnotateResponse,
  type AnnotationRemoveRequest,
  type AnnotationRemoveResponse,
  type AnnotationsRequest,
  type AnnotationsResponse,
  type PromoteRequest,
  type PromoteResponse,
  type RowsRequest,
  type RowsResponse,
  type RowsRow,
  type PingResponse,
  type SchemaResponse,
  type SchemaView,
  type SearchMode,
  type SearchRequest,
  type SearchResponse,
  type UpdateRequest,
  type UpdateResponse,
} from "./types";
import { normalizeUrl } from "../../../shared/protocol";
import { editableChanges, findRowByRef, rowRefOf } from "./row-ref";
import { noteLinkColumnName } from "../../views/promoted-detect";
import { dedicatedNoteKeyFor } from "../notes/dedicated-note";
import { annotationColumn, cellWithoutAnnotation, readWireAnnotation, rowForUrl } from "./annotate-plan";
import { annotationCellText, type StoredAnnotation } from "../../../shared/annotations";
import { identityCell } from "../notes/promotion-plan";

/**
 * What the bridge actually does.
 *
 * Each handler is a plain function over a context, so the whole set can be exercised with fakes — no server,
 * no vault. Handlers are registered rather than hard-wired, which is what lets the endpoint list grow (search
 * and annotations are the obvious next ones) without touching anything that already works.
 */

export interface BridgeContext {
  readonly vaultName: string;
  /** The plugin's manifest version, reported so an out-of-date vault names itself. */
  readonly pluginVersion?: string;
  readonly settings: () => BridgeSettings;
  readonly listProfiles: () => readonly Profile[];
  /** Rows and resolved columns for a view — the same pair the in-app capture command uses. */
  readonly viewData: (profile: Profile) => Promise<{ rows: readonly Row[]; columns: readonly CaptureColumn[] }>;
  readonly capture: CaptureService;
  /** Delete rows through the shared writer, with its undo path. Absent when deleting isn't available. */
  readonly deleteRows?: (rows: readonly Row[]) => Promise<number>;
  /** Move the page's dedicated note to the vault's trash. Absent when unavailable. */
  readonly trashNoteForUrl?: (url: string) => Promise<string | null>;
  /** The dedicated note's path for a page, by identity, or null. */
  readonly noteForUrl?: (url: string) => string | null;
  /** Web-annotation storage and note feeding. Absent when annotations aren't available. */
  readonly webAnnotations?: {
    list(url: string): Promise<readonly StoredAnnotation[]>;
    save(annotation: StoredAnnotation): Promise<void>;
    remove(url: string, id: string): Promise<StoredAnnotation | null>;
    removeAll(url: string): Promise<number>;
    appendToDedicatedNote(matchKey: string, matchValue: string, annotation: StoredAnnotation): Promise<boolean>;
  };
  /** Create or find a row's dedicated note. Absent when promotion isn't available. */
  readonly promote?: (
    profile: Profile,
    row: Row,
    columns: readonly { readonly name: string; readonly type?: string }[],
  ) => Promise<{ ok: boolean; path?: string; created?: boolean; reason?: string }>;
  /** Apply cell edits. Absent when writing back isn't available at all. */
  readonly editCells?: (edits: readonly { provenance: Row["provenance"]; column: string; value: string }[]) => Promise<void>;
  readonly onCaptured: (path: string) => void;
  /** Complete a pairing with a typed code. */
  readonly pair: (code: string) => { ok: true; token: string } | { ok: false; reason: string };
  /**
   * Search the vault. Absent when the plugin's search is switched off entirely, in which case the endpoint
   * says so plainly rather than returning an empty list that would read as "nothing found".
   */
  readonly search?: (request: SearchRequest) => Promise<SearchResponse>;
}

function badRequest(reason: string): { status: number; body: unknown } {
  return { status: 400, body: { error: reason } };
}

/** The views this caller is allowed to see, in the order the vault lists them. */
function exposedProfiles(context: BridgeContext): readonly Profile[] {
  const settings = context.settings();
  return context.listProfiles().filter((p) => isViewExposed(p.id, settings));
}

function describeColumn(column: CaptureColumn): SchemaColumn {
  return {
    name: column.name,
    typeId: column.typeId,
    ...(column.role !== undefined && column.role !== "none" ? { role: column.role } : {}),
    ...(column.options && column.options.length > 0
      ? { options: column.options.map((o) => o.value) }
      : {}),
  };
}

/**
 * `GET /schema` — what this vault's views look like.
 *
 * This is the endpoint that makes the companion different from a clipper. Sending the columns, their types
 * and the vocabulary each choice column already uses means the extension can render a correct, validating
 * form for a view it has never seen, with no template written by hand.
 */
export function schemaRoute(): Route<BridgeContext> {
  return {
    method: "GET",
    path: "/schema",
    permission: "read",
    handler: async (_request, context) => {
      const settings = context.settings();
      const views: SchemaView[] = [];
      for (const profile of exposedProfiles(context)) {
        const { columns } = await context.viewData(profile);
        const target = effectiveTarget(profile);
        views.push({
          id: profile.id,
          name: profile.name,
          columns: columns.map(describeColumn),
          capture:
            target === null
              ? { writable: false, reason: "No capture target is set for this view." }
              : settings.allowWrite
                ? { writable: true, shape: target.shape }
                : { writable: false, shape: target.shape, reason: "Writing through the bridge is turned off." },
        });
      }
      const body: SchemaResponse = {
        vault: context.vaultName,
        protocol: BRIDGE_PROTOCOL,
        ...(context.pluginVersion !== undefined ? { pluginVersion: context.pluginVersion } : {}),
        views,
      };
      return { status: 200, body };
    },
  };
}

/**
 * `POST /lookup` — is this already in the vault?
 *
 * Matching is on identity only (a URL or a DOI), never a title, for the same reason capture works that way:
 * two different things can share a name, and a false match is worse than a missed one.
 */
export function lookupRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/lookup",
    permission: "read",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as LookupRequest;
      const url = (body.url ?? "").trim();
      const doi = (body.doi ?? "").trim();
      if (url === "" && doi === "") return badRequest("Provide a url or a doi to look up.");

      const wanted = body.viewIds;
      const matches: LookupMatch[] = [];
      for (const profile of exposedProfiles(context)) {
        if (wanted !== undefined && !wanted.includes(profile.id)) continue;
        const { rows, columns } = await context.viewData(profile);
        const probe: Record<string, string> = {};
        for (const column of columns) {
          const name = column.name.toLowerCase().replace(/[\s_-]+/g, "");
          if (url !== "" && (name === "url" || column.typeId === "url")) probe[column.name] = url;
          if (doi !== "" && (name === "doi" || column.typeId === "doi")) probe[column.name] = doi;
        }
        if (Object.keys(probe).length === 0) continue;

        const hit = findDuplicate(probe, columns, rows);
        if (hit !== null) {
          const titleColumn = columns.find((c) => c.role === "title") ?? columns[0];
          const linkColumn = noteLinkColumnName(
            columns.map((c) => ({ name: c.name, type: c.typeId })),
          );
          const hasNote = linkColumn !== null && getField(hit.row, linkColumn).trim() !== "";
          matches.push({
            viewId: profile.id,
            viewName: profile.name,
            rowRef: rowRefOf(hit.row.provenance),
            ...(hasNote ? { hasNote: true } : {}),
            on: hit.on,
            title: titleColumn ? getField(hit.row, titleColumn.name) : "",
            filePath: hit.row.provenance.filePath,
          });
        }
      }
      // The page-level note, found by identity whether or not any row links it — the "note first, no row
      // yet" state has to be visible for the surface to offer creating its row.
      const notePath = url !== "" ? context.noteForUrl?.(url) ?? null : null;
      const response: LookupResponse = {
        matches,
        ...(notePath !== null ? { note: { path: notePath } } : {}),
      };
      return { status: 200, body: response };
    },
  };
}

/**
 * `POST /capture` — commit something to a view.
 *
 * Duplicates are reported alongside a successful write rather than blocking it, matching the in-app command:
 * the caller is told what matched and can decide what to do, instead of the bridge quietly refusing.
 */
export function captureRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/capture",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as CaptureRequest;
      const viewId = (body.viewId ?? "").trim();
      if (viewId === "") return badRequest("Which view should this go to?");
      const manyRows: unknown = body.rows;
      const isMany = Array.isArray(manyRows) && manyRows.length > 0;
      const incoming: unknown = isMany ? [] : body.fields;
      if (!isMany && !Array.isArray(incoming)) return badRequest("Expected a list of fields.");

      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) {
        // Same answer whether the view is hidden or absent, so the bridge can't be used to enumerate views
        // that were deliberately not exposed.
        return { status: 404, body: { error: "No such view." } };
      }

      const target = effectiveTarget(profile);
      if (target === null) {
        const failed: CaptureResponse = { ok: false, reason: "No capture target is set for this view." };
        return { status: 409, body: failed };
      }

      if (isMany) {
        const { rows: existing, columns } = await context.viewData(profile);
        const prepared = (manyRows as readonly (readonly { key?: unknown; value?: unknown }[])[])
          .map((row) => ({
            fields: row.map((f) => ({ key: asString(f.key), value: asString(f.value) })),
          }))
          .map((p) => prepareCapture(p, columns).values)
          .filter((values) => Object.keys(values).length > 0);

        if (prepared.length === 0) {
          const failed: CaptureResponse = { ok: false, reason: "None of those rows matched this view." };
          return { status: 422, body: failed };
        }
        // Duplicates are counted, not blocked: reviewing twenty rows one refusal at a time would be worse
        // than importing a few things twice.
        const already = prepared.filter((values) => findDuplicate(values, columns, existing) !== null).length;
        const written = await context.capture.commitMany(target, prepared, columns);
        if (!written.ok) {
          const failed: CaptureResponse = { ok: false, reason: written.reason ?? "Could not write." };
          return { status: 500, body: failed };
        }
        if (written.path !== undefined) context.onCaptured(written.path);
        const manyResponse: CaptureResponse = {
          ok: true,
          written: prepared.length,
          ...(written.path !== undefined ? { path: written.path } : {}),
          ...(written.createdTable === true ? { createdTable: true } : {}),
          ...(already > 0 ? { duplicate: { on: `${String(already)} already present`, filePath: written.path ?? "" } } : {}),
        };
        return { status: 200, body: manyResponse };
      }

      const note = body.note;
      const payload: CapturePayload = {
        fields: (incoming as readonly { key?: unknown; value?: unknown }[]).map((f) => ({
          key: asString(f.key),
          value: asString(f.value),
        })),
        ...(typeof body.url === "string" && body.url.trim() !== "" ? { url: body.url.trim() } : {}),
        ...(body.shape === "row" || body.shape === "note" ? { shape: body.shape } : {}),
        ...(note !== undefined
          ? {
              note: {
                ...(typeof note.fileName === "string" ? { fileName: note.fileName } : {}),
                ...(typeof note.body === "string" ? { body: note.body } : {}),
                ...(typeof note.template === "string" ? { template: note.template } : {}),
                ...(note.appendTo !== undefined && typeof note.appendTo.path === "string"
                  ? {
                      appendTo: {
                        path: note.appendTo.path,
                        ...(typeof note.appendTo.heading === "string" ? { heading: note.appendTo.heading } : {}),
                        ...(note.appendTo.createHeading === true ? { createHeading: true } : {}),
                      },
                    }
                  : {}),
              },
            }
          : {}),
      };

      const { rows, columns } = await context.viewData(profile);
      const { values, unmapped } = prepareCapture(payload, columns);
      if (Object.keys(values).length === 0) {
        const failed: CaptureResponse = { ok: false, reason: "Nothing in that payload matched this view." };
        return { status: 422, body: failed };
      }

      const duplicate = findDuplicate(values, columns, rows);
      // The reverse of promotion: when a note already exists for this page's identity, the new row gets
      // its wikilink at capture time — nobody has to notice the pair and connect them by hand.
      const linked =
        typeof context.capture.linkExistingNote === "function"
          ? context.capture.linkExistingNote(values, columns, dedicatedNoteKeyFor(profile))
          : values;
      const written = await context.capture.commit(target, linked, columns, payload);
      if (!written.ok) {
        const failed: CaptureResponse = { ok: false, reason: written.reason ?? "Could not write." };
        return { status: 500, body: failed };
      }
      if (written.path !== undefined) context.onCaptured(written.path);

      // A write can succeed into a file the view never reads — a capture target outside the view's scope
      // — and from the outside that is indistinguishable from silent data loss: "I saved it and it isn't
      // there." Re-reading the view and checking makes the misconfiguration speak at the moment it
      // happens, naming both the file and the fix.
      let warning: string | undefined;
      if (written.path !== undefined && target.shape !== "note") {
        const after = await context.viewData(profile);
        const visible = after.rows.some((r) => r.provenance.filePath === written.path);
        if (!visible) {
          warning = `Saved to “${written.path}”, but that file isn't one this view reads — the row won't appear in “${profile.name}”. Add the file to the view's sources, or point the view's capture target at a file it reads.`;
        } else if ((payload.url ?? "") !== "" && rowForUrl(after.rows, after.columns, payload.url ?? "") === null) {
          // The row exists and the view shows it — but nothing in it holds the page's URL, so lookup can
          // never recognise this page as captured. Without this sentence, the symptom is a page that stays
          // "not in any of your views" forever, one save after another, each one apparently successful.
          warning = `Saved — but no cell holds this page's URL, so “${profile.name}” can't recognise the page as already captured. Add a URL/Link/Source column to the view (or map one), and future captures will be recognised.`;
        }
      }

      const response: CaptureResponse = {
        ok: true,
        ...(warning !== undefined ? { warning } : {}),
        ...(written.path !== undefined ? { path: written.path } : {}),
        ...(written.createdTable === true ? { createdTable: true } : {}),
        ...(duplicate !== null
          ? { duplicate: { on: duplicate.on, filePath: duplicate.row.provenance.filePath } }
          : {}),
        ...(unmapped.length > 0 ? { unmapped: unmapped.map((f) => f.key) } : {}),
      };
      return { status: 200, body: response };
    },
  };
}


/**
 * `POST /search` — search the vault from the browser.
 *
 * This is what makes the companion a companion rather than a clipper. Comparable extensions have to borrow
 * someone else's search plugin to do this at all; here the index is already in the same process, so keyword,
 * meaning-based and question-answering search all come from one place — and they reach rows, annotations,
 * attachments and Zotero, not just note titles.
 *
 * Behind its own permission: telling a caller what your views are shaped like is a far smaller thing than
 * letting it read what's inside your notes.
 */
export function searchRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/search",
    permission: "search",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as SearchRequest;
      const query = (body.query ?? "").trim();
      if (query === "") return badRequest("Nothing to search for.");
      if (context.search === undefined) {
        return { status: 503, body: { error: "Search isn't enabled in this vault." } };
      }
      const mode: SearchMode = body.mode === "semantic" || body.mode === "ask" ? body.mode : "keyword";
      const limit = Math.min(Math.max(Number(body.limit ?? 20) || 20, 1), 50);
      const result = await context.search({ query, mode, limit });
      return { status: 200, body: result };
    },
  };
}


/**
 * `GET /ping` — "is a KVS bridge listening here?"
 *
 * Exists so nobody has to be told a port number. The companion tries a short list and stops at the first
 * bridge that answers, which removes the one setup step people most often get wrong.
 *
 * It answers with the protocol version and nothing else: not the vault's name, not whether anything is
 * paired, not what views exist. Combined with the origin rule — an unlisted web page can't reach the bridge
 * at all — that keeps a convenience from turning into a way for a website to learn what you have installed.
 */
export function pingRoute(): Route<BridgeContext> {
  return {
    method: "GET",
    path: "/ping",
    permission: "public",
    handler: (_request, context) => {
      const body: PingResponse = {
        kvs: true,
        protocol: BRIDGE_PROTOCOL,
        ...(context.pluginVersion !== undefined ? { pluginVersion: context.pluginVersion } : {}),
      };
      return { status: 200, body };
    },
  };
}


/**
 * `POST /known` — which of these pages do I already have?
 *
 * Answers with the URLs and nothing else: no titles, no paths, no view names. This is called by a script
 * running on a search results page, and such a script has no business learning what the vault contains
 * beyond the question it asked. URLs are compared after normalisation, so a result carrying campaign
 * parameters still recognises the page you saved without them.
 */
export function knownRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/known",
    permission: "read",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as KnownRequest;
      const asked: unknown = body.urls;
      if (!Array.isArray(asked) || asked.length === 0) return badRequest("Provide urls to check.");
      const urls = (asked as readonly unknown[])
        .map((u) => asString(u))
        .filter((u) => u !== "")
        .slice(0, 200);
      if (urls.length === 0) return badRequest("Provide urls to check.");

      const wanted = new Map<string, string>();
      for (const url of urls) wanted.set(normalizeUrl(url), url);

      const found = new Set<string>();
      for (const profile of exposedProfiles(context)) {
        if (body.viewIds !== undefined && !body.viewIds.includes(profile.id)) continue;
        const { rows, columns } = await context.viewData(profile);
        const urlColumns = columns.filter(
          (c) => c.typeId === "url" || c.name.toLowerCase().replace(/[\s_-]+/g, "") === "url",
        );
        if (urlColumns.length === 0) continue;
        for (const row of rows) {
          for (const column of urlColumns) {
            const value = normalizeUrl(getField(row, column.name));
            if (value === "") continue;
            const original = wanted.get(value);
            if (original !== undefined) found.add(original);
          }
        }
        if (found.size === wanted.size) break;
      }
      const response: KnownResponse = { known: [...found] };
      return { status: 200, body: response };
    },
  };
}

/**
 * `POST /update` — change a row you already have.
 *
 * The capability that makes this a companion rather than a collector. Marking something read, setting a
 * rating, moving a status: all things you decide while looking at the page, and all previously impossible
 * without switching to Obsidian and finding the row by hand.
 *
 * Two safeguards, both deliberate. The row reference is **matched** against rows the vault produced rather
 * than dereferenced, so a forged or stale handle finds nothing and the edit is refused instead of writing
 * somewhere nobody intended. And read-only fields are re-checked here, because in the app they're enforced
 * by the editing surface — nothing below it would stop a computed value being overwritten with a literal.
 */
export function updateRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/update",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as UpdateRequest;
      const viewId = (body.viewId ?? "").trim();
      const rowRef = (body.rowRef ?? "").trim();
      if (viewId === "" || rowRef === "") return badRequest("Which row, in which view?");
      const asked: unknown = body.values;
      if (!Array.isArray(asked) || asked.length === 0) return badRequest("Nothing to change.");
      if (context.editCells === undefined) {
        return { status: 503, body: { error: "Editing isn't available in this vault." } };
      }

      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) return { status: 404, body: { error: "No such view." } };

      const { rows, columns } = await context.viewData(profile);
      const row = findRowByRef(rows, rowRef);
      if (row === null) {
        // Either it's gone or it changed since the caller last looked. Applying the edit to whatever now
        // occupies that position would be worse than refusing.
        const stale: UpdateResponse = { ok: false, reason: "That row has changed or is no longer there." };
        return { status: 409, body: stale };
      }

      const values = (asked as readonly { key?: unknown; value?: unknown; mode?: unknown }[]).map((v) => ({
        key: asString(v.key),
        value: asString(v.value),
        ...(v.mode === "append" ? { mode: "append" as const } : {}),
      }));
      const { allowed, skipped } = editableChanges(row, values, columns);
      if (allowed.length === 0) {
        const nothing: UpdateResponse = {
          ok: false,
          reason: "None of those columns can be written.",
          ...(skipped.length > 0 ? { skipped } : {}),
        };
        return { status: 422, body: nothing };
      }

      await context.editCells(
        allowed.map((change) => ({ provenance: row.provenance, column: change.column, value: change.value })),
      );
      context.onCaptured(row.provenance.filePath);

      const response: UpdateResponse = {
        ok: true,
        updated: allowed.map((c) => c.column),
        ...(skipped.length > 0 ? { skipped } : {}),
      };
      return { status: 200, body: response };
    },
  };
}


/**
 * `POST /rows` — read a view.
 *
 * What makes a dashboard possible outside Obsidian. Everything before this could answer questions *about* a
 * page; this hands back the view itself, so the companion can show a reading queue or a paper list in a
 * sidebar and let someone work through it without switching applications.
 *
 * Every row carries the same opaque handle `/update` expects, and the columns it doesn't own, so the surface
 * showing it knows what may be edited before anyone tries — rather than discovering it from a refusal.
 */
export function rowsRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/rows",
    permission: "read",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as RowsRequest;
      const viewId = (body.viewId ?? "").trim();
      if (viewId === "") return badRequest("Which view?");

      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) return { status: 404, body: { error: "No such view." } };

      const { rows, columns } = await context.viewData(profile);

      // Narrowing happens here rather than in the caller so a large view doesn't cross the wire first.
      const query = (body.query ?? "").trim().toLowerCase();
      const url = normalizeUrl((body.url ?? "").trim());
      const filtered = rows.filter((row) => {
        if (url !== "") {
          const matchesUrl = columns.some((column) => normalizeUrl(getField(row, column.name)) === url);
          if (!matchesUrl) return false;
        }
        if (query === "") return true;
        return columns.some((column) => getField(row, column.name).toLowerCase().includes(query));
      });

      const pageSize = Math.min(Math.max(Number(body.pageSize ?? 50) || 50, 1), 200);
      const page = Math.max(Number(body.page ?? 1) || 1, 1);
      const start = (page - 1) * pageSize;

      const shown: RowsRow[] = filtered.slice(start, start + pageSize).map((row) => {
        const cells: Record<string, string> = {};
        for (const column of columns) cells[column.name] = getField(row, column.name);
        const readOnly = row.provenance.readOnlyFields ?? [];
        return {
          rowRef: rowRefOf(row.provenance),
          cells,
          ...(readOnly.length > 0 ? { readOnly: [...readOnly] } : {}),
        };
      });

      const response: RowsResponse = {
        ok: true,
        columns: columns.map((column) => describeColumn(column)),
        rows: shown,
        total: filtered.length,
        page,
        pageSize,
      };
      return { status: 200, body: response };
    },
  };
}


/**
 * `POST /promote` — a row's dedicated note, in one click.
 *
 * The second half of the workflow the capture model exists for: the row collects the page's data, and when
 * a page turns out to deserve its own note, this makes one — pre-filled from the row, linked both ways.
 * Idempotent: the note is found before it is created, so promoting twice opens what the first promote made
 * rather than manufacturing a duplicate.
 */
export function promoteRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/promote",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as PromoteRequest;
      const viewId = (body.viewId ?? "").trim();
      const rowRef = (body.rowRef ?? "").trim();
      if (viewId === "" || rowRef === "") return badRequest("Which row, in which view?");
      if (context.promote === undefined) {
        return { status: 503, body: { error: "Promotion isn't available in this vault." } };
      }

      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) return { status: 404, body: { error: "No such view." } };

      const { rows, columns } = await context.viewData(profile);
      const row = findRowByRef(rows, rowRef);
      if (row === null) {
        const stale: PromoteResponse = { ok: false, reason: "That row has changed or is no longer there." };
        return { status: 409, body: stale };
      }

      const outcome = await context.promote(profile, row, columns);
      if (!outcome.ok) {
        const failed: PromoteResponse = { ok: false, reason: outcome.reason ?? "Couldn't create the note." };
        return { status: 422, body: failed };
      }
      if (outcome.path !== undefined) context.onCaptured(row.provenance.filePath);
      const response: PromoteResponse = {
        ok: true,
        ...(outcome.path !== undefined ? { path: outcome.path } : {}),
        ...(outcome.created !== undefined ? { created: outcome.created } : {}),
      };
      return { status: 200, body: response };
    },
  };
}


/**
 * `POST /annotate` — a highlight lands everywhere it belongs, in one call.
 *
 * Sidecar for repainting; the page's row for the glanceable copy; the dedicated note, when there is one,
 * for the write-up. And when the page has no row yet, one is created first from the metadata the caller
 * sent — a highlight is the strongest signal a page matters, and it shouldn't be lost to filing order.
 *
 * The sidecar write comes LAST. If anything earlier fails, nothing claims the highlight exists; a sidecar
 * entry whose row copy silently failed would paint a highlight the vault has no record of.
 */
export function annotateRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/annotate",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as AnnotateRequest;
      const viewId = (body.viewId ?? "").trim();
      const url = (body.url ?? "").trim();
      if (viewId === "" || url === "") return badRequest("Which page, into which view?");
      const annotation = readWireAnnotation(body.annotation, url);
      if (annotation === null) return badRequest("That highlight has no text to anchor.");
      if (context.webAnnotations === undefined) {
        return { status: 503, body: { error: "Annotations aren't available in this vault." } };
      }

      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) return { status: 404, body: { error: "No such view." } };

      let { rows, columns } = await context.viewData(profile);
      let target = rowForUrl(rows, columns, url);
      let createdRow = false;

      if (target === null) {
        // No row yet: make one from the page metadata, then find it again in the re-read view.
        const fields: readonly { key?: unknown; value?: unknown }[] = Array.isArray(body.fields)
          ? (body.fields as readonly { key?: unknown; value?: unknown }[])
          : [];
        const payload: CapturePayload = {
          fields: fields.map((f) => ({ key: asString(f.key), value: asString(f.value) })),
          url,
        };
        const { values } = prepareCapture(payload, columns);
        if (Object.keys(values).length === 0) {
          const failed: AnnotateResponse = {
            ok: false,
            reason: "This page has no row in that view, and there wasn't enough metadata to create one.",
          };
          return { status: 422, body: failed };
        }
        const captureTarget = effectiveTarget(profile);
        if (captureTarget === null) {
          const failed: AnnotateResponse = { ok: false, reason: "That view has no capture target set." };
          return { status: 422, body: failed };
        }
        const linked =
          typeof context.capture.linkExistingNote === "function"
            ? context.capture.linkExistingNote(values, columns, dedicatedNoteKeyFor(profile))
            : values;
        const written = await context.capture.commit(captureTarget, linked, columns, payload);
        if (!written.ok) {
          const failed: AnnotateResponse = { ok: false, reason: written.reason ?? "Couldn't create the row." };
          return { status: 500, body: failed };
        }
        if (written.path !== undefined) context.onCaptured(written.path);
        createdRow = true;
        ({ rows, columns } = await context.viewData(profile));
        target = rowForUrl(rows, columns, url);
      }

      // The row copy: appended through the same guarded path as any other edit.
      let wroteCell = false;
      if (target !== null && context.editCells !== undefined) {
        const column = annotationColumn(columns);
        if (column !== null) {
          const { allowed } = editableChanges(
            target,
            [{ key: column, value: annotationCellText(annotation), mode: "append" }],
            columns,
          );
          if (allowed.length > 0) {
            await context.editCells(
              allowed.map((change) => ({ provenance: target.provenance, column: change.column, value: change.value })),
            );
            context.onCaptured(target.provenance.filePath);
            wroteCell = true;
          }
        }
      }

      // The note copy, when the page has a dedicated note.
      let wroteNote = false;
      if (target !== null) {
        const matchKey = dedicatedNoteKeyFor(profile);
        const cells: Record<string, string> = {};
        for (const column of columns) cells[column.name] = getField(target, column.name);
        const matchValue = identityCell(cells, matchKey);
        if (matchValue !== "") {
          wroteNote = await context.webAnnotations.appendToDedicatedNote(matchKey, matchValue, annotation);
        }
      }

      await context.webAnnotations.save(annotation);

      const response: AnnotateResponse = {
        ok: true,
        ...(target !== null ? { rowRef: rowRefOf(target.provenance) } : {}),
        ...(createdRow ? { createdRow: true } : {}),
        ...(wroteCell ? { wroteCell: true } : {}),
        ...(wroteNote ? { wroteNote: true } : {}),
      };
      return { status: 200, body: response };
    },
  };
}

/** `POST /annotations` — everything saved for a page, for repainting on revisit. */
export function annotationsRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/annotations",
    permission: "read",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as AnnotationsRequest;
      const url = (body.url ?? "").trim();
      if (url === "") return badRequest("Which page?");
      if (context.webAnnotations === undefined) {
        const empty: AnnotationsResponse = { ok: true, annotations: [] };
        return { status: 200, body: empty };
      }
      const stored = await context.webAnnotations.list(url);
      const response: AnnotationsResponse = {
        ok: true,
        annotations: stored.map((a) => ({
          id: a.id,
          anchor: a.anchor,
          color: a.color,
          createdAt: a.createdAt,
          ...(a.note !== undefined ? { note: a.note } : {}),
        })),
      };
      return { status: 200, body: response };
    },
  };
}

/**
 * `POST /annotate/remove` — delete a highlight, and clean up its row line.
 *
 * The cell cleanup strips exactly the line the annotation wrote, matched whole — a line someone reworded by
 * hand no longer matches and therefore survives, which is the right way round: their edit outranks our
 * bookkeeping.
 */
export function annotateRemoveRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/annotate/remove",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as AnnotationRemoveRequest;
      const url = (body.url ?? "").trim();
      const id = (body.id ?? "").trim();
      if (url === "" || id === "") return badRequest("Which highlight, on which page?");
      if (context.webAnnotations === undefined) {
        return { status: 503, body: { error: "Annotations aren't available in this vault." } };
      }

      const removed = await context.webAnnotations.remove(url, id);
      let removedFromCell = false;

      const viewId = (body.viewId ?? "").trim();
      if (removed !== null && viewId !== "" && context.editCells !== undefined) {
        const profile = exposedProfiles(context).find((p) => p.id === viewId);
        if (profile !== undefined) {
          const { rows, columns } = await context.viewData(profile);
          const target = rowForUrl(rows, columns, url);
          const column = target !== null ? annotationColumn(columns) : null;
          if (target !== null && column !== null) {
            const cleaned = cellWithoutAnnotation(getField(target, column), removed);
            if (cleaned !== null) {
              const { allowed } = editableChanges(target, [{ key: column, value: cleaned }], columns);
              if (allowed.length > 0) {
                await context.editCells(
                  allowed.map((c) => ({ provenance: target.provenance, column: c.column, value: c.value })),
                );
                context.onCaptured(target.provenance.filePath);
                removedFromCell = true;
              }
            }
          }
        }
      }

      const response: AnnotationRemoveResponse = {
        ok: true,
        ...(removedFromCell ? { removedFromCell: true } : {}),
      };
      return { status: 200, body: response };
    },
  };
}


/**
 * `POST /row/delete` — remove one row, named by its handle.
 *
 * Deliberately row-scoped: the dedicated note is never touched here. A row is regenerable metadata; a note
 * may hold hours of someone's writing, and the two must not share a delete button.
 */
export function rowDeleteRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/row/delete",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as RowDeleteRequest;
      const viewId = (body.viewId ?? "").trim();
      const rowRef = (body.rowRef ?? "").trim();
      if (viewId === "" || rowRef === "") return badRequest("Which row, in which view?");
      if (context.deleteRows === undefined) {
        return { status: 503, body: { error: "Deleting rows isn't available in this vault." } };
      }
      const profile = exposedProfiles(context).find((p) => p.id === viewId);
      if (profile === undefined) return { status: 404, body: { error: "No such view." } };
      const { rows } = await context.viewData(profile);
      const row = findRowByRef(rows, rowRef);
      if (row === null) {
        const stale: RowDeleteResponse = { ok: false, reason: "That row has changed or is already gone." };
        return { status: 409, body: stale };
      }
      const removed = await context.deleteRows([row]);
      context.onCaptured(row.provenance.filePath);
      const response: RowDeleteResponse =
        removed > 0 ? { ok: true } : { ok: false, reason: "The row couldn't be removed from its file." };
      return { status: removed > 0 ? 200 : 500, body: response };
    },
  };
}

/**
 * `POST /note/delete` — the page's dedicated note goes to the vault's trash. Trash, not deletion:
 * a note is writing, and writing deserves an undo.
 */
export function noteDeleteRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/note/delete",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as NoteDeleteRequest;
      const url = (body.url ?? "").trim();
      if (url === "") return badRequest("Which page?");
      if (context.trashNoteForUrl === undefined) {
        return { status: 503, body: { error: "Deleting notes isn't available in this vault." } };
      }
      const trashed = await context.trashNoteForUrl(url);
      const response: NoteDeleteResponse =
        trashed !== null
          ? { ok: true, trashedPath: trashed }
          : { ok: false, reason: "No dedicated note exists for this page." };
      return { status: trashed !== null ? 200 : 404, body: response };
    },
  };
}

/** `POST /annotations/clear` — every highlight for a page, gone from the sidecar in one call. */
export function annotationsClearRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/annotations/clear",
    permission: "write",
    handler: async (request, context) => {
      const body = (request.body ?? {}) as AnnotationsClearRequest;
      const url = (body.url ?? "").trim();
      if (url === "") return badRequest("Which page?");
      if (context.webAnnotations === undefined) {
        return { status: 503, body: { error: "Annotations aren't available in this vault." } };
      }
      const removed = await context.webAnnotations.removeAll(url);
      const response: AnnotationsClearResponse = { ok: true, removed };
      return { status: 200, body: response };
    },
  };
}

/** `POST /pair` — exchange a short code shown in settings for a lasting token. */
export function pairRoute(): Route<BridgeContext> {
  return {
    method: "POST",
    path: "/pair",
    permission: "public",
    handler: (request, context) => {
      const body = (request.body ?? {}) as { code?: unknown };
      const code = typeof body.code === "string" ? body.code : "";
      if (code.trim() === "") return badRequest("A pairing code is required.");
      const result = context.pair(code);
      if (!result.ok) return { status: 401, body: { error: result.reason } };
      return { status: 200, body: { token: result.token, vault: context.vaultName, protocol: BRIDGE_PROTOCOL } };
    },
  };
}

/** The endpoints the bridge ships with. Registering rather than hard-wiring keeps the list open. */
export function defaultRoutes(): readonly Route<BridgeContext>[] {
  return [
    pingRoute(),
    pairRoute(),
    schemaRoute(),
    lookupRoute(),
    knownRoute(),
    rowsRoute(),
    captureRoute(),
    promoteRoute(),
    annotateRoute(),
    annotationsRoute(),
    annotateRemoveRoute(),
    rowDeleteRoute(),
    noteDeleteRoute(),
    annotationsClearRoute(),
    updateRoute(),
    searchRoute(),
  ];
}
