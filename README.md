# Knowledge Views Studio

[![CI](https://github.com/WK-101/knowledge-views-studio/actions/workflows/ci.yml/badge.svg)](https://github.com/WK-101/knowledge-views-studio/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/WK-101/knowledge-views-studio?display_name=tag&sort=semver)](https://github.com/WK-101/knowledge-views-studio/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/WK-101/knowledge-views-studio/blob/main/LICENSE)
[![Tests](https://img.shields.io/badge/tests-606%20passing-brightgreen)](https://github.com/WK-101/knowledge-views-studio#development)

**Your notes are already a database. This makes them behave like one.**

Your data is already in your vault — in the tables you typed, the properties you set, the tasks you checked off, the spreadsheets you keep alongside. Knowledge Views Studio brings **all of it** into one live view — as a table, board, calendar, cards, gallery, or pivot — and when you edit a cell there, it writes straight back to the note (or the workbook) the row came from.

Then it lets you **search inside all of it**, including the full text of your PDFs — offline.

<!-- Screenshots. Put images in docs/images/ and reference them here, e.g.:

![A board of tasks gathered from many notes](docs/images/board.png)
-->

> [!IMPORTANT]
> **This plugin writes to your notes.** That is the whole point of it — and it is also a real responsibility. Unlike read-only tools, a bug here could change your files. Please read **Safety** (below) before you point it at a vault you care about, and start with a backup or a copy.

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

### Layouts — one dataset, seven ways of seeing it
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
- **Rollups** — follow `[[links]]` in a relation column and aggregate across the notes they point to
  (count, sum, average, min/max, list, unique).
- **Formulas** — derive a column from the others. The editor shows the result **live against a real row**,
  a searchable function reference, and **the working**: each sub-expression and what it evaluated to, so a
  blank result points at the empty field that caused it instead of leaving you to guess.
- **Column summaries** — a footer that sums, averages, counts or de-duplicates any column, over the rows
  your filter kept.
- **Number styles** — draw a number column as a progress bar or a ring instead of a bare figure.
- **Per-group row limits** — a board grouped into five-thousand-row columns draws the first few and offers
  the rest, rather than laying out five thousand cards you will never read.

### Search — the whole vault, offline
A dedicated search view that goes far beyond note titles:

- **Keyword** — BM25 ranking, `"exact phrases"`, `-exclusions`, `AND`/`OR`, `field:value`, `tag:name`, `/regex/`, and typo tolerance. Title, heading and tag matches rank above body matches.
- **Semantic** — finds notes by *meaning* even when the words differ. Runs entirely on your device: **no model download, no network, nothing leaves your vault.**
- **Hybrid** — both, blended.
- **Ask** — put a question in plain language and get the passages from your vault that answer it, with sources. Retrieval, not generation: every passage is real text from your notes, so there is nothing to hallucinate.

It searches your **notes, your dashboard rows, your annotations, and the full text inside your attachments** — PDF, Word, PowerPoint, EPUB, and Excel — **with no companion plugin**. Results jump to the exact page of a PDF, the exact heading of a note, or the exact row.

**Related notes** — a sidebar showing the notes closest *by meaning* to the one you are reading. Click to
open; hover to insert a link at your cursor.

**Relevance is yours to tune.** How much Hybrid mode weighs meaning against exact words, how much a title
match outranks a body match, and how much a recently-edited note is favoured (with an exponential decay,
not a cutoff) — all settings, with a Reset button. They were constants I invented; a guess you can
disagree with beats a guess you cannot see.

**Attachment indexing is off by default**, because reading every PDF in a large vault costs real time and
battery. Turn it on when you want it, from the search view itself.

**The index can live in your vault**, so whatever already syncs your notes — Obsidian Sync, iCloud,
Dropbox — carries it too, and search works on your phone without re-indexing there. Off by default: if
you do not sync your vault, it buys you nothing.

**A better semantic engine, if you want one.** The default learns from your own vault and downloads
nothing, ever — but it cannot know that "car" and "automobile" mean the same thing unless your notes
taught it. Opt in to the neural engine and a real sentence-transformer does the job properly. It fetches
a model once (~25 MB); your notes are still never sent anywhere. See **Network use** below.

### Research kit (optional, off by default)
- **Annotate PDFs inside Obsidian** — highlight, comment, and the highlights sync into the note as callouts you can edit, re-colour, and delete.
- **Pull annotations from everywhere else** — comments and highlights out of Word, PowerPoint and Excel files; annotations from Zotero's local API.
- **Academic columns** — citation key, authors, DOI, arXiv, PubMed, with one-click links and citation copying.
- **Metadata lookups** — fill a row from a DOI (Crossref / OpenAlex). *The only feature that touches the network, and it is opt-in.*
- **Literature-review starters** — a synthesis matrix, a paper library, a highlight-synthesis builder.

---

## Install

**Settings → Community plugins → Browse → search "Knowledge Views Studio" → Install.**

Or install manually: download `main.js`, `manifest.json` and `styles.css` from the
[latest release](https://github.com/WK-101/knowledge-views-studio/releases/latest), put them in
`YourVault/.obsidian/plugins/knowledge-views-studio/`, and enable the plugin.

Requires **Obsidian 1.10 or later** (the Bases integration needs it).

## Quick start

Run **“Getting started”** from the command palette. It walks you through it in a few steps and offers three ways in:

1. **From a note you already have** — open a note with a table; your headers become the columns.
2. **From a template** — reading list, task tracker, project log, image gallery, literature review. Each creates an example note and a matching view you can edit or throw away.
3. **From scratch** — an empty view pointed at a folder.

---


## Network use

**By default, this plugin makes no network requests at all.** Everything — indexing, keyword search,
semantic search, Ask, annotation — runs on your device, and no note text ever leaves your machine.

Two features are exceptions, and both are **off by default** and must be switched on deliberately:

| Feature | What it contacts | Why | Default |
| --- | --- | --- | --- |
| **Neural semantic engine** | `huggingface.co` (model weights, ~25 MB) and `cdn.jsdelivr.net` (the transformers.js runtime), **once**, then cached | To run a real sentence-transformer locally, which understands meaning far better than the built-in engine | **Off** |
| **Research metadata lookups** | `api.crossref.org` and `api.openalex.org` | To fill in a paper's metadata from a DOI | **Off** |

**Your notes are never sent anywhere, by either feature.** The neural model downloads *to* you and then
runs on your machine, inside a sandboxed iframe that can do nothing but turn text into numbers. The DOI
lookup sends only the DOI you ask about.

If you want a plugin that touches the network exactly zero times, leave both switched off — that is the
out-of-the-box state, and the built-in semantic engine is designed for precisely that: it learns from
your own vault and downloads nothing, ever.

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
- **Mobile works, but is newly supported.** Every touch interaction has a real pointer-based path —
  board cards, column resizing, sort reordering, PDF highlighting — and the heavy jobs (full-text
  attachment indexing, the neural engine) are held back on phones by default, because settings sync and a
  laptop's choices should not conscript a battery. It has been built carefully for touch, but it has not
  yet been through a long shakedown on many real devices, so treat rough edges as expected and report
  them.
- **Search relevance is now measured, on a small corpus.** There is a human-judged query set (a coffee
  knowledge base with per-query relevance decided by meaning, not by the ranker) and a metrics harness
  (precision, recall, MRR, nDCG) that runs on every build as a regression gate. On that corpus the engine
  scores MRR 0.95 and nDCG@10 0.91 — a relevant result is almost always at or near the top. The honest
  limit: it is twelve documents and ten queries, enough to catch a ranking regression and enough to have
  already caught a real parser bug, but not a substitute for evaluation at vault scale. The weights are
  now *tested to be non-harmful* rather than merely asserted; no alternative beat them meaningfully on
  this corpus.

## Status

**New, and looking for people to break it.** The logic is covered by **735 unit tests**, the code is
TypeScript in strict mode, and every release must pass typecheck, tests, build, and lint — including
Obsidian's own reviewer rules (`eslint-plugin-obsidianmd`), which run in CI on every push.

But it has been used in very few real vaults. Real vaults are messier than tests: I found two genuine
correctness bugs — tables inside code fences being scraped as data, and escaped pipes leaking into cell
values — by spending twenty minutes attacking my own parser with adversarial Markdown. There are more.
I would rather hear about them from you than have you discover them quietly.

**If you can make it write something wrong, that is the most valuable bug report there is.**

Issues and pull requests very welcome. See the [changelog](https://github.com/WK-101/knowledge-views-studio/blob/main/CHANGELOG.md) for the development history, mistakes included.

## Development

```bash
npm install
npm run dev        # watch build
npm test           # 606 tests
npm run build      # typecheck + production build
npm run lint
```

## License

MIT — see [LICENSE](https://github.com/WK-101/knowledge-views-studio/blob/main/LICENSE).
