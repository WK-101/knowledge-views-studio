import type { LookupResponse, SchemaResponse } from "../../../shared/protocol";
import { loadPreferences } from "./preferences";
import { zoteroSave, type ZoteroSaveItem } from "./zotero-client";
import {
  annotationsClear,
  annotationsFor,
  loadConnection,
  lookup,
  noteDelete,
  obsidianLink,
  rowDelete,
} from "./bridge-client";

/**
 * The first thing capture shows: where this page already is.
 *
 * The old first screen led with a destination picker and a form — the machinery of adding — when the
 * question a person actually starts with is *do I already have this?* The card answers that before anything
 * else: every view whose row names this page (with the file it lives in, and the way to open either), the
 * page's dedicated note, its highlights — and for each thing that exists, what can be done to it; for each
 * that doesn't, the way to make it. Nothing shows a button for a state it isn't in: a delete for a note
 * that doesn't exist is noise at best and a lie at worst.
 */

export interface StatusActions {
  /** Reveal the add flow (view picker + form), preselecting a shape. */
  readonly onAdd: (shape: "row" | "note") => void;
  /** Jump to the edit tab for a matched row. */
  readonly onEdit: (viewId: string, rowRef: string) => void;
  /** Re-render the card after something changed. */
  readonly refresh: () => void;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

/** A button that asks once before doing something destructive. */
function confirmButton(label: string, act: () => Promise<void>): HTMLButtonElement {
  const button = el("button", { class: "mini danger", type: "button" }, label);
  let armed = false;
  button.addEventListener("click", () => {
    if (!armed) {
      armed = true;
      button.textContent = "Sure?";
      window.setTimeout(() => {
        armed = false;
        button.textContent = label;
      }, 3000);
      return;
    }
    button.disabled = true;
    void act().finally(() => {
      button.disabled = false;
    });
  });
  return button;
}

export async function mountStatusCard(
  host: HTMLElement,
  url: string,
  vaultName: string,
  schema: SchemaResponse,
  actions: StatusActions,
  page?: ZoteroSaveItem,
): Promise<void> {
  host.replaceChildren();
  const card = el("div", { class: "status-card" });
  host.appendChild(card);

  let found: LookupResponse = { matches: [] };
  let highlightCount = 0;
  try {
    const connection = await loadConnection();
    found = await lookup(connection, { url });
    highlightCount = (await annotationsFor(connection, { url })).annotations.length;
  } catch {
    card.appendChild(el("p", { class: "hint" }, "Couldn't check this page against your vault."));
    return;
  }

  const hasAnything = found.matches.length > 0 || found.note !== undefined || highlightCount > 0;

  // ---- Presence -----------------------------------------------------------

  if (found.matches.length === 0) {
    card.appendChild(el("p", { class: "status-line" }, "This page isn't in any of your views."));
  }

  for (const match of found.matches) {
    const entry = el("div", { class: "status-match" });
    const line = el("div", { class: "status-line" });
    line.appendChild(el("strong", {}, `In ${match.viewName}`));
    if (match.title !== "") line.appendChild(el("span", { class: "status-title" }, ` — ${match.title}`));
    entry.appendChild(line);
    entry.appendChild(el("div", { class: "status-sub" }, `from ${match.filePath}`));

    const buttons = el("div", { class: "status-actions" });

    // Open in the view, landed on this row: the plugin's kvs-open handler parks the focus and the table
    // consumes it on render.
    const openView = el("a", {
      class: "mini",
      href: `obsidian://kvs-open?vault=${encodeURIComponent(vaultName)}&view=${encodeURIComponent(match.viewId)}&ref=${encodeURIComponent(match.rowRef ?? "")}`,
    }, "Open in view");
    buttons.appendChild(openView);

    const openFile = el("a", { class: "mini", href: obsidianLink(vaultName, match.filePath) }, "Open file");
    buttons.appendChild(openFile);

    if (match.rowRef !== undefined) {
      const edit = el("button", { class: "mini", type: "button" }, "Edit");
      edit.addEventListener("click", () => actions.onEdit(match.viewId, match.rowRef ?? ""));
      buttons.appendChild(edit);

      buttons.appendChild(
        confirmButton("Delete row", async () => {
          try {
            const connection = await loadConnection();
            const result = await rowDelete(connection, { viewId: match.viewId, rowRef: match.rowRef ?? "" });
            actions.setStatus(result.ok ? "Row deleted (undo is in Obsidian's edit menu)." : result.reason ?? "Couldn't delete that row.", result.ok ? "info" : "error");
          } catch (error) {
            actions.setStatus(error instanceof Error ? error.message : "Couldn't delete that row.", "error");
          }
          actions.refresh();
        }),
      );

      if (match.hasNote !== true && found.note === undefined) {
        const promote = el("button", { class: "mini", type: "button", "data-promote": match.viewId }, "Create its note");
        buttons.appendChild(promote);
      }
    }
    entry.appendChild(buttons);
    card.appendChild(entry);
  }

  // ---- The dedicated note -------------------------------------------------

  if (found.note !== undefined) {
    const notePath = found.note.path;
    const entry = el("div", { class: "status-match" });
    entry.appendChild(el("div", { class: "status-line" }, "Has a dedicated note"));
    entry.appendChild(el("div", { class: "status-sub" }, notePath));
    const buttons = el("div", { class: "status-actions" });
    buttons.appendChild(el("a", { class: "mini", href: obsidianLink(vaultName, notePath) }, "Open note"));
    buttons.appendChild(
      confirmButton("Delete note", async () => {
        try {
          const connection = await loadConnection();
          const result = await noteDelete(connection, { url });
          actions.setStatus(
            result.ok ? "Note moved to the vault's trash — recoverable from there." : result.reason ?? "Couldn't delete the note.",
            result.ok ? "info" : "error",
          );
        } catch (error) {
          actions.setStatus(error instanceof Error ? error.message : "Couldn't delete the note.", "error");
        }
        actions.refresh();
      }),
    );
    entry.appendChild(buttons);
    card.appendChild(entry);

    if (found.matches.length === 0) {
      // Note first, no row yet: the other half of the workflow, offered where the gap is visible.
      const makeRow = el("button", { class: "mini", type: "button" }, "Create its row");
      makeRow.addEventListener("click", () => actions.onAdd("row"));
      const wrap = el("div", { class: "status-actions" });
      wrap.appendChild(makeRow);
      card.appendChild(wrap);
    }
  }

  // ---- Highlights ---------------------------------------------------------

  if (highlightCount > 0) {
    const entry = el("div", { class: "status-match" });
    entry.appendChild(
      el("div", { class: "status-line" }, `${String(highlightCount)} highlight(s) on this page`),
    );
    const buttons = el("div", { class: "status-actions" });
    buttons.appendChild(
      confirmButton("Delete highlights", async () => {
        try {
          const connection = await loadConnection();
          const result = await annotationsClear(connection, { url });
          actions.setStatus(`Removed ${String(result.removed)} highlight(s). Their lines in the row remain.`, "info");
        } catch (error) {
          actions.setStatus(error instanceof Error ? error.message : "Couldn't remove the highlights.", "error");
        }
        actions.refresh();
      }),
    );
    entry.appendChild(buttons);
    card.appendChild(entry);
  }

  // ---- Adding -------------------------------------------------------------

  // Send to Zotero, when the integration is on: the same page metadata a capture would use, saved through
  // the connector protocol into the chosen collection — one button, like Zotero's own extension.
  const prefs = await loadPreferences();
  if (prefs.zotero && page !== undefined && page.url !== "") {
    const wrap = el("div", { class: "status-actions" });
    const send = el("button", { class: "mini", type: "button" }, "Send to Zotero");
    send.addEventListener("click", () => {
      send.disabled = true;
      void (async () => {
        const outcome = await zoteroSave(page, prefs.zoteroCollectionKey || undefined);
        if (!outcome.ok) actions.setStatus(outcome.reason ?? "Zotero refused the save.", "error");
        else if (prefs.zoteroCollectionKey !== "" && outcome.placed !== true) {
          actions.setStatus("Saved to Zotero — but it refused the collection move, so the item is wherever Zotero's own selection sits.", "info");
        } else actions.setStatus("Saved to Zotero.", "ok");
        send.disabled = false;
      })();
    });
    wrap.appendChild(send);
    card.appendChild(wrap);
  }

  const writable = schema.views.some((v) => v.capture.writable);
  if (writable && (found.matches.length === 0 || !hasAnything)) {
    const add = el("div", { class: "status-actions status-add" });
    // Two equal choices, equally weighted. "Row first" is one workflow, not a hierarchy the buttons
    // should editorialise.
    const asRow = el("button", { class: "primary", type: "button" }, "Add as row");
    asRow.addEventListener("click", () => actions.onAdd("row"));
    const asPage = el("button", { class: "primary", type: "button" }, "Add as page");
    asPage.addEventListener("click", () => actions.onAdd("note"));
    add.append(asRow, asPage);
    card.appendChild(add);
  } else if (writable && found.matches.length > 0) {
    // Already present, but adding elsewhere stays possible — several views legitimately hold one page.
    const add = el("div", { class: "status-actions" });
    const more = el("button", { class: "mini", type: "button" }, "Add to another view…");
    more.addEventListener("click", () => actions.onAdd("row"));
    add.appendChild(more);
    card.appendChild(add);
  }
}
