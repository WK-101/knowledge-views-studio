# Changelog

Development notes for Knowledge Views Studio. Written for people reading the code — the *why* behind
each change, including the mistakes, because a changelog that only records what worked teaches nothing.

For what the plugin does, see the [README](README.md).

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
