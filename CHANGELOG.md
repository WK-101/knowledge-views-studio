# Changelog

Development notes for Knowledge Views Studio. Written for people reading the code — the *why* behind
each change, including the mistakes, because a changelog that only records what worked teaches nothing.

For what the plugin does, see the [README](README.md).

## Phase 127.1 — fix: "Fill details from Zotero" never appeared (a dropped callback)

A real bug, and an honest one to record. "Fill details from Zotero" (added in phase 126) was wired at both
ends — the dashboard set the `onFetchZotero` callback, and the table view's row menu read it — but it never
showed up. The cause was the layer *between* them: `renderProfile` takes a `RenderProfileOptions` bag and
forwards a whitelisted set of callbacks into the view context, and `onFetchZotero` was never added to that
whitelist. So the callback was set, passed to `renderProfile`, and silently dropped before the view ever
saw it. `onPromote` was forwarded, which is why "Promote to dedicated note" (and its new Zotero-awareness)
worked — the two were wired the same way at the ends but only one survived the middle.

The lesson: verifying the endpoints ("dashboard sets it", "view reads it") wasn't enough — the passthrough
layer was the gap, and only tracing the whole chain caught it. The fix declares and forwards
`onFetchZotero` like the others, and a new guard test parses `render-profile.ts` and asserts that *every*
row-action callback (fill-from-DOI, fill-from-Zotero, promote, cite, DOI-values) is both declared and
forwarded — so a callback can't be added at the ends while being dropped in the middle again.

Where these options live, for reference: right-click a row **in a table view that has the Academic Research
kit enabled**. "Fill details from Zotero" sits directly under "Fill details from DOI", so the row needs a
DOI column with a value. "Promote to dedicated note" is the same existing menu item — there is no separate
button for the Zotero-awareness; it simply builds the note from Zotero when the row's DOI is in your library
and Zotero is running, and falls back to the template otherwise.

753 tests (was 748).

## Phase 127 — keeping Zotero fresh: note refresh, collection scoping, and incremental search

Three smaller completions, each closing a "this goes stale / this is all-or-nothing" gap in the Zotero
integration.

### Refresh a literature note from Zotero

A literature note is created with the paper's annotations at that moment — but you keep annotating in Zotero
afterwards, and the note drifts. A new command, **"Refresh literature note from Zotero"**, re-pulls the
paper's annotations and rewrites just the managed Annotations region, leaving your own writing untouched. It
uses the note's own `zotero-key` frontmatter to know which paper it is, so it's only offered on an actual
literature note (a `checkCallback` hides it elsewhere) and needs no argument.

### Pick a collection to scope a dashboard

The engine already understood collection scoping (`scope.zoteroCollectionKey`), but nothing surfaced it.
Now **"Create Zotero dashboard from a collection…"** opens a fuzzy picker of your Zotero collections —
shown as an indented tree with item counts, built from Zotero's flat parent-referenced list — and builds a
dashboard scoped to the one you choose (or the whole library). So a dashboard can be "just my Thesis
collection" across all seven layouts, not the entire library every time.

### Refresh Zotero in search without a full rebuild

Zotero items only entered the search index on a *full* rebuild, which also re-reads every vault file —
expensive just to pick up a couple of new papers. **"Refresh Zotero in search"** re-runs only the external
(Zotero) document pass and persists, leaving the file index alone. Because that pass already clears the
prior Zotero batch before adding the current one, it's a clean swap: new items appear, removed items
disappear, and your notes aren't reindexed. Offered only when Zotero search is enabled.

None of these change default behaviour or add startup cost — they're on-demand commands. Each degrades with
a clear notice when Zotero isn't reachable.

Covered by tests (748 total, was 742): the collection tree-building (parents before children, sibling
sorting, indentation, the whole-library option, key mapping preserved) and the collection mapping from the
local API (parent and item count).

## Phase 126 — deeper Zotero: editable note templates, fill-from-Zotero, and Zotero-aware promotion

Three changes, all pushing the Zotero connection further into features that already existed.

### 1. Literature note templates are now editable

The literature note had a fixed shape (frontmatter + abstract + annotations + notes). Now there's a
template setting: write your own layout with `{{placeholders}}` (title, authors, year, journal, doi, url,
citeKey, itemType, key, tags, abstract, zoteroLink), or leave it empty for the built-in default. The
default and a custom template travel the *same* substitution path — the default is just the template we
ship.

