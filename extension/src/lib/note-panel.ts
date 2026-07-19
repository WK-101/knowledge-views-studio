import type { PageSnapshot } from "../../../shared/extract";
import type { CaptureRequest, SchemaView } from "../../../shared/protocol";
import { renderTemplate, safeName } from "../../../shared/template";
import { noteVariables, DEFAULT_NOTE_TEMPLATE, DEFAULT_FILENAME_TEMPLATE } from "../../../shared/note";
import { BridgeError, capture, loadConnection } from "./bridge-client";
import { queueCapture } from "./queue-store";
import { loadPreferences } from "./preferences";

/**
 * Capturing a page as a note.
 *
 * The shape that was missing. A view could already be configured to capture notes, and the bridge would
 * write one — but the extension only ever sent column values, so what landed was frontmatter with an empty
 * body. A note capture that keeps none of the article is worse than none at all, because it looks like it
 * worked.
 *
 * What changes it is that the content script now returns the article as Markdown. This panel decides what to
 * do with it: which template, what filename, and whether the body is the whole article, just the selection,
 * or nothing at all.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly view: () => SchemaView | null;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

type BodyChoice = "article" | "selection" | "none";

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

export function mountNote(page: PageSnapshot, elements: Elements): void {
  const { host, view, setStatus } = elements;
  host.replaceChildren();

  const hasArticle = (page.article?.markdown ?? "") !== "";
  const hasSelection = (page.selectionMarkdown ?? page.selection ?? "") !== "";

  // Default to the selection when there is one: choosing text is a more deliberate act than landing on a
  // page, so it's the better guess at what someone means to keep. Someone who has said they don't want
  // article bodies gets properties only, since that preference is the more considered statement.
  let bodyChoice: BodyChoice = hasSelection ? "selection" : hasArticle ? "article" : "none";
  let quoteSelection = true;

  const bodyField = node("label", { class: "field" });
  bodyField.appendChild(node("span", {}, "Note body"));
  const bodyPicker = node("select", {});
  if (hasArticle) {
    const words = page.article?.wordCount ?? 0;
    bodyPicker.appendChild(node("option", { value: "article" }, `Whole article (~${String(words)} words)`));
  }
  if (hasSelection) bodyPicker.appendChild(node("option", { value: "selection" }, "Just my selection"));
  bodyPicker.appendChild(node("option", { value: "none" }, "No body — properties only"));
  bodyPicker.value = bodyChoice;
  bodyField.appendChild(bodyPicker);
  host.appendChild(bodyField);

  if (!hasArticle) {
    host.appendChild(
      node("p", { class: "hint" }, "No article was found on this page — it may not be article-shaped."),
    );
  }

  const nameField = node("label", { class: "field" });
  nameField.appendChild(node("span", {}, "File name"));
  const nameInput = node("input", { type: "text" });
  nameField.appendChild(nameInput);
  host.appendChild(nameField);

  const previewToggle = node("details", { class: "advanced" });
  previewToggle.appendChild(node("summary", {}, "Preview"));
  const preview = node("pre", { class: "preview-note" });
  previewToggle.appendChild(preview);
  host.appendChild(previewToggle);

  const button = node("button", { class: "primary", type: "button" }, "Save note to vault");
  host.appendChild(button);

  const bodyFor = (choice: BodyChoice): string => {
    if (choice === "article") return page.article?.markdown ?? "";
    if (choice === "selection") {
      const text = page.selectionMarkdown ?? page.selection ?? "";
      if (text === "" || !quoteSelection) return text;
      // Marking a selection as a quotation keeps someone else's words visibly theirs once it's in a note.
      return text
        .split(/\r?\n/)
        .map((line) => (line.trim() === "" ? ">" : `> ${line}`))
        .join("\n");
    }
    return "";
  };

  // Preferences arrive after the first draw; re-drawing is cheaper than blocking the panel on storage.
  void loadPreferences().then((prefs) => {
    quoteSelection = prefs.selectionStyle === "quote";
    if (!prefs.includeContent && bodyChoice === "article") {
      bodyChoice = hasSelection ? "selection" : "none";
      bodyPicker.value = bodyChoice;
    }
    refresh();
  });

  const refresh = (): void => {
    bodyChoice = bodyPicker.value as BodyChoice;
    const values = noteVariables(page, bodyFor(bodyChoice));
    // Rendered with the same code the plugin will use, so the preview can't drift from the result.
    const rendered = renderTemplate(DEFAULT_NOTE_TEMPLATE, values);
    preview.textContent = rendered.length > 1200 ? `${rendered.slice(0, 1200)}\n…` : rendered;
    if (nameInput.value.trim() === "") {
      nameInput.value = safeName(renderTemplate(DEFAULT_FILENAME_TEMPLATE, values));
    }
  };
  bodyPicker.addEventListener("change", refresh);
  refresh();

  button.addEventListener("click", () => {
    void (async () => {
      const target = view();
      if (target === null) {
        setStatus("Pick a view to capture into first.", "error");
        return;
      }
      const values = noteVariables(page, bodyFor(bodyChoice));
      const name = safeName(nameInput.value.trim() === "" ? values["title"] ?? "" : nameInput.value);

      // The plugin renders the template and writes the file; the fields carry everything it needs to.
      const request: CaptureRequest = {
        viewId: target.id,
        fields: Object.entries(values)
          .filter(([, value]) => value !== "")
          .map(([key, value]) => ({ key, value })),
        url: page.url,
        note: { fileName: name, body: bodyFor(bodyChoice) },
      };

      button.setAttribute("disabled", "");
      setStatus("Saving note…");
      try {
        const connection = await loadConnection();
        const result = await capture(connection, request);
        if (!result.ok) {
          setStatus(result.reason ?? "Couldn't save that note.", "error");
          button.removeAttribute("disabled");
          return;
        }
        setStatus(`Saved to ${result.path ?? "your vault"}`, "ok");
      } catch (error) {
        if (error instanceof BridgeError && error.offline) {
          await queueCapture(request);
          setStatus("Your vault isn't reachable — saved to send when it is.", "info");
          return;
        }
        setStatus(error instanceof BridgeError ? error.message : "Couldn't save that note.", "error");
        button.removeAttribute("disabled");
      }
    })();
  });
}
