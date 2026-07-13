# Knowledge Views Studio

[![CI](https://github.com/OWNER/knowledge-views-studio/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/knowledge-views-studio/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/OWNER/knowledge-views-studio?display_name=tag&sort=semver)](https://github.com/OWNER/knowledge-views-studio/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-535%20passing-brightgreen)](#development)

**Your notes are already a database. This makes them behave like one.**

Your data is already in your vault — in the tables you typed, the properties you set, the tasks you checked off, the spreadsheets you keep alongside. Knowledge Views Studio brings **all of it** into one live view — as a table, board, calendar, cards, gallery, or pivot — and when you edit a cell there, it writes straight back to the note (or the workbook) the row came from.

Then it lets you **search inside all of it**, including the full text of your PDFs — offline.

<!-- ────────────────────────────────────────────────────────────────
     SCREENSHOTS GO HERE. This is the highest-leverage thing you can add.
     Put images in docs/images/ and reference them like this:

![One board of tasks pulled from many notes](docs/images/board.png)

     Suggested four, in this order:
       1. board.png    — a board of tasks gathered from several notes
       2. writeback.gif — drag a card to Done, and the source note's table changes (THE money shot)
       3. gallery.png  — the gallery layout full of images
       4. search.png   — Ask mode answering a question with passages from a PDF
     ──────────────────────────────────────────────────────────────── -->

> [!IMPORTANT]
> **This plugin writes to your notes.** That is the whole point of it — and it is also a real responsibility. Unlike read-only tools, a bug here could change your files. Please read [Safety](#safety) before you point it at a vault you care about, and start with a backup or a copy.

---

## The idea

Your vault is full of structured data you never called a database. Some of it is in frontmatter. Some is in tasks. Some is in `key:: value` fields. And a lot of it — often most of it — is in **the tables you typed inside your notes**, which almost nothing can read.

Knowledge Views treats **all five as the same thing**: rows.

| Source | Read by other tools? |
| --- | --- |
| **Table rows** — every row of every Markdown table | **No — this is the gap** |
| **Note properties** — frontmatter | Yes |
| **Tasks** — checkbox items | Partly |
| **Inline fields** — `key:: value` | Yes |
| **Excel rows** — `.xlsx` worksheets | No |

Mix them freely, in one view. And unlike the read-only tools, **you can edit what you see** — the change goes back into the source.

Say you have three project notes, each with a table:

`Projects/Apollo.md`
```markdown
| Task            | Status   | Due        | Owner |
| --------------- | -------- | ---------- | ----- |
| Draft spec      | Done     | 2025-03-01 | Ana   |
| Wire up the API | Doing    | 2025-03-14 | Ravi  |
```

`Projects/Borealis.md`
```markdown
| Task           | Status | Due        | Owner |
| -------------- | ------ | ---------- | ----- |
| Vendor review  | Todo   | 2025-03-09 | Ana   |
```

Point a view at `Projects/` and you get **one board of every task across every note** — group it by `Status`, drag a card to `Done`, and the word `Done` is written into that note's table. Switch the same view to a calendar and drag a card to reschedule it; the `Due` cell updates in the source note.

No query language. No duplicated data. The Markdown stays the source of truth, readable and editable by hand, portable to any other tool.

---

## Features

### Layouts — one dataset, six ways of seeing it
Switching layout never changes your data, your filter, or your source notes.

| Layout | What it's for |
| --- | --- |
| **Table** | The spreadsheet view — sort, resize, freeze, edit in place, virtualised for large sets |
| **Cards** | One card per row, for browsing |
| **Board** | Kanban columns by any field; drag a card to change it |
| **Calendar** | Rows on their date; drag to reschedule |
| **Gallery** | Every image across your rows as its own card, with live size / aspect / fit controls |
| **Pivot** | Cross-tabulate and total |

You can attach several layouts to a single view and switch between them from the toolbar.

### Sources — you decide what counts as a row
- **Table rows** — every row of every Markdown table in a note *(the one nothing else does)*
- **Note properties** — frontmatter, one row per note
- **Tasks** — every checkbox item
- **Inline fields** — `key:: value` pairs
- **Excel rows** — worksheet rows from `.xlsx` files, fully editable (opt-in)

Combine them freely. When several sources meet in one note, you say how they behave: keep each source's rows **separate**, or fold a note's properties **into each of its table rows**. Where the same header exists in two sources, you can bind a column to the one you mean — and "Discover from vault" fills that in for you.

### Editing that writes back
- Edit cells in place; changes land in the source note (or the Excel workbook).
- Add, duplicate, and delete rows.
- Multi-select, bulk edit, and copy rows out as Markdown *or* as a real table for Word / Google Docs / Excel.
- Undo, and daily backups before the first write to any workbook.

### Columns, filters, and computation
- Typed columns: text, number, date, select, rating, checkbox, tags, links, images, Markdown, and more.
- A visual filter builder, plus an expression escape hatch.
- Sort, multi-level grouping, and pagination.
- **Computed columns** and **rollups** — follow `[[links]]` in a relation column and aggregate across the notes they point to (count, sum, average, min/max, list, unique).

### Search — the whole vault, offline
A dedicated search view that goes far beyond note titles:

- **Keyword** — BM25 ranking, `"exact phrases"`, `-exclusions`, `AND`/`OR`, `field:value`, `tag:name`, `/regex/`, and typo tolerance. Title, heading and tag matches rank above body matches.
- **Semantic** — finds notes by *meaning* even when the words differ. Runs entirely on your device: **no model download, no network, nothing leaves your vault.**
- **Hybrid** — both, blended.
- **Ask** — put a question in plain language and get the passages from your vault that answer it, with sources. Retrieval, not generation: every passage is real text from your notes, so there is nothing to hallucinate.

It searches your **notes, your dashboard rows, your annotations, and the full text inside your attachments** — PDF, Word, PowerPoint, EPUB, and Excel — **with no companion plugin**. Results jump to the exact page of a PDF, the exact heading of a note, or the exact row.

Attachment indexing is **off by default**, because reading every PDF in a large vault costs real time and battery. Turn it on when you want it, from the search view itself.

### Research kit (optional, off by default)
- **Annotate PDFs inside Obsidian** — highlight, comment, and the highlights sync into the note as callouts you can edit, re-colour, and delete.
- **Pull annotations from everywhere else** — comments and highlights out of Word, PowerPoint and Excel files; annotations from Zotero's local API.
- **Academic columns** — citation key, authors, DOI, arXiv, PubMed, with one-click links and citation copying.
- **Metadata lookups** — fill a row from a DOI (Crossref / OpenAlex). *The only feature that touches the network, and it is opt-in.*
- **Literature-review starters** — a synthesis matrix, a paper library, a highlight-synthesis builder.

---

## Install

**Not yet in the community plugin directory** — a submission is pending. Until then:

1. Download `main.js`, `manifest.json` and `styles.css` from the [latest release](../../releases/latest).
2. Put them in `YourVault/.obsidian/plugins/knowledge-views-studio/`.
3. Reload Obsidian and enable the plugin in **Settings → Community plugins**.

Or install with [BRAT](https://github.com/TfTHacker/obsidian42-brat) by pointing it at this repository.

## Quick start

Run **“Getting started”** from the command palette. It walks you through it in a few steps and offers three ways in:

1. **From a note you already have** — open a note with a table; your headers become the columns.
2. **From a template** — reading list, task tracker, project log, image gallery, literature review. Each creates an example note and a matching view you can edit or throw away.
3. **From scratch** — an empty view pointed at a folder.

---

## Safety

This plugin **modifies your notes**, which most Obsidian data tools deliberately do not. That deserves plain speaking:

- **Back up first.** Try it on a copy of your vault, or make sure your vault is in version control / Obsidian Sync before you start editing through it.
- **Rows are located by content fingerprint**, not just line number, so a row can still be found after a file shifts. If a row cannot be found with confidence, the write is **refused** rather than guessed.
- **Cells that shouldn't be touched aren't.** Excel formula cells, and columns bound to a different source, are read-only.
- **Excel workbooks are backed up** before the first change each day.
- **Code blocks are not data.** A Markdown table inside a ``` fence is documentation — it is never scraped into a view, and never written to.
- **Undo** is available for changes made through the plugin.

If you find a way to make it write something wrong, that is the most valuable bug report you can file. Please open an issue.

## What this is not

Being honest about the edges:

- **Not a query language.** [Dataview](https://github.com/blacksmithgu/obsidian-dataview) gives you DQL and JavaScript; that is enormous expressive power this does not try to match. If you want to *program* your vault, use Dataview — it is excellent, and it is read-only, which for many people is exactly right.
- **Not a Bases replacement.** Obsidian's own Bases works on note properties and ships with the app.
- **No OCR.** Text inside scanned images isn't searchable (yet).
- **No AI generation.** "Ask" retrieves real passages; it does not write prose. That is a deliberate choice, not a missing feature — offline and honest beats fluent and wrong.
- **Mobile is untested.** It uses no desktop-only APIs and should run, but no one has put it through its paces on a phone. PDF annotation in particular is built for desktop.

## Status

**New, and looking for people to break it.** The logic is covered by **535 unit tests**, the code is TypeScript in strict mode, and every release passes typecheck, tests, build, and lint. But it has been used in exactly one real vault — mine. Real vaults are messier than tests, and I would rather hear about the rough edges from you than have you discover them quietly.

Issues and pull requests very welcome. If it does something surprising, please say so.

## Development

```bash
npm install
npm run dev        # watch build
npm test           # 535 tests
npm run build      # typecheck + production build
npm run lint
```

## License

MIT — see [LICENSE](LICENSE).

## Phase 106 — Obsidian community-directory review: every issue fixed

The plugin was pulled from the directory listing pending fixes. All of them are resolved, and Obsidian's
own linter (`eslint-plugin-obsidianmd`) is now **part of the four gates**, so this class of problem
cannot recur.

### Errors (these were the blockers)
- **34 × `no-unsupported-api`** — one root cause: the **Bases API requires Obsidian 1.10.0**, but the
  manifest declared `minAppVersion: 1.5.0`. The plugin genuinely needs those APIs, so the honest fix was
  to raise the floor. `minAppVersion` is now **1.10.0**.
- **11 × `no-static-styles-assignment`** — inline `element.style.x = y`. Static values moved to CSS
  classes; genuinely dynamic ones (auto-growing textareas) use `setCssStyles`, and the dynamic highlight
  colour uses `setCssProps` with a CSS variable.

### Manifest
- Description trimmed **255 → 244** characters (limit 250).
- `authorUrl` corrected to a live URL (it pointed at a placeholder that did not exist).

### Warnings
- `document.write()` → `srcdoc` on the print iframe.
- `Vault.delete()` → `FileManager.trashFile()`, respecting the user's deletion preference.
- `globalThis` removed; `console` logging on startup removed.
- `builtin-modules` dependency dropped for Node's own `node:module`.
- `window.setTimeout` / `clearTimeout` / `requestAnimationFrame` throughout, for popout-window safety.
  Two modules run under Node in the test suite, which has no `window` — so the tests now shim it, rather
  than the source telling a lie about where it runs.
- `String(unknown)` in the backup, restore, export and OpenAlex parsers could have written
  `"[object Object]"` into a user's data. Replaced with a safe coercion (`asString`) that returns the
  fallback instead. **This was a real latent bug, not just lint noise.**
- Unnecessary type assertions removed; untyped frontmatter narrowed to `unknown`.

### CSS
- Duplicate `background` / `color` declarations removed — they were hand-rolled `color-mix` fallbacks,
  redundant now that the floor is Obsidian 1.10 (a modern Chromium).
- `:has()` replaced with a class the annotation decorator sets itself — cheaper, and Obsidian discourages
  the selector.

### One deliberate non-fix
`setWarning()` is deprecated in favour of `setDestructive()` — but **`setDestructive` requires Obsidian
1.13**, and we support 1.10. Obsidian lists this only as a *recommendation*, so breaking compatibility
for it would be the wrong trade. The linter caught this the moment it was attempted; the deprecated call
stays, with the reason recorded in the source.

**535 tests. Typecheck, tests, build, and lint (now including Obsidian's reviewer rules) all clean.**
