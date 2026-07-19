import type { PageSnapshot, RawMeta, RawTable } from "../../../shared/extract";

/**
 * Reading the live page.
 *
 * This function is injected into the tab and runs there, so it must be **entirely self-contained**: the
 * browser serializes it across the boundary, and anything it referenced from module scope would simply be
 * undefined on the other side. That's why the helpers are nested rather than imported, and why this is the
 * only file in the extension written under that constraint.
 *
 * Reading the live document — rather than handing the URL to the plugin to fetch — is the reason a browser
 * extension is worth building at all. A fetch gets the markup a server sends to a stranger. This gets what
 * the reader is actually looking at: content rendered by script, sections they expanded, pages they're
 * logged into, and whatever they selected.
 */
export function readPageSnapshot(): PageSnapshot {
  const meta: RawMeta[] = [];
  for (const tag of Array.from(document.querySelectorAll("meta"))) {
    const key = tag.getAttribute("property") ?? tag.getAttribute("name") ?? tag.getAttribute("itemprop");
    const content = tag.getAttribute("content");
    if (key !== null && content !== null && content.trim() !== "") meta.push({ key, content });
  }

  const jsonLd: unknown[] = [];
  for (const script of Array.from(document.querySelectorAll('script[type="application/ld+json"]'))) {
    const text = script.textContent ?? "";
    if (text.trim() === "") continue;
    try {
      jsonLd.push(JSON.parse(text));
    } catch {
      // Malformed JSON-LD is common on real sites and not worth reporting: the other sources usually
      // still describe the page perfectly well.
    }
  }

  let excerpt = "";
  for (const selector of ["article p", "main p", "p"]) {
    for (const p of Array.from(document.querySelectorAll(selector))) {
      const text = (p.textContent ?? "").replace(/\s+/g, " ").trim();
      if (text.length >= 80) {
        excerpt = text.slice(0, 500);
        break;
      }
    }
    if (excerpt !== "") break;
  }

  // Tables, for capturing many rows at once. Read generously and filtered later in shared code, where the
  // rules about what counts as data rather than layout can be tested.
  const tables: RawTable[] = [];
  for (const table of Array.from(document.querySelectorAll("table")).slice(0, 12)) {
    const rowNodes = Array.from(table.querySelectorAll("tr"));
    if (rowNodes.length < 2) continue;

    const cellsOf = (tr: Element): string[] =>
      Array.from(tr.querySelectorAll("th, td")).map((c) => (c.textContent ?? "").replace(/\s+/g, " ").trim());

    // A header row is the one made of <th>, or failing that the first row.
    const headerRow = rowNodes.find((tr) => tr.querySelector("th") !== null) ?? rowNodes[0];
    if (headerRow === undefined) continue;
    const headers = cellsOf(headerRow);
    if (headers.length === 0) continue;

    const bodyRows = rowNodes.filter((tr) => tr !== headerRow).slice(0, 300).map(cellsOf);
    const caption = (table.querySelector("caption")?.textContent ?? "").replace(/\s+/g, " ").trim();
    tables.push({ headers, rows: bodyRows, ...(caption !== "" ? { caption } : {}) });
  }

  const selection = (window.getSelection()?.toString() ?? "").replace(/\s+/g, " ").trim();

  return {
    url: location.href,
    title: document.title.trim(),
    meta,
    jsonLd,
    ...(selection !== "" ? { selection } : {}),
    ...(excerpt !== "" ? { excerpt } : {}),
    ...(tables.length > 0 ? { tables } : {}),
  };
}
