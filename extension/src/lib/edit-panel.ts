import type { LookupMatch, SchemaView, UpdateResponse } from "../../../shared/protocol";
import { BridgeError, loadConnection, obsidianLink, update } from "./bridge-client";

/**
 * Changing something you already have.
 *
 * Until now the companion could only add. That's what made it feel like a filing tool: everything went one
 * way, and the moment you wanted to mark a paper read, rate it, or move its status, you had to leave the
 * page and go find the row by hand.
 *
 * Editing is the capability the plugin was built around — views here are editable dashboards that write back
 * to the files beneath them — and it's the one thing a general web clipper structurally cannot offer, since
 * it has no idea what a row is.
 *
 * Nothing is guessed at. The form is built from the same schema the capture form uses, so the fields are the
 * view's own; the vault decides what may be written; and anything it refuses is reported rather than
 * quietly dropped.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly view: () => SchemaView | null;
  readonly vaultName: () => string;
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

/**
 * Which columns are worth offering.
 *
 * A page you've already saved is one you're revisiting, and what changes on a revisit is your relationship
 * to it — read, rated, tagged, moved along — not its title or its author. Showing the whole row would bury
 * those three fields among a dozen that never change.
 */
export function editableColumns(view: SchemaView): SchemaView["columns"] {
  const interesting = new Set(["status", "priority", "rating", "tags"]);
  const chosen = view.columns.filter(
    (column) =>
      (column.role !== undefined && interesting.has(column.role)) ||
      interesting.has(column.name.toLowerCase()) ||
      (column.options !== undefined && column.options.length > 0) ||
      column.typeId === "checkbox" ||
      column.typeId === "rating",
  );
  // Nothing obviously about your relationship to the page: offer everything short, rather than nothing.
  return chosen.length > 0 ? chosen : view.columns.filter((c) => c.typeId !== "markdown").slice(0, 6);
}

export function mountEdit(match: LookupMatch, elements: Elements): void {
  const { host, view, vaultName, setStatus } = elements;
  host.replaceChildren();

  const target = view();
  if (target === null || match.rowRef === undefined) {
    host.appendChild(node("p", { class: "hint" }, "This row can't be edited from here."));
    return;
  }

  const header = node("div", { class: "edit-head" });
  header.appendChild(node("span", { class: "badge" }, match.viewName));
  const vault = vaultName();
  if (vault !== "" && match.filePath !== "") {
    const link = node(
      "a",
      { href: obsidianLink(vault, match.filePath), target: "_blank", rel: "noreferrer" },
      match.title === "" ? match.filePath : match.title,
    );
    header.appendChild(link);
  } else {
    header.appendChild(node("span", {}, match.title));
  }
  host.appendChild(header);
  host.appendChild(node("p", { class: "hint" }, `Already saved — matched on ${match.on}.`));

  const columns = editableColumns(target);
  for (const column of columns) {
    const field = node("label", { class: "field" });
    field.appendChild(node("span", {}, column.name));

    if (column.options !== undefined && column.options.length > 0) {
      const select = node("select", { "data-column": column.name });
      select.appendChild(node("option", { value: "" }, "— leave unchanged —"));
      for (const option of column.options) select.appendChild(node("option", { value: option }, option));
      field.appendChild(select);
    } else if (column.typeId === "checkbox") {
      const select = node("select", { "data-column": column.name });
      select.appendChild(node("option", { value: "" }, "— leave unchanged —"));
      select.appendChild(node("option", { value: "true" }, "Yes"));
      select.appendChild(node("option", { value: "false" }, "No"));
      field.appendChild(select);
    } else {
      const input = node("input", {
        "data-column": column.name,
        type: column.typeId === "number" || column.typeId === "rating" ? "number" : column.typeId === "date" ? "date" : "text",
        placeholder: "leave blank to keep",
      });
      field.appendChild(input);
    }
    host.appendChild(field);
  }

  const button = node("button", { class: "primary", type: "button" }, "Update this row");
  host.appendChild(button);

  button.addEventListener("click", () => {
    void (async () => {
      // Only what was actually filled in. A blank field means "leave it alone", not "clear it" — clearing
      // by omission would be a very expensive default to get wrong.
      const values = Array.from(host.querySelectorAll("[data-column]"))
        .map((el) => ({
          key: el.getAttribute("data-column") ?? "",
          value: (el as HTMLInputElement | HTMLSelectElement).value.trim(),
        }))
        .filter((v) => v.key !== "" && v.value !== "");

      if (values.length === 0) {
        setStatus("Change something first — blank fields are left as they are.", "error");
        return;
      }

      button.setAttribute("disabled", "");
      setStatus("Updating…");
      try {
        const connection = await loadConnection();
        const result: UpdateResponse = await update(connection, {
          viewId: match.viewId,
          rowRef: match.rowRef ?? "",
          values,
        });
        if (!result.ok) {
          setStatus(result.reason ?? "Couldn't update that row.", "error");
          button.removeAttribute("disabled");
          return;
        }
        const parts = [`Updated ${(result.updated ?? []).join(", ")}`];
        if (result.skipped !== undefined && result.skipped.length > 0) {
          parts.push(`· couldn't change ${result.skipped.map((s) => s.column).join(", ")}`);
        }
        setStatus(parts.join(" "), "ok");
      } catch (error) {
        // Deliberately not queued: an edit held for hours would be applied against a row that has since
        // moved on, and silently overwriting a later change is worse than asking again.
        setStatus(
          error instanceof BridgeError && error.offline
            ? "Your vault isn't reachable, so this wasn't changed."
            : error instanceof BridgeError
              ? error.message
              : "Couldn't update that row.",
          "error",
        );
        button.removeAttribute("disabled");
      }
    })();
  });
}
