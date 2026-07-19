# Knowledge Views Companion (browser extension)

Captures pages into your Obsidian vault's Knowledge Views, using **each view's own columns**.

Unlike a general web clipper, this doesn't ask you to write a template. It asks your vault what its views
look like — the columns, their types, and the values each choice column already uses — and builds the right
form from that. A view you created five minutes ago gets a correct form with nothing configured.

Everything stays on your computer. There is no account and no server beyond Obsidian itself.

## Status

Store-ready but not yet submitted. Targets **Chrome and Firefox on Windows** first.

- [PUBLISHING.md](PUBLISHING.md) — step-by-step submission, with the listing copy ready to paste
- [PRIVACY.md](PRIVACY.md) — the privacy policy both stores require

`npm run ext:package` builds the archives to upload.

## Permissions

Kept as small as they can be. `activeTab` and `scripting` read the page you're capturing, at the moment you
click; `storage` holds your settings and anything waiting to send; `alarms` retries a delayed capture; and
`http://127.0.0.1/*` reaches Obsidian on your own machine.

`tabs` is **optional** and requested only if you turn on the toolbar mark — so installing never asks to
"read your browsing history", which would be a poor first impression for a tool whose whole argument is that
nothing leaves your computer.

## Build it

From the repository root:

```bash
cd extension
node build.mjs           # development build
node build.mjs production  # minified
```

That writes `extension/dist`, which is the folder you load.

## Load it

**Chrome**

1. Go to `chrome://extensions`
2. Turn on **Developer mode** (top right)
3. **Load unpacked** → choose `extension/dist`

**Firefox**

1. Go to `about:debugging#/runtime/this-firefox`
2. **Load Temporary Add-on…** → choose `extension/dist/manifest.json`

Firefox removes temporary add-ons when it closes, so you'll reload it after restarting. That's expected
until the extension is signed.

## Connect it to your vault

1. In Obsidian: **Settings → Browser bridge**
2. Turn the bridge on
3. Choose **Generate a pairing code** — a six-digit code appears
4. In the extension: **Settings** → type the code → **Pair**

The code lasts five minutes and works once. The extension then holds a token, which you can revoke from
either side at any time.

If you changed the port in Obsidian, change it here too.

## Capturing many rows at once

When a page is *already* a set of rows — a journal's contents, a search result, a bibliography, a comparison
table — a **Rows** tab appears offering to capture all of them together. It shows what it found and previews
the first few before anything is written, because a wrong bulk import takes far longer to undo than a wrong
single one.

Two sources are trusted: real HTML tables, and JSON-LD lists (which is how most sites describe their own
listings). Repeated `<div>` layouts are deliberately *not* guessed at — that guess is wrong often enough to
produce confident nonsense.

This is the thing a row-shaped tool can do that a note-shaped one can't. Every clipper is one-page-one-note,
so a contents page becomes a single blob of prose.

## Using your selection

Select text before opening the popup and every field gains a **Use selection** button, so a highlighted
abstract, price or author can go into the column you actually want it in rather than always the description.

## Highlighting on pages

Turn it on in settings, select text on any page, and a toolbar appears: four colours, or a colour with a
note. Highlights are painted again whenever you revisit the page, land in the page's row, and — when the
page has a dedicated note — under its `## Annotations` heading. Click a highlight to read, note, or remove
it. A page with no row yet gets one automatically: a highlight is the strongest signal a page matters.

## Rules for particular sites

Papers from arXiv always belong in the same view, in the same shape. Articles from a newspaper belong
somewhere else. A rule says so once, and every capture from that site follows it.

The most specific rule wins, so `scholar.google.com` can behave differently from `google.com` without either
rule knowing the other exists. A rule can also set the shape and add tags.

Beyond that: a default view, returning to whichever view you used last, whether captured notes include the
article body, whether a selection is kept as a quotation, tags added to everything, and which mode search
opens in.

## Two surfaces

**The popup** is a glance: capture what's in front of you, save a highlight, search. It comes in three sizes
— small, medium and large — because a quick filing tool and a place to review before saving are different
needs, and one compromise width serves neither.

**The sidebar** stays open while you browse and does everything: capture, highlight, edit, search, and your
**views** — a reading queue or paper list you can work through, changing a status without switching
applications. Open it from your browser's sidebar control (Firefox: View → Sidebar → Knowledge Views).

Both run the same code. What differs is how much room there is and which panels are worth showing.

## Highlights

Select text, save it. What's stored is the quoted passage plus a little of what surrounds it — never a
position, which points at something else the moment a page rerenders. When a passage genuinely has gone, the
highlight reports it rather than silently attaching to a neighbouring sentence.

Highlights you've already saved from a page are listed when you return to it.

## Changing what you already have

When a page is already in your vault, an **Update** tab appears. Mark it read, set a rating, move its status
— without leaving the page or hunting for the row in Obsidian.

This is the half no general web clipper can offer, because a clipper has no idea what a row is. The vault
decides what may be written: computed values and fields owned by another source are refused and reported
rather than silently overwritten.

## Marking search results

The companion can show which search results you already have — the question worth asking *before* you click,
rather than after. Off by default; turning it on asks for access to Google, Scholar, DuckDuckGo, Bing, Brave,
arXiv, PubMed and Semantic Scholar in **one** prompt, not site by site.

Only the addresses of results are checked, and the vault answers with nothing but which of them it
recognised — no titles, no paths, no view names. The page-side script never holds your vault token.

## Searching your vault

The popup's second tab searches the vault itself — notes, table rows, annotations, attachments and Zotero —
in three ways: **keyword** (phrases, exclusions, tags), **meaning** (finds notes that say it differently),
and **ask** (a question; the passages that answer it are the result). Hits open in Obsidian; Zotero items and
saved links open where they actually are.

Searching is its own permission in Obsidian, off by default, under **Settings → Browser bridge → Allow
searching**. It's separate from the others deliberately: reading tells the extension the *shape* of your
views, while searching can return the text inside your notes.

## Knowing what you already have

The companion can mark its toolbar icon when a page is already in your vault. It's off by default, in the
extension's settings, because turning it on means every page you visit gets checked against your vault. That
check never leaves your computer — but it's a thing to choose, not to assume.

## Using it

Click the toolbar button on any page. The companion reads the page, picks a view, pre-fills what it found,
and tells you if the page already appears in that view. Selecting text before you click uses your selection
as the description.

If Obsidian isn't running, the capture is **held and sent when it is** — captures aren't lost because the
app happened to be closed. Anything waiting is listed in the extension's settings.

## What it reads

Meta tags (OpenGraph, Dublin Core, academic citation tags), Schema.org JSON-LD, the page title and URL,
your selection, and a representative opening paragraph when the page offers no description. Reading the
*live* page is the point: a re-fetch from the plugin would miss anything rendered by script, anything you
expanded, and anything behind a login.

## Layout

```
extension/
  manifest.json      Chrome + Firefox MV3
  build.mjs          esbuild → dist/
  src/
    popup.ts         the capture window: schema-driven form
    options.ts       pairing and connection
    background.ts    drains the offline queue
    lib/
      bridge-client.ts  talks to the vault
      page-reader.ts    injected into the tab; self-contained by necessity
      queue-store.ts    persistence for held captures
../shared/           the wire contract and pure logic, shared with the plugin
```

`shared/` is deliberately shared rather than duplicated: a change to a wire shape becomes a compile error in
whichever half hasn't kept up.

## Not yet built

Multi-row capture from tables and lists, and highlight-to-field mapping. The bridge already has the shape
for both.