The one invariant that can't be given up: a literature note **must** carry `zotero-key` in frontmatter, or
find-or-create can't match it and duplicate protection silently breaks. So if a custom template omits the
key, the builder injects it (into the existing frontmatter, or a minimal block if there's none). You get
layout freedom without being able to accidentally disable the idempotency the whole workflow rests on.

### 2. "Fill details from Zotero" — a richer sibling of fill-from-DOI

The dashboard's "Fill details from DOI" reads Crossref. Now, right beside it, "Fill details from Zotero"
looks the row's DOI up in your *local Zotero library* and fills from the paper you already have — which
means it can bring across things Crossref doesn't: your Better BibTeX **cite key** and the paper's **tags**,
as well as the usual author/title/year/venue. Same fill semantics (only empty fields are touched, undoable),
with clear notices when Zotero isn't running or the DOI isn't in the library.

### 3. "Promote to dedicated note" now builds from Zotero when it can

Promoting a paper used to always render the plain template. Now, if the row's DOI is in Zotero, promotion
builds the note from the live Zotero item instead — full metadata **and the paper's annotations** — using
the same literature-note builder the library view uses. So the two paths converge: promoting a paper that
exists in Zotero produces a proper literature note, not just a metadata fill. When there's no DOI match (or
Zotero isn't reachable), it falls back to the template exactly as before — the enhancement never removes the
original behaviour.

Together these make Zotero the connective tissue across the academic kit: whether you start from the Zotero
library, from a DOI in a dashboard, or from promoting a row, you land on the same rich, annotated,
linkable note.

Covered by expanded tests (742 total, was 735): custom-template substitution, and the guarantee that
`zotero-key` is injected when a template omits it (in an existing frontmatter block, or a fresh one) and not
duplicated when it's present.

## Phase 125 — literature notes: turn a Zotero paper into a note you can think with

The Zotero features so far let you *view and search* papers inside Obsidian — but that was never the
researcher's endpoint. The reason people bridge Zotero and Obsidian at all is that a paper needs to become a
*note you can think with*: linkable from concept notes, taggable into your own system, a node in the graph,
with room for your own synthesis. Zotero can't do that. Until now our library could show you a paper, but
clicking it opened the paper's *web page* — the opposite of what you want, which is to stay in Obsidian and
get a note.

This phase closes that gap. Clicking a paper in the Zotero library now creates — or opens, if it already
exists — a **literature note**: a real Obsidian note carrying the paper's metadata as frontmatter, its
abstract, its Zotero annotations, a durable link back to Zotero, and an empty Notes section for your own
writing.

### Why it actually reduces friction (and doesn't become a chore)

- **Idempotent by a durable key.** Every literature note records the paper's Zotero key in frontmatter
  (`zotero-key`), and creating a note first looks that key up across the whole vault. So a paper never gets
  two notes, renaming its title never forks it, and clicking a paper you've already noted just opens the
  existing note. Re-running refreshes the annotations region in place — via the same upsert the annotation
  sync uses — without touching your own writing below it.
- **A real note, not a data dump.** Metadata lands in frontmatter (authors, year, journal, doi, tags, the
  key), which makes the paper a proper Obsidian citizen — wikilinkable, taggable, and *queryable by KVS's
  own note-properties source*. So your literature notes can themselves become a dashboard, closing the loop:
  Zotero → notes → dashboard, all native.
- **Annotations arrive with the note.** On creation, the paper's Zotero annotations are pulled in and
  rendered into a managed Annotations region, reusing the existing annotation-collection machinery.
- **Reading progress at a glance.** Each row in the library shows a dot — filled when the paper already has
  a literature note, hollow when it doesn't — so "which papers have I written up?" is answerable without
  leaving the view. Select many and "Create notes" makes them in one go.

The title click is the primary action (create/open the note); a small external-link icon still jumps to the
paper in Zotero when you want the source.

Covered by tests (735 total, was 725): the note content (frontmatter fields, the Zotero link, YAML escaping,
graceful handling of sparse items), and — the load-bearing property — idempotent find-or-create, proven by a
test that creates a note, then "creates" it again with a changed title and a different folder and asserts the
existing note is reused with no duplicate.

## Phase 124 — the whole library, and a bridge from it to a dashboard

Two things: a correctness fix, and the feature that removes the friction between the Zotero library and a
KVS dashboard.

### The library view showed only part of a large library

It capped at the first page of results — a real library of thousands of items was silently truncated. The
root cause was two-fold: our own request asked for a fixed number, and Zotero's API caps any single request
at 100 items regardless. The fix is proper **pagination**: the provider now walks the library page by page
(requesting `start=0, 100, 200, …`) until a short page signals the end, so the entire library loads no
matter its size. The same applies to annotation fetching for search. A hard safety ceiling guards against a
misbehaving server, and an optional overall cap is still honoured where one is wanted (search indexing).
Proven by tests that simulate a 250-item library across three pages and assert all of it comes back.

### From library to dashboard, without friction

The library view was a good place to *browse* Zotero, but getting what you found into a KVS dashboard meant
starting over. Now the view has a selection model and an action bar:

- **Tick items** (with a select-all), and the action bar shows what your next action will act on — the
  ticked items, or, when nothing is ticked, everything currently shown (so you can filter/search the
  library and act on the result).
- **Open as dashboard** — builds a full KVS dashboard (all seven layouts, filters, formulas) from exactly
  those items. This is powered by a new, precise scoping option: a Zotero-scoped profile can now pin itself
  to a specific set of item keys (or to a Zotero collection), so "these twelve papers I selected" becomes a
  living dashboard, not a copy.
- **Copy as table** — copies the selection as a Markdown table. Pasted into a note, KVS's *own* table
  source reads it straight back — a tidy closed loop from Zotero to a native KVS table.
- **Copy citations** — copies cite keys (`[@key]`), falling back to a compact reference when an item has no
  key.

The scoping is the load-bearing idea: rather than duplicating Zotero data into the vault, a selection
becomes a *filter* on the live library, so the dashboard stays current and read-only-honest like every other
Zotero view. Tested end to end — a selection-scoped profile returns exactly the pinned items through the
real engine, and an empty selection correctly means "the whole library," not "nothing."

725 tests (was 720). Four gates green.

## Phase 123 — Zotero search results now show a name and a preview

The Zotero search integration worked, but its results displayed poorly: a hit's header showed only the bare
item type ("journalArticle") instead of the paper's name, and there was no content preview under it — unlike
a note or PDF hit, which shows its title and a snippet. Two concrete bugs, both fixed:

- **The header now names the paper.** A Zotero item hit reads "Attention Is All You Need · Journal article"
  (name first, then a readable type label), mirroring how a file hit reads "<file> · <section>". An
  annotation hit names the paper it belongs to when that can be resolved — "Deep Learning · p. 9" — instead
  of a bare page number. The item type is humanised too ("journalArticle" → "Journal article").
- **The preview snippet now appears.** The results list shows a content snippet for a Zotero hit — the
  item's creators/abstract, or the annotation's highlighted text — the same way it does for attachments.
  Previously the snippet was always empty: the search view only knew how to fetch preview text for vault
  files (via a path), and Zotero hits have no path, so it bailed out before retrieving anything. Now the
  view routes Zotero hits to the retained document text, and the indexer keeps that text (and persists it)
  the same way it does for PDFs and Office files.

Both were failures of *display*, not of search — the items were found and ranked correctly all along; they
just rendered without the context that makes a result useful. Result rendering now reads the same for Zotero
as for everything else.

Covered by expanded tests (720 total, was 717): the header/name format for items and annotations, the
humanised type label, parent-paper resolution for annotations, and the page-label fallback.

## Phase 122 — search that reaches into Zotero, too

With Zotero integrated as a data source, the search should reach it as well. This phase indexes your Zotero
library and its annotations into the *same* search index as your notes — so one query finds a paper in your
Zotero library, or a passage you highlighted in it, ranked alongside your vault, by the same relevance
model. Not a second search box; the same one, made wider.

### One index, two new document kinds

The design principle throughout has been: don't build a parallel search path. Zotero content becomes
ordinary `IndexDoc`s fed to the existing `SearchIndex`, so BM25 ranking, the semantic layer, field boosts,
scope filters, and the relevance harness all apply with no special-casing:

- `source: "zotero"` — one document per library item (title, creators, abstract, tags, publication), with
  title and tags in the *boosted* fields, so a title match outranks an abstract match exactly as it does
  for a note.
- `source: "zotero-annotation"` — one document per annotation: the quoted text plus your comment. This is
  the payoff of integrating annotations — the words you highlighted in a paper become findable from the
  same box you search your notes with.

Both are tagged so they appear as their own filter chips ("Zotero", "Zotero notes") and, when clicked, open
the item in Zotero via its `zotero://select` protocol rather than trying to open a nonexistent vault file.

### Decoupled, and safe to have off

The indexer gained a single, opaque hook — "an optional source of extra documents" — so it has no
Zotero-specific knowledge; it just folds in whatever the hook returns. That keeps the search engine general
and the Zotero specifics in one place. The whole feature is off by default and guarded end to end: disabled,
or Zotero not running, or the API unreachable → it contributes nothing and never fails a build. And turning
it off doesn't leave stale results — the next rebuild clears the Zotero documents, because the indexer clears
the prior external batch before adding the current one.

Verified two ways: unit tests for the document building (right text, boosted fields, defensive annotation
parsing), and an end-to-end test that adds Zotero documents to a real `SearchIndex` and confirms a query
finds an item by its title and an annotation by its words — with the title-boost ranking holding. The P6
relevance regression gate still passes unchanged, since vault ranking is untouched.

717 tests (was 709). Four gates green.

## Phase 121 — Zotero as a first-class source across every layout

The previous phase gave the Zotero library its own view. This phase makes it a *source* the whole engine
understands, so a Zotero library renders through all seven layouts (table, cards, board, calendar, gallery,
chart, pivot) with filters, computed columns, rollups, and search — exactly like a folder of notes or a
spreadsheet. Not a bespoke panel; a real data source.

### How it plugs in

The impedance mismatch was real: the view engine is built around *files* (a `DataService` discovers notes
and runs synchronous extractors), while Zotero is *async and not file-based*. The clean resolution was to
branch at the one point where raw rows are produced — `buildDataset` — rather than force Zotero through the
file-reading machinery:

- A new scope mode, `"zotero"`. A profile scoped to Zotero draws its rows from the live provider instead of
  the vault.
- `buildDataset` branches on that mode: Zotero-scoped → fetch from the provider and map to rows; otherwise
  the existing file path, untouched.
- **Everything downstream is unchanged.** Once the items are `Row`s, the transform pipeline (compute →
  filter → search → sort → paginate) and every layout treat them identically. That is the entire benefit of
  having a single `Row` abstraction: adding a wholly new *kind* of source touched one method, not the seven
  layouts, the filter engine, or the formula evaluator.

The provider is injected optionally, so the engine has no hard dependency on Zotero: a Zotero-scoped view on
a machine with no Zotero running yields an empty dataset (with a source warning), never an error.

### The write seam survives the whole pipeline

The read-only marking established last phase isn't lost when rows pass through the engine: a test drives a
real `DataService` with a fake provider and asserts that the rows coming *out* of the full pipeline still
carry `readOnlyFields` and their Zotero provenance. So when local write support arrives and the backend
reports it can write, edits made in any layout — a card, a board cell, a table row — will route through the
same seam. The bidirectional future is wired end to end, not just at the edges.

### What ships

A command, "Create Zotero library dashboard (all layouts)", builds a Zotero-scoped view with typed columns
and semantic roles (title, date, tags) so the non-table layouts have sensible defaults out of the box —
your library as a kanban board by item type, a calendar by date added, a gallery, a pivot. All read live
from Zotero's local API, all read-only until Zotero permits otherwise.

Proven by an end-to-end integration test (7 cases) that runs the real engine against a fake provider: rows
produced, filters applied, sort applied, kanban grouping, the read-only seam intact, and graceful
degradation when the provider is absent or throws.

709 tests (was 702). Four gates green.

## Phase 120 — a live Zotero library view, built for eventual two-way editing

The [zotero-lib-view](https://github.com/lebenswille/zotero-lib-view) plugin puts your Zotero library in a
table inside Obsidian — but it reads a *static Better BibTeX JSON export*, so the data is only as fresh as
your last manual export, and it is inherently view-only (a file export has nothing to write back to). This
phase does the same job, live: it reads Zotero's **local HTTP API** (the same current source ZotFlow
uses), so the library is always up to date with no export step.

### Why this is designed the way it is

The obvious question is "can it edit back into Zotero, so the two feel like one system?" The honest answer,
verified against Zotero's own documentation and developer statements: **not yet — Zotero's local API is
read-only.** Write support is planned upstream but unbuilt; every local endpoint is GET. (Even ZotFlow's
bidirectional sync goes through Zotero's *cloud* Web API, not locally.) So local write-back cannot exist
today through supported means, and reaching into Zotero's SQLite directly — the only alternative — corrupts
libraries and is not something worth shipping.

Rather than build a read-only feature that would need ripping apart later, this is built so that turning on
editing, when Zotero allows it, is a *swap and not a rewrite*:

- **A transport-agnostic provider** (`ZoteroProvider`) separates *what a library looks like* from *how it's
  fetched* and *how it's written*. Reads run live against the local API now.
- **The write path exists today, as a seam.** `ZoteroWriteBackend` is a real interface with a real
  implementation — `ReadOnlyZoteroBackend`, which reports `canWrite() === false` with an honest reason
  (Zotero's limitation, not ours). Every would-be edit already routes through it. The day a working backend
  exists — local writes, or an opt-in to the cloud Web API — it implements the same three methods and
  nothing else changes.
- **Rows already carry their write address.** Each Zotero item becomes a normal KVS row (so it renders in
  the table/cards/board/etc. like any other source), and its provenance stores the item key and *version*
  — exactly what Zotero's `If-Unmodified-Since-Version` / 412 conflict protocol needs. The write path has
  its address the moment it becomes usable.
- **The lock is the existing mechanism, parameterised.** Today every Zotero field is marked
  `readOnlyFields` — the same guard that stops someone overwriting an Excel formula cell. When the backend
  reports it can write, the editable metadata fields simply drop out of that list. There is no Zotero-
  specific write-block to unwind; the machinery is already general.

That seam is not decoration — it is tested. A test swaps in a hypothetical write-capable backend and
asserts the *same row mapper* produces editable rows (title, DOI, tags unlock; identity and timestamps stay
locked), proving the future path works before it is needed.

### What ships now

A live, searchable, sortable Zotero library view (command: "Open Zotero library"), read straight from the
local API. Full-record search (title, creators, tags, abstract, DOI), click-to-open, and the same
accessibility standard as the rest of the plugin — semantic `<table>`, `aria-sort`, keyboard sorting,
live-region status. It states plainly that it is read-only and why, rather than pretending. Together with
the ZotFlow reader bridge and annotation collection from the previous phase, the arc is: browse your live
Zotero library → open a paper in ZotFlow's Zotero-grade reader → pull the annotations you make into your
notes — three plugins composing into something that feels native, on supported seams only.

702 tests (was 686). Four gates green.

## Phase 119 — optional interoperation with ZotFlow

[ZotFlow](https://community.obsidian.md/plugins/zotflow) embeds Zotero's real PDF/EPUB reader — a far
richer reading and annotation experience than our pdf.js annotator, and one we have no intention of
reimplementing. This phase lets the two plugins *compose* instead of compete: if a user has ZotFlow
installed, KVS can hand a file to its reader and collect the annotations they make there; if they don't,
nothing changes and our own reader is used exactly as before. It is off by default and enabled with one
toggle in settings.

### What it does

- **"Open in ZotFlow reader"** — a right-click option on PDF and EPUB attachment cards, shown only when
  ZotFlow is detected. Our own reader stays the default (plain click); this is an addition, never a
  replacement.
- **Collects ZotFlow's annotations** — the "Sync annotations into this note" command now also reads
  ZotFlow's co-located `.zf.json` sidecars, so highlights made in *either* reader land in the note
  together, rendered as the same callouts. For EPUBs this is pure gain, since we have no EPUB reader of
  our own.

### The two rules it is built on

ZotFlow exposes **no public API**, and ships very frequently, so the integration is deliberately
conservative:

1. **Public seams only.** It touches exactly three things ZotFlow exposed to the world, never its
   internals: whether the plugin is enabled, its registered *view type* (opened through Obsidian's own
   `setViewState`, the same call ZotFlow's own code uses), and its documented on-disk `.zf.json` format.
   It never imports ZotFlow's modules, calls its worker, or depends on the shape of its internal classes —
   those are not ours to reach into, and would break on any refactor.
2. **Enhance, never break.** Every entry point degrades to "feature quietly unavailable" — never an
   error — when ZotFlow is absent, disabled, or has renamed a seam. The right-click menu isn't shown; a
   runtime failure opening the reader falls back to ours; a missing or corrupt sidecar yields no
   annotations. Interop is a bonus that can vanish; it is never load-bearing.

The one genuine fragility — ZotFlow's view-type string and sidecar name — is isolated in a single file and
guarded, so if ZotFlow changes them, detection simply returns false and we fall back. That is the correct
failure mode, and the reason the whole surface is wrapped rather than assumed.

### Tested where it counts

The parsing of ZotFlow's foreign file format is the part most likely to meet corrupt or version-changed
input, so it is the part most thoroughly tested (13 cases): malformed JSON, missing fields, wrong-typed
fields, and per-annotation validation all degrade to "no annotations" rather than throwing. Detection and
file-opening need a live Obsidian and ZotFlow install, so they are guarded at their call sites instead.

686 tests (was 673). Four gates green.

## Phase 118 — measuring search relevance (and the bug that fell out of it)

For six phases the README carried the same admission: the search had never been evaluated for relevance.
650 tests proved the code did what it *said*; none proved it returned what a person actually *wanted*.
Those are different claims, and only the first was backed. This phase backs the second.

### The instrument

Three pieces, built in the order that keeps them honest:

1. **Metrics** (`eval-metrics.ts`) — precision@k, recall@k, MRR, nDCG@k, textbook definitions implemented
   plainly and checked against hand-computed values (16 tests). A metric that is subtly wrong flatters or
   damns the ranker for no reason, so the measuring instrument is itself measured first.
2. **A judged corpus** (`fixtures/eval-corpus.ts`) — twelve documents on a coffee knowledge base, chosen
   for natural vocabulary overlap (brewing/extraction, grind/burr) that separates keyword matching from
   meaning, plus ten queries whose relevant answers were decided *by reading and judging*, before the
   search was ever run. The whole exercise's integrity rests on that ordering: a corpus reverse-engineered
   from what the ranker already does would always score well and prove nothing. Each judgement carries a
   written rationale so a reader can check it rather than trust it.
3. **A harness** (`eval-harness.ts`) that indexes the corpus, runs the queries through the real
   `SearchIndex`, and reduces the results to those metrics.

### What it found

Running it exposed a genuine bug the unit tests never could. The query *"extraction and flavour"* returned
a single result where three documents were relevant — because the parser treated the lowercase word
**"and"** as the boolean AND operator, collapsing a broad query to the one document containing every term.
The convention everywhere else (Google, Lucene's default) is that only UPPERCASE `AND`/`OR`/`NOT` are
operators; lowercase are ordinary words. Fixed. The evaluation measured the effect: that query went from
recall 0.33 / nDCG 0.47 to 0.67 / 0.84, and the corpus aggregate nDCG@10 rose from 0.873 to **0.910**.
This is the loop the harness exists for — measure, find a real defect, fix, measure the gain.

### What it declined to do

A weight sweep across the field boosts moved the aggregate nDCG by less than 0.01 in either direction, and
pushing the title boost higher actively *hurt*. The disciplined conclusion is not to hunt a marginally
better number on twelve documents and call it tuning — that is overfitting, and dishonest. So the shipped
defaults are unchanged; what changed is that they are now *tested to be non-harmful* rather than asserted,
and the far larger relevance win came from the parser fix, not from the weights.

The eval now runs on every build as a regression gate, with thresholds set just below the measured
baseline — floors to defend and ratchet upward, not targets that happen to pass today. The honest caveat
that replaces the old one: this is twelve documents, not vault scale.

673 tests (was 650). Four gates green.

## Phase 117 — command hygiene and accessibility

Two kinds of polish that the compiler and the unit suite are both blind to.

### Commands hide when they can't run, instead of scolding

Seven commands — focus mode, and the DOI/dedup/citation/shard academic commands — used a plain
`callback` that, when no Knowledge View was open, fired a Notice: "Open a Knowledge View first." Obsidian's
convention is the opposite: a context-dependent command should be *absent* from the palette when its
context is missing, via `checkCallback` returning `false`. Present-but-failing is a small papercut every
time someone scrolls past a command they can't use. All seven now hide cleanly. (The reference-import
command kept its structure: it works with or without a view, so it should stay visible; its one Notice is
a settings-state message, not a missing-context one.)

### Accessibility: four gaps a screen-reader user actually hits

The table was already in good shape — real `<table>` semantics, `scope`, `role="columnheader"`,
`aria-sort`, keyboard-activated sorting. The gaps were elsewhere:

- **The virtualized table lied about its size.** With windowing, the DOM holds only a dozen rows, so a
  screen reader counting `<tr>`s announced "row 5 of 8" for a 500-row table. Now `role="grid"` +
  `aria-rowcount` (the true total) + `aria-rowindex` on every row (its true position) — so assistive tech
  navigates the whole grid, not the window. One subtlety caught in review: group-header rows also receive
  an index, so the count has to include them, or an index would exceed the stated total whenever grouping
  is on.
- **The save-status indicator was silent.** A sighted user sees "Saving… / Saved / Not saved"; a screen
  reader got nothing — including no signal that a write *failed*. It is now an `aria-live="polite"`
  `role="status"` region, so every transition is announced without stealing focus from the cell being
  edited.
- **Popovers stranded the keyboard.** Opening the view menu or properties popover left focus on the page
  behind it, Tab walked the wrong things, and Escape had nowhere to return focus to. Popovers are now
  `role="dialog"`; focus moves to the first control on open and returns to whatever opened it on close
  (guarding against that trigger having been removed meanwhile).
- **Search results weren't announced.** The result count ("12 results" / "No matches" / "5 passages") is
  now a live region, so a screen-reader user learns their query returned something without leaving the
  search box.

Each is wired with a source-level guard test — presence, not runtime correctness, which is the honest
limit of a static check, but enough to stop the wiring being silently dropped later.

650 tests (was 643). Four gates green.

## Phase 116 — decomposing the god object: the academic kit moves out

`DashboardView` was 3,130 lines. It built the toolbar, managed tabs, switched layouts, saved, rendered —
and, incongruously, knew how to talk to Crossref. Roughly 540 of those lines were the academic-research
kit: DOI capture and fill, duplicate detection, citation-graph linking, library sharding, and BibTeX/CSV
reference import. A view class reaching out to academic metadata APIs is exactly the layering violation
the rest of this codebase is careful to avoid, and it made every future change to either concern more
expensive.

The kit now lives in its own `AcademicController` (639 lines), reached through a deliberately narrow
`AcademicHost` interface. The controller owns everything academic — the DOI clients, the dedup detector,
the shard writer, and the one piece of state that logic carries (the citation-key index). The host
supplies only the live view state the kit cannot know on its own: the current and rendered profiles, the
on-screen rows, the search string, the shared write-with-undo path, and a redraw callback. `DashboardView`
dropped to **2,587 lines** — a 17% cut — and its public academic commands became one-line delegations, so
`main.ts` still calls them on the view and nothing downstream changed.

### Behaviour is provably unchanged

A refactor this size is only worth doing if it changes nothing, so that was checked rather than assumed.
The moved method bodies were compared, normalised, against their originals from git: every difference is
exactly the mechanical `this.search` → `this.host.search()` and `this.lastRows` → `this.host.lastRows()`
indirection the extraction deliberately introduced — no logic drift anywhere. That, plus the full suite
staying green, is the evidence.

### The boundary is a tested invariant, not a hope

The entire value of the split is the boundary holding. If the controller ever imports the view, or
reaches into `TextFileView`/`WorkspaceLeaf`/`contentEl`, or the view's delegators grow back into real
implementations, the god object quietly reassembles. So a guard test enforces all of it: the dependency
points one way only, the controller talks solely through `this.host`, and each public command is a genuine
delegation. Four assertions that make regression a failing test rather than a slow slide.

Which methods stayed on the view was a judgment call, not a line-count cut: `promoteToNote`,
`addRowAndEdit`, and `forceRefresh` touch academic state but are not academic features, and the BibTeX
*export* branch is cohesive with the export flow, not the kit — so those stayed, and the shared
`applyRowEdits`/`appendTargetFor` write helpers stayed on the view with the kit calling into them, rather
than the kit owning a second copy.

643 tests (was 639). Four gates green.

## Phase 115 — chart.js off the startup path (and why shrinking it is a dead end)

chart.js is ~190 KB of the bundle and exactly one of the seven layouts uses it — yet a static
`import` put it on the parse-and-execute path of *every* plugin launch, including for the majority who
never open a chart. This phase takes it off that path. The result is measured, and the measurement is not
the one the plan assumed.

### The obvious fix doesn't work, for a concrete reason

The plan was to tree-shake chart.js smaller by registering only the components the six chart kinds draw
(bar, line, doughnut controllers; category + linear scales; the matching elements; legend, tooltip,
filler) instead of `registerables`, which pulls in radar, polar-area, bubble, scatter, and log/time
scales this view cannot produce. That change is correct and was kept — but it saves **~7 KB, not the
expected chunk**. The reason: chart.js v4 ships as a single pre-rolled `dist/chart.js` where the internals
are already minified to single-letter names, so esbuild sees one opaque module and cannot drop the unused
controllers *inside* it. v4 exposes only three import subpaths (`.`, `./auto`, `./helpers`) — no granular
controller/scale entry points — so there is no shakeable seam to import through. Registering less stops
that code *executing*; it does not stop it *shipping*.

### The lever that actually moves: defer execution, not bytes

So the win is not a smaller library — it is not running the library at startup at all. chart.js now loads
via a dynamic `import()` inside a new optional `prepare()` hook on the view interface, called and awaited
by `renderProfile` before a view draws. Only the chart view defines it; every other view leaves it
undefined and is untouched.

The subtlety worth recording: Obsidian requires a single CJS `main.js`, and code-splitting needs ESM
output — so esbuild **inlines** the dynamically-imported chart.js rather than emitting a separate chunk.
The bytes stay in the bundle (the total is in fact ~1.4 KB *larger*, from the lazy-init wrapper). It would
be easy to conclude the deferral therefore did nothing. It didn't do nothing — verified empirically:
esbuild wraps a dynamically-imported module in a lazy init function, so requiring the built bundle runs in
~10 ms and chart.js's ~400 KB of module code executes only on the first `import()`, not at load. Bytes are
downloaded once and cached; startup execution is paid on every single launch. Trading a 1.4 KB byte
increase to remove ~190 KB of startup execution is the right side of that trade.

A guard test locks it in: chart.js may be imported only via dynamic `import()` or a type-only import,
never statically. That deferral is one stray `import { Chart } from "chart.js"` from silent reversal, and
no other gate would catch it — it would still typecheck, test, build, and lint.

639 tests (was 636). Four gates green.

## Phase 114 — container queries, and the CSS gate that was missing

Obsidian is a tiling window manager. The same dashboard can be a full pane on a laptop, a 380px sidebar
split on a 4K monitor, or a small pop-out — all at the *same window width*. So the handful of
`@media (max-width: …)` rules were answering the wrong question: they measure the window, and the window
is not the pane. A dashboard crammed into a narrow split still got the wide-pane layout, because the
monitor was wide.

The fix is **container queries**. Two elements are now named containment contexts — the dashboard root
(`kvs-cq-root`) and the shared per-layout host (`kvs-cq-view`, which also wraps views embedded in notes)
— and the width-dependent rules key off `@container`, not `@media`. The same view now adapts the same way
whether it is narrow on a phone or narrow in a split on a big screen. That desktop case is the one the old
rules never caught, and the reason this is not just a mobile fix. (Supported since the plugin's floor,
Obsidian 1.10 / its Chromium.)

What actually changed behaviourally:

- **The dashboard toolbar gained a narrow-pane path it never had.** There was *no* toolbar
  responsiveness before — it simply wrapped to a second row, pushing the data below the fold. Now, below
  ~600px of pane it scrolls sideways and tightens; below ~420px the view-switcher drops its label to an
  icon, the layout tabs shrink to icons, and the search field becomes a flexible remainder.
- **Boards and the formula editor fold on their host width**, so an embedded board in a narrow note
  column behaves like a narrow board, independent of the window.
- **Search fields stopped being sized to the monitor.** They were `max-width: 40vw` — nearly half a 4K
  screen while sitting in a sidebar. Now `cqi` (a fraction of their container).

### The gate that let this class of bug exist

Fixing the above surfaced something worse: **CSS is the one layer no gate tests.** `tsc` cannot see it,
`vitest` does not render it, `eslint` lints TypeScript. Proof that this matters — the Phase 113 mobile CSS
itself shipped selectors for `.kvs-toolbar`, `.kvs-tabs`, `.kvs-tab`, `.kvs-layout-tabs`: **none of which
the dashboard emits.** Those touch-target and scroll rules were dead on arrival and every gate stayed
green. Converting to container queries forced a DOM re-audit, which is how it was caught and corrected
(the real classes are `.kvs-toolbar-bar`, `.kvs-view-tabs`, `.kvs-layout-tabs-inline`, `.kvs-view-tab`,
`.kvs-tb-icon`).

So there is now a **stylesheet guard test**: it parses every `.kvs-…` class the stylesheet targets and
asserts each corresponds to a class the source actually puts on an element (accounting for classes built
at runtime like `kvs-attach-${kind}`). It cannot prove a rule looks right, but it proves a rule can match
*something* — the exact failure that bit us twice this session. Running it also turned up **19 genuinely
dead selectors inherited from earlier phases**; those are captured in an explicit, itemised debt list
that the guard forbids from growing, so old rot is visible and new rot is impossible. Deleting that list
to empty is a deliberate follow-up, kept out of this change so it stays reviewable.

636 tests (was 633). Four gates green. Bundle unchanged at 2.9 MB.

## Phase 113 — mobile: making `isDesktopOnly: false` true instead of just claimed

The manifest said the plugin runs on mobile. The code disagreed in three places, and one of them was
the same *shape* of untruth as the `minAppVersion: 1.5.0` from Phase 106 — a compatibility claim we had
not earned. This phase earns it.

### The interactions that were not "degraded on touch" — they were impossible

Moving a board card, reordering a sort key, and resizing a table column all used **HTML5 drag-and-drop**
(`draggable`, `dragstart`, `drop`). Those events **do not fire on touch at all**. Not badly — never. So
on a phone, a kanban board silently could not be rearranged, and a column silently could not be resized.
No error, no hint; the gesture simply did nothing.

Replaced wholesale with **Pointer Events**, which are the one input API that covers mouse, touch and pen
on a single code path (`src/util/pointer-drag.ts`). The hard part is not the plumbing, it is one
question: *when does a press become a drag?*

- A **handle** (a resize grip, a reorder grip) is only ever a drag, so it activates immediately.
- A **card** is also something you scroll past and tap, so on touch its drag begins only after a **long
  press** — and a finger that moves first is scrolling, and is left alone.

That decision is a pure state machine (`pressStart`/`pressMove`/`pressHold`/`pressCancel`), tested in
isolation, because "did this finger mean to scroll or to drag" is impossible to eyeball and easy to get
subtly wrong. A second test file drives the DOM bindings through a lightweight element double — proving
the listeners actually fire from a `pointerType: "touch"` event, which is the exact regression that would
bring the whole bug back. 13 + 6 new tests.

Because a drag can no longer be the *only* way to move a card (a keyboard cannot perform one, a screen
reader cannot see one), every board card now also has a **"Move to…" menu** — the same write, reachable
by tap, right-click, or keyboard. The board stopped being drag-or-nothing.

### The settings that synced from a laptop and punished a phone

`data.json` syncs. So "index the full text of every PDF" and "use the neural engine", chosen on a
desktop, arrived on the phone as decisions it never made and could not afford — pdf.js over a library of
books, or downloading and running a sentence-transformer, on a battery and a fraction of the memory.

The worst part: the settings panel already *warned* about this, in words, and then let the phone do it
anyway. **Advice a program declines to act on is not a safeguard.** So the device now gets a veto,
applied in one pure place (`applyDevicePolicy`, tested with 12 cases):

- Attachments are indexed on mobile only if you asked *for mobile*, as a **separate toggle**. Notes are
  always indexed — they are cheap, and they are the point.
- The neural engine **never** runs on mobile; semantic search falls back to the built-in engine (which
  downloads nothing), rather than failing. The engine dropdown now says so plainly when you are on a
  phone, instead of claiming an engine that isn't the one running.

### The rest of the touch surface

- **PDF highlighting worked only via `mouseup`**, which a touch selection does not reliably produce —
  a phone selection is a long-press adjusted with drag handles, and each ends in `touchend`. The swatch
  bar now binds `touchend` (so it appears at all) and `touchstart` on the swatches (a tap's synthesized
  `mousedown` arrives *after* the selection is already gone). Additive: desktop behaviour is unchanged.
- **Six affordances were revealed only on `:hover`** — the copy button, the attachment remove, the
  annotation tools, and others. A touchscreen has no hover, so these were not hard to find, they were
  invisible. Now forced visible under `@media (hover: none)` — which correctly leaves a tablet-with-mouse
  alone, unlike a width guess would.
- **Touch targets**: the toolbar's ~26px icon buttons are a coin-toss for a fingertip. Restored to the
  44px floor Apple and Google both specify — but only under `body.is-phone`, a class Obsidian sets for us
  and the plugin had never once used. Toolbars scroll horizontally rather than wrapping; board columns
  widen to nearly fill a phone screen; the 4px column divider gets a 20px *hit* area without a wider
  *line*.

633 tests (was 606). Four gates green. Bundle unchanged at 2.9 MB — this is all behaviour, not weight.
The load-time diet (chart.js tree-shaking) and container-query responsiveness are still ahead (P2/P3).

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

## Phase 107 — Related notes, an honest semantic fallback, and an optional neural engine

Prompted by comparing our semantic search against `obsidian-similarity`, which uses a real
sentence-transformer. That comparison was uncomfortable and useful.

### A real bug, found by taking the comparison seriously
Our built-in semantic engine (Random Indexing) learns from *your* vault. If you search for a word your
notes have never contained, every vector component is zero and it returned **nothing** — silently telling
you "there is nothing here" when keyword search would have found the note immediately. Searching
"automobile" in a vault that says "car" returned an empty list.

It now **detects that it has nothing to say** (`canAnswer`) and **falls back to keyword search** instead
of pretending the note doesn't exist. Tested.

### Related notes panel
A sidebar showing the notes most similar *by meaning* to the one you're reading — not by links, not by
tags. Click to open; hover to insert a wikilink at the cursor. A quiet strength bar shows how close each
one is. Works with either engine. (`Show related notes` in the command palette.)

### An optional neural engine
The built-in engine downloads nothing and never will — but it cannot know "car" and "automobile" are the
same thing unless your own notes taught it, and it is weakest on a **small** vault, where there is least
to learn from.

So the semantic engine is now a choice:
- **Built-in** (default) — learns from your vault. **Zero network, zero download, ever.**
- **Neural** (opt-in) — `all-MiniLM-L6-v2` in a sandboxed iframe. Genuinely better at meaning. Fetches
  the model (~25 MB) once, then runs entirely on-device. **Your notes are never sent anywhere.**

The settings screen states plainly what is downloaded, from where, and what is not sent — and the README
now carries the **Network use** disclosure Obsidian's developer policy requires.

**544 tests.**

## Phase 108 — Column summaries (adopted from Note Database)

Comparing against `obsidian-note-database` surfaced the one thing every database product has and we
didn't: a **summary footer**.

Click under any column to summarise it — **Sum, Average, Min, Max, Range, Count, Unique, Empty, Filled,
Percent filled**. It summarises the rows *currently shown*, so it respects your filter: the question
people actually have is "what does what I'm looking at add up to?", not "what does my whole vault add
up to."

It was nearly free: the arithmetic already existed inside **rollups**, which aggregate across the notes a
relation points to. A summary is the same arithmetic pointed at a simpler question. Extracting it made it
pure, and therefore testable.

Details that matter: a summary over a column with no values says **nothing**, rather than inventing a
`0`. The footer only appears once a column asks for one, so a plain table stays plain. And it is sticky,
so it stays put while you scroll.

**552 tests.**

## Phase 109 — Chart view, number display styles, per-group row limits

Three features adopted from `obsidian-note-database`, which does them well and we didn't do them at all.

### Chart view (a seventh layout)
Bar, horizontal bar, line, area, donut, and a big-number stat — over the rows that passed your filter.
Choose what to **group by** (the axis) and what to **measure** (count, sum, average, min, max, unique).
**Click a bar to open the rows behind it**: a chart you can't interrogate is a picture, not a tool.

Two decisions worth stating. The aggregation reuses `summarizeColumn` — the *same code* as the table's
summary footer — so a chart and a footer can never disagree about what a column sums to. And a bucket
whose value column holds nothing numeric charts as **0, not missing**: a missing bar is a lie about the
data, a zero bar is the truth about it. Charts follow your theme's colours rather than fighting them.

### Number display styles
A number column can be drawn as a **progress bar** or a **ring** instead of a bare figure, with a
configurable "full at" value. A column of `62`, `18`, `94` is a column of numbers; the same column as
bars is one you can *see*. A value that can't be parsed falls back to plain text rather than drawing a
misleading empty bar.

### Per-group row limits
A board grouped by a field with five thousand rows used to render five thousand cards, and nobody has
ever read the five thousandth. Set **Rows per group** and each group draws that many, with an honest
"Show N more". The group's header count always reports the **true** total — a column that says 12 when it
holds 500 would be lying, and lying about how much data someone has is the one thing a data tool must
never do.

Chart aggregation and group limiting are pure and unit-tested. **566 tests.**

## Phase 110 — The formula editor

Auditing this turned up something bigger than an upgrade: **computed columns had no editor at all.** They
existed in the data model and the evaluator, but the only way to author one was to hand-edit imported
JSON. So this is not a better formula box — it is the first one.

### A Formulas panel
A new section in the view editor, alongside Columns and Rollups. Add a formula, name it, choose its
result type, optionally write it back to a source column.

### An editor that explains itself
A formula box that only shows you the answer is close to useless, because while you are writing one the
answer is usually blank — and blank tells you nothing about **why**. So it shows three things:

- **The answer, live, against a real row from your own data** — with arrows to step through rows, because
  a formula that works on row 1 and breaks on row 7 is the normal case.
- **A searchable function reference** — every function, its signature, what it does, and an example you
  can click straight into the formula. Fields are chips you click to insert.
- **The working.** Each sub-expression and what it evaluated to, indented by nesting. When the result is
  blank it points at *the empty field that caused it* — `[Hours] → (empty)  “Hours” is empty on this row`
  — instead of leaving you to guess. That one line is the whole feature.

The trace is honest about a subtlety: `if(...)` is a function call, not a ternary, so **both branches
really do evaluate**. Rather than fake laziness, it says which argument was returned. Same for
`coalesce`, which reports which argument it fell through to.

### More functions
`today  now  days  daysfromnow  adddays  dateadd  floor  ceiling` — joining the twenty-one already there.
`dateadd` does month arithmetic through `Date`, so adding a month to 31 January behaves the way a
calendar does rather than the way milliseconds do.

A test asserts that **every documented function is actually implemented** — a reference that promises a
function that does not exist is worse than no reference.

The reference, the tracer, and the new functions are pure and unit-tested (14 tests). **580 tests.**

## Phase 111 — Relevance, made explicit (and a recency bonus)

Comparing our search against [Obsidian Seek](https://github.com/ryan-manor/Obsidian-Seek) — which tunes
its ranking against an evaluated query set — surfaced something uncomfortable. **The numbers deciding
which result you see first were constants I had invented.** A hybrid blend of `0.6 / 0.4`. Field boosts
of `3× / 2× / 1.6×`. Chosen because they sounded reasonable. Never measured.

Hiding an unmeasured guess inside a black box is worse than exposing it: exposed, at least someone can
disagree with it. So the weights now have names, defaults, and a settings panel.

### Tunable relevance
- **Semantic weight** — how much Hybrid mode weighs meaning against exact words. (Was hard-coded at 40%.)
- **Title / heading match bonus** — how much a title match outranks a body match. (Was `3×` / `2×`.)
- **Reset to defaults** — once you hand people knobs, you owe them a way back. Seek has this; we didn't.

### Recency bonus
A note edited today can rank above an identical one from two years ago. It uses **exponential decay**
rather than a cutoff — a note 179 days old and one 181 days old should not belong to different worlds —
and it is **multiplicative and bounded**, so it *breaks ties* rather than taking over. A weak-but-fresh
note still loses to a strong-but-old one, and there is a test asserting exactly that.

The 180-day half-life is **not my number**: it is the value Seek arrived at after measuring relevance
across a large query set. Borrowing a figure someone actually evaluated beats inventing one I did not.

Recency applies to **every** mode — keyword, semantic, hybrid, Ask — because how a result was found has
nothing to do with how fresh it is.

The whole model is pure and unit-tested (14 tests), including the fusion detail that matters: each
ranking is normalised against its own maximum before blending, because BM25 scores and cosine
similarities live on incomparable scales, and adding them raw would let whichever produces bigger numbers
win by arithmetic accident rather than by relevance.

**594 tests.**

### Still honest about what is missing
We now have 594 tests proving the code does what we said. We still have **no evidence that it returns
good results** — no relevance evaluation, no query set, no measurement. Those are different claims, and
only the first is currently backed. Seek has done the second; we have not.

## Phase 112 — The index can live in your vault (so search works on mobile)

The search index lived in IndexedDB: fast, invisible, and **per-device**. Index your vault on the laptop,
open it on your phone, and the phone starts from nothing. On a large vault with attachments that is not a
minor inconvenience — it is the difference between search working on mobile and not.

**Settings → Search → Where the index lives** now offers to keep it in the vault instead, as an ordinary
file. Whatever already syncs your notes — Obsidian Sync, iCloud, Dropbox, Syncthing — carries the index
too, without needing to know what it is. This is the trick [Obsidian Seek](https://github.com/ryan-manor/Obsidian-Seek)
uses, and it is the right one.

### Three decisions worth stating

**It is one file, deliberately.** Several files would sync independently, and a sync that delivered new
postings beside an old text store would leave the index quietly wrong — returning results whose snippets
no longer exist, with no error to explain it. One file is atomic: you either get the new index or keep the
old one, never half of each.

**Typed arrays needed a real codec.** IndexedDB stores JavaScript objects, so `Float32Array` survives
without anyone thinking about it. A file holds bytes, and `JSON.stringify` turns a Float32Array into
`{"0":0.1,"1":0.2,…}` — enormous, and no longer a Float32Array when it comes back. So: a versioned
container (a JSON envelope, typed arrays referenced into a single binary blob, gzipped). Tests confirm a
real keyword index and a real semantic model come back returning **identical results in identical order**
— which is the only round-trip test that means anything.

**A file we cannot read is refused, not guessed at.** Wrong version, truncated by a half-finished sync,
foreign file — all decode to nothing, and the index rebuilds. A half-understood index is worse than none.

### And the honest costs, stated in the settings panel
- The index becomes a real file your sync service must carry. Compressed, but on a large vault with
  attachments indexed it can still be tens of megabytes.
- Indexing on two devices at once may produce a sync conflict file. Harmless — the index self-corrects on
  load by re-checking every file against what it recorded — but you may see a duplicate.
- A stale index is not a broken one: whatever changed since it was written is re-indexed on load, so an
  index synced from another device saves most of the work even when out of date.
- **If you do not sync your vault, this buys you nothing.** Leave it on "this device only" — which is the
  default, for exactly that reason.

**606 tests.**
