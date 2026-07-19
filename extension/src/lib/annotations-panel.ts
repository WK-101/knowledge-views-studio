import type { PageSnapshot } from "../../../shared/extract";
import { anchorSummary, buildAnchor } from "../../../shared/anchor";
import type { CaptureRequest, SchemaView, TextAnchor } from "../../../shared/protocol";
import { BridgeError, capture, loadConnection, rows as fetchRows } from "./bridge-client";
import { queueCapture } from "./queue-store";

/**
 * Highlights, kept where the rest of your reading is.
 *
 * A highlight is only worth making if it can be found again, so what's saved is the quoted text plus a
 * little of what surrounds it — never a position, which points at something else the moment the page
 * rerenders. When a passage genuinely has gone, the annotation says so rather than silently pointing at a
 * neighbouring sentence.
 *
 * Existing highlights for the page are listed first. Seeing what you already thought about something is most
 * of the value of having written it down.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly view: () => SchemaView | null;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (text !== undefined) el.textContent = text;
  return el;
}

/** The column an annotation's text belongs in, preferring one the view actually named for it. */
export function highlightColumn(view: SchemaView): string {
  const named = view.columns.find((c) =>
    ["highlight", "quote", "annotation", "excerpt", "note"].includes(c.name.toLowerCase()),
  );
  if (named !== undefined) return named.name;
  const long = view.columns.find((c) => c.typeId === "markdown");
  return long?.name ?? view.columns[0]?.name ?? "Note";
}

export function mountAnnotations(page: PageSnapshot, elements: Elements): void {
  const { host, view, setStatus } = elements;
  host.replaceChildren();

  const selected = (page.selectionMarkdown ?? page.selection ?? "").trim();
  const existing = node("div", { class: "annots" });
  host.appendChild(existing);

  // What you already noted about this page, before offering to add more.
  void (async () => {
    const target = view();
    if (target === null) return;
    try {
      const connection = await loadConnection();
      const result = await fetchRows(connection, { viewId: target.id, url: page.url, pageSize: 20 });
      if (result.rows.length === 0) {
        existing.appendChild(node("p", { class: "hint" }, "No highlights saved from this page yet."));
        return;
      }
      const column = highlightColumn(target);
      existing.appendChild(node("p", { class: "hint" }, `${String(result.rows.length)} saved from this page`));
      for (const row of result.rows) {
        const text = row.cells[column] ?? Object.values(row.cells).find((v) => v !== "") ?? "";
        if (text === "") continue;
        existing.appendChild(node("blockquote", { class: "annot" }, anchorSummary({ exact: text }, 160)));
      }
    } catch {
      // Reading is optional here; adding a highlight still works without it.
    }
  })();

  host.appendChild(node("hr", {}));

  if (selected === "") {
    host.appendChild(
      node("p", { class: "hint" }, "Select some text on the page, then reopen this to save it as a highlight."),
    );
    return;
  }

  host.appendChild(node("blockquote", { class: "annot is-new" }, anchorSummary({ exact: selected }, 300)));

  const noteField = node("label", { class: "field" });
  noteField.appendChild(node("span", {}, "Your note (optional)"));
  const noteInput = node("textarea", { rows: "2" });
  noteField.appendChild(noteInput);
  host.appendChild(noteField);

  const button = node("button", { class: "primary", type: "button" }, "Save highlight");
  host.appendChild(button);

  button.addEventListener("click", () => {
    void (async () => {
      const target = view();
      if (target === null) {
        setStatus("Pick a view to save into first.", "error");
        return;
      }
      // Anchor against the article text so the highlight can be found again after the page changes.
      const context = page.article?.markdown ?? page.excerpt ?? selected;
      const anchor: TextAnchor = buildAnchor(context, selected);

      const request: CaptureRequest = {
        viewId: target.id,
        fields: [
          { key: highlightColumn(target), value: selected },
          { key: "url", value: page.url },
          { key: "title", value: page.title ?? "" },
          ...(noteInput.value.trim() !== "" ? [{ key: "note", value: noteInput.value.trim() }] : []),
          ...(anchor.prefix !== undefined ? [{ key: "anchorPrefix", value: anchor.prefix }] : []),
          ...(anchor.suffix !== undefined ? [{ key: "anchorSuffix", value: anchor.suffix }] : []),
        ],
        url: page.url,
      };

      button.setAttribute("disabled", "");
      setStatus("Saving highlight…");
      try {
        const connection = await loadConnection();
        const result = await capture(connection, request);
        if (!result.ok) {
          setStatus(result.reason ?? "Couldn't save that highlight.", "error");
          button.removeAttribute("disabled");
          return;
        }
        setStatus(`Highlight saved to ${result.path ?? "your vault"}`, "ok");
      } catch (error) {
        if (error instanceof BridgeError && error.offline) {
          await queueCapture(request);
          setStatus("Your vault isn't reachable — saved to send when it is.", "info");
          return;
        }
        setStatus(error instanceof BridgeError ? error.message : "Couldn't save that highlight.", "error");
        button.removeAttribute("disabled");
      }
    })();
  });
}
