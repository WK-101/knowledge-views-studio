import type { PageSnapshot } from "../../../shared/extract";
import { anchorSummary, buildAnchor } from "../../../shared/anchor";
import type { SchemaView, TextAnchor, WireAnnotation } from "../../../shared/protocol";
import { BridgeError, annotate, annotateRemove, annotationsFor, loadConnection } from "./bridge-client";

/**
 * The page's highlights, managed from the companion.
 *
 * The in-page toolbar is where highlights are usually made; this panel is where they're *reviewed* — every
 * highlight on the page with its colour and note, and the way to remove one. It reads the structured store,
 * never the row's cell: the cell is the human copy people are free to edit, and nothing machine-side should
 * depend on parsing it back.
 *
 * A selection can also be saved from here, for the cases where the toolbar isn't available — a page where
 * the annotator is off, or a highlight being added from the popup deliberately.
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

const DOT: Record<string, string> = {
  yellow: "#e6c200",
  green: "#3fae5a",
  blue: "#4a8fe0",
  red: "#e05a6a",
};

export function mountAnnotations(page: PageSnapshot, elements: Elements): void {
  const { host, view, setStatus } = elements;
  host.replaceChildren();

  const list = node("div", { class: "annots" });
  host.appendChild(list);

  const drawExisting = async (): Promise<void> => {
    list.replaceChildren();
    try {
      const connection = await loadConnection();
      const result = await annotationsFor(connection, { url: page.url });
      if (result.annotations.length === 0) {
        list.appendChild(node("p", { class: "hint" }, "No highlights on this page yet."));
        return;
      }
      list.appendChild(
        node("p", { class: "hint" }, `${String(result.annotations.length)} highlight(s) on this page`),
      );
      for (const annotation of result.annotations) {
        list.appendChild(annotationRow(annotation, drawExisting));
      }
    } catch {
      list.appendChild(node("p", { class: "hint" }, "Couldn't read this page's highlights — is Obsidian running?"));
    }
  };

  const annotationRow = (annotation: WireAnnotation, redraw: () => Promise<void>): HTMLElement => {
    const wrap = node("div", { class: "annot" });
    const head = node("div", { class: "annot-head" });
    const dot = node("span", { class: "annot-dot" });
    dot.style.backgroundColor = DOT[annotation.color] ?? DOT["yellow"] ?? "";
    head.appendChild(dot);
    head.appendChild(node("span", { class: "annot-quote" }, anchorSummary(annotation.anchor, 140)));
    const remove = node("button", { class: "link annot-remove", type: "button" }, "Remove");
    remove.addEventListener("click", () => {
      void (async () => {
        try {
          const connection = await loadConnection();
          const target = view();
          await annotateRemove(connection, {
            url: page.url,
            id: annotation.id,
            ...(target !== null ? { viewId: target.id } : {}),
          });
          setStatus("Highlight removed.", "info");
          await redraw();
        } catch {
          setStatus("Couldn't remove that highlight.", "error");
        }
      })();
    });
    head.appendChild(remove);
    wrap.appendChild(head);
    if (annotation.note !== undefined) {
      wrap.appendChild(node("p", { class: "annot-note" }, annotation.note));
    }
    return wrap;
  };

  void drawExisting();

  host.appendChild(node("hr", {}));

  const selected = (page.selectionMarkdown ?? page.selection ?? "").trim();
  if (selected === "") {
    host.appendChild(
      node(
        "p",
        { class: "hint" },
        "Select text on the page and use the highlight toolbar — or select and reopen this to save from here.",
      ),
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
      // Anchored against the article text — the panel can't read the live page the way the in-page
      // toolbar can, so restoring this highlight depends on the article extraction being faithful.
      const context = page.article?.markdown ?? page.excerpt ?? selected;
      const anchor: TextAnchor = buildAnchor(context, selected);
      const annotation: WireAnnotation = {
        id: Array.from({ length: 10 }, () =>
          "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)],
        ).join(""),
        anchor,
        color: "yellow",
        createdAt: new Date().toISOString(),
        ...(noteInput.value.trim() !== "" ? { note: noteInput.value.trim() } : {}),
      };

      button.setAttribute("disabled", "");
      setStatus("Saving highlight…");
      try {
        const connection = await loadConnection();
        const result = await annotate(connection, {
          viewId: target.id,
          url: page.url,
          annotation,
          fields: [
            { key: "title", value: page.title ?? "" },
            { key: "url", value: page.url },
          ],
        });
        if (!result.ok) {
          setStatus(result.reason ?? "Couldn't save that highlight.", "error");
          button.removeAttribute("disabled");
          return;
        }
        setStatus(result.createdRow === true ? "Highlight saved — new row created for this page." : "Highlight saved.", "ok");
        noteInput.value = "";
        button.removeAttribute("disabled");
        await drawExisting();
      } catch (error) {
        setStatus(error instanceof BridgeError ? error.message : "Couldn't save that highlight.", "error");
        button.removeAttribute("disabled");
      }
    })();
  });
}
