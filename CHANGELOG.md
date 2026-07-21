# Changelog

Development notes for Knowledge Views Studio. Written for people reading the code — the *why* behind
each change, including the mistakes, because a changelog that only records what worked teaches nothing.

For what the plugin does, see the [README](README.md).

## Phase 163 — capture starts with where things already are

The capture screen used to lead with the machinery of adding — a destination picker and a form — when the
question a person actually starts with is *do I already have this?* It now leads with the answer.

**The status card.** Opening capture first shows every view whose row names this page — the full list, not
the first match, because per-view source binding exists precisely so the same page can legitimately live in
several places. Each match shows its view and title, the file the row lives in, and its actions: open in
the view (landed on the row), open the file, edit, delete. Below that, the page's dedicated note with its
own open and delete, and the page's highlight count with a clear-all. Only then, the ways to add.

**Nothing shows a button for a state it isn't in.** "Create its note" appears only on a row without one;
"Create its row" only when a note exists rowless (lookup now reports the dedicated note by identity even
when no row links it — the "note first" state is finally visible); every delete only when its target
exists. A delete for a note that doesn't exist is noise at best and a lie at worst.

**Open in view means *at the row*.** Table rows now carry the same opaque handle the bridge issues, and an
`obsidian://kvs-open` handler parks a one-shot focus request the table consumes on render: the view opens,
scrolls to the row, and flashes it briefly so the eye lands where the link pointed. On very long virtualized
tables the landing uses estimated row heights — near the row, with the flash doing the final pointing.

**Deletion, scoped the way the data deserves.** Three separate operations, never one button: the row
(through the shared writer, snapshot first, undoable from Obsidian's edit menu like any in-app deletion);
the dedicated note (to the vault's *trash*, recoverable — notes are writing, and writing deserves an undo);
the highlights (sidecar only, count reported). Each write-gated, each confirming with a second click, and
deleting a note that isn't there says so rather than pretending.

1315 tests (was 1309): row deletion by handle through the shared writer; stale handles refused rather than
deleting whatever is there now; write permission on all three deletes; trash refusing to pretend; the
highlight clear-count; and lookup reporting the page's note when no row links it.

## Phase 162 — the two halves now check they match

The three "still broken" reports had one cause, and it wasn't in any of the features: the vault was running
an old plugin while the companion called endpoints that only exist in new ones. The newest *release* of the
plugin is 0.140.0 — before the whole capture model, promotion, and every annotation endpoint — and the two
halves ship separately with nothing anywhere comparing them. Every 404 surfaced as its symptom: highlights
"vanishing" (the save endpoint didn't exist), captures following years-old rules, three sessions of
plugin-side fixes that never ran. The diagnosis shipped in 0.161 couldn't help either — it was itself
plugin-side.

**The vault now names its version** in ping and schema responses, and the companion refuses to proceed
quietly when it's older than required: the popup, sidebar, and settings all state the version they found,
the version they need, and that until the plugin is updated, captures and highlights will misbehave in
confusing ways. A missing version reads as too old, because plugins that predate reporting are precisely
the old ones.

**A 404 from a paired vault now says "update the plugin"** instead of "not found" — from a paired vault it
means exactly that, and "not found" reads as a bug in the wrong half.

**And the build is now delivered installable.** The repository zips exclude `main.js` (a build artifact),
which silently meant no way to actually run what was built without a release or a local build. A
`kvs-plugin-install` zip now ships alongside: manifest, build, and styles, ready to drop into the vault's
plugin folder.

1304 → 1309 tests: the version comparison — required and newer accepted, older rejected, missing and
unreadable read as too old (the case that actually happened), numeric not textual ordering, and the message
naming what it found.

## Phase 161 — the vanishing explained, and captures that say where they went

Four reports from real use, and the pattern across all four is the same lesson: features were behaving
lawfully and saying nothing, which from the outside is indistinguishable from being broken.

**A capture now says so when its view will never show it.** A capture target is a file path, and nothing
required it to be a file the view actually reads — so a save could succeed into a file outside the view's
sources, and "I saved it and it isn't there" looked exactly like data loss. After writing, the view is
re-read; if the new row isn't visible in it, the response says which file was written and what to change
(add the file to the view's sources, or repoint the capture target). The surface treats that warning as
more important than the success it accompanies, and no longer closes before it can be read. This is the
likeliest explanation for rows that "disappeared", and it will now name itself.

**A refused highlight now says why.** On save failure the highlight unpaints — that's correct; the page must
never show a highlight the vault refused — but it did so wordlessly, so every configuration problem
(writing turned off, no writable view, a view with no matching columns) read as a glitch. The reason now
travels the whole way: route → background → a small toast on the page, alongside the unpaint.

**Highlights have a home you choose.** A "Highlights go to" view picker in the Highlighting settings,
outranking the per-site resolution when set — plus, in plain words, where each highlight actually lands
(row cell, dedicated note, plugin store) and that a page with no row gets one automatically.

**Saved view choices now *look* saved.** The settings page applied saved values to the view pickers before
the view list had arrived, and setting a select's value before its options exist silently does nothing — so
every saved choice displayed as "— first available —", which read precisely as "changing the default view
does nothing". The values are now re-applied after the options exist. (The preference itself was always
saved; only its reflection was broken — but a setting that doesn't look saved may as well not be.)

**The sidebar can re-read the page.** It reads the page once, when it starts — which is why it never
offered "Use selection" (your selection didn't exist yet) and went stale when you navigated. One button
re-reads: current page, current selection, redrawn form. The popup never needed this because opening it
*is* the moment of capture; the sidebar's whole point is staying open, so it needs the moment back.

1301 → 1304 tests: the annotation-view preference surviving normalization; the invisible-capture warning
firing when the written file isn't among the view's rows and staying silent when it is.

## Phase 160 — a richer annotator, and settings with a shape

**The settings page now works like the plugin's own**: a nav of sections — Connection, Captures,
Highlighting, Sidebar, More — one visible at a time, each setting housed where a person would look for it.
Settings pages fail by becoming one long scroll where nothing has a home; someone looking for the sidebar
switch shouldn't have to read about pairing codes on the way.

**The sidebar is one tick now.** All it ever actually needed was the page-reading permission — the popup
borrows that from the toolbar click, and a sidebar has no click to borrow from. The checkbox asks for it
(from the click, before any await — the gesture rule), and success reveals where each browser hides its
sidebar. Unticking doesn't revoke anything; permissions are removed in the browser's own settings, and the
page says so instead of pretending.

**"No view can receive captures" now explains itself.** Three different situations shared that sentence —
the wrong vault answering, writing turned off in the plugin, and views without capture targets — and
nothing on screen distinguished them. The message now names the vault it reached and lists what each view
said (the plugin has always sent per-view reasons; the surface threw them away). The settings page's view
count now reads "5 views from X, 0 can receive captures" with the reasons, which is a diagnosis rather than
a number. Found and fixed nearby: the background chose views for annotations by the wrong test (`capture`
present rather than `capture.writable`), so a highlight could be sent to a view that would refuse it.

**The annotator grew into a tool.** Six colours (purple and orange join), and two styles — paint or
underline. The toolbar starts from whatever colour and style you used last, remembered across pages.
Clicking an existing highlight now offers recolour (in place, nothing re-anchors), restyle, copy the quoted
text, note, and remove. Sidecar entries written by the previous version read cleanly: a missing style is a
highlight, junk colours fall back to yellow.

1299 tests held, plus style and colour coercion pinned across all six colours and both styles.

## Phase 159 — the web annotator (Phase B)

Select text on any page and a small toolbar appears: four colours, or a colour with a note. The highlight is
painted immediately, lands in the page's row, in the page's dedicated note when it has one — and is painted
again every time the page is revisited. Click a painted highlight to read its note, edit it, or remove it.
This is the shape the reference tools (Web Highlights, WuCai, Hypothesis) established, with the vault as the
store.

**One highlight, three homes — by design.** The *sidecar* (a JSON file beside the plugin's data, syncing
wherever the vault syncs) is the machine's copy: anchors, colours, ids, the only thing the painter ever
reads. The *row cell* gets the glanceable copy — `==quote== — note` — readable in any table view. The
*dedicated note* gets a proper blockquote under `## Annotations`, created if the template lacks the heading.
Nothing ever parses the human copies back, which is precisely what makes them safe to edit: reword the cell
line, annotate the blockquote, and no highlight breaks.

**Painting is anchor-based, never position-based.** On revisit, the saved quote is located in the page's raw
text — whitespace-tolerant, because a re-render's whitespace is exactly what changes; context-scored when
the quote appears more than once; and refused when occurrences can't be told apart, because a highlight
painted on the wrong words teaches distrust of every mark. A skipped paint is still in the vault and listed
in the companion.

**A highlight can create its row.** Highlighting a page with no row yet captures it first — metadata into
the view the site's rule (or default) names — then annotates. A highlight is the strongest signal a page
matters; it shouldn't be lost to filing order. The sidecar write comes last in the chain, so nothing ever
claims a highlight the row copy silently lost.

**Removal respects people.** Deleting a highlight strips exactly the line it wrote from the cell — matched
whole, so a line someone reworded by hand no longer matches and survives. Their edit outranks our
bookkeeping.

**The trust boundaries hold.** All UI lives in a closed shadow root the page's CSS can't touch; the only
additions to the page's own tree are the highlight marks themselves, styled inline, dark-mode aware. The
pairing token never enters the content script — everything goes through the background worker, and the
worker resolves which view a highlight lands in the same way captures do: site rule, then default, then
last-used, then first writable.

**Asked for, not assumed.** The annotator is off until enabled in settings; enabling asks for page access in
the same click (the gesture rule the search toggle taught us), registers the content script, and
re-registers it after browser restarts while the permission holds. The companion's Highlight tab now reads
the structured store — colour dots, notes, per-highlight removal — instead of scraping rows.

1299 tests (was 1253): the annotation model — ids, replace-by-id, tolerant reading of a hand-editable file,
colour fallback, the cell line staying one line and never leaking bookkeeping, the note blockquote; locating
— re-rendered whitespace, non-breaking spaces, regex-special quotes, lone-occurrence tolerance, duplicate
refusal, context disambiguation; the sidecar surviving corruption and dropping only what can't paint; the
route orchestration — cell text purity, row-creation-then-annotate, permission gating, wire hygiene, and
removal cleaning exactly its own line; and adversarial pipes, quotes and `<br>` literals through the cell
append path.

## Phase 158 — the capture model (Phase A)

The foundation for the row-and-note workflow: a row collects a page's data; when a page deserves its own
note, one click makes it — pre-filled, linked both ways; and if the note came first, the row finds it. The
surfaces come later; this phase is the model itself, and every decision in it is testable without a browser.

**Identity, generalised.** Promotion only worked for papers because only academic views had an identity key
(the DOI). Everything else now defaults to `source` — the page's normalised URL — so a reading list's rows
can have dedicated notes on exactly the machinery papers already used. DOIs stay the academic identity,
which is right: the same paper lives at arXiv, at the publisher, and as a PDF — three URLs, one DOI. URL
matching uses the same normalisation as the rest of the bridge, so a note saved from a link with campaign
parameters is still the note for the clean URL.

**One template engine.** Promoted notes now render through the shared engine that captured notes and the
companion's previews use. Every old `{{placeholder}}` template keeps working — the old syntax was a strict
subset — and filters now work in promoted templates too. All seven existing promoted-note tests pass
unchanged, which is the compatibility claim in executable form.

**Promotion for any row.** A new pure planner turns any row into its note: every cell becomes a template
variable under its own name, so a view with a `Rating` column can write `{{rating}}` without declaring
anything. The note always carries its identity in frontmatter — written in even when the template forgets —
because a note that loses its identity is an orphan the next promote can't find. `POST /promote` exposes it:
idempotent (found before created), stale-handle-safe, write-permission-gated, and the wikilink is backfilled
into the row through the same writer as every other edit.

**The reverse direction.** Capturing a row for a page whose note already exists links them at capture time —
the `Note` column gets its wikilink without anyone noticing the pair and connecting it by hand. That's the
"I made the note first, weeks ago" case, handled where it happens.

**Append into an existing note.** Page-shaped capture could only create files, which quietly assumed every
capture deserves its own note. A capture can now go *inside* something — a daily log, a running inbox, a
topic note — under a chosen heading, at the end of that heading's section. The section logic understands
ownership: `## Captured` owns its `### subsections`, so an append lands after them, not in the middle. What
gets appended is a block (source line, then body), deliberately never frontmatter, which in the middle of a
note would corrupt it.

**Append into a cell.** An update can now say `mode: "append"` per change: the cell keeps what it has and
the addition joins it, `<br>`-separated. The final value is composed at the bridge against the row the vault
just produced — never by the caller — so appending can't replay a stale copy of a cell over a newer one.
This is the write path web annotations will use.

**Lookup says more.** A match now reports whether its row already has a dedicated note, so a surface can
offer "open the note" instead of "create one".

1253 tests (was 1209): identity defaulting and loose URL matching that still keeps identifying parameters
distinct; promotion planning — aliases, titles, folders, sanitisation, the link column, cells-as-variables;
heading-aware appending including section ownership, case-insensitive matching that refuses prefixes,
create-once semantics, gap discipline and CRLF; the append block never containing frontmatter; cell
appending — history kept, clean start, server-side composition, read-only refusal; and the promote route —
success, idempotency signalling, stale handles, permission, and honest unavailability.

## Phase 157 — four bugs, four root causes

All four reported problems traced to distinct causes. None was cosmetic.

**Search didn't work from the popup.** The tab wiring registered a dashboard panel unconditionally, but only
the sidebar has one — so the popup's panel map held a null. The first tab click threw as soon as the loop
reached it, and because Search is registered *after* the dashboard, it was never shown and never mounted.
Typechecking couldn't see this: the element was cast as present. A missing element is now absent from the
map rather than stored as nothing, and there is a test that reads both pages and checks that every tab has
its panel, every panel has its button, and both carry what the shared script reaches for.

**The sidebar couldn't read pages the popup handled seconds earlier.** Not a bug in the reading — a
permission the sidebar never had. `activeTab` is granted by *clicking the toolbar button*, for that tab, at
that moment. A sidebar is never opened that way, so nothing was ever granted and every injection failed.
There's no trick around it: a surface that stays open across everything you navigate to genuinely is asking
for more than one that reads a single page on click. So it now asks, in plain words, with a button — and only
when the sidebar is actually used. The install prompt is unchanged.

**Turning on search-result marks failed without ever showing a prompt.** The handler checked whether access
was already held before requesting it, and that check was awaited — which ends the browser's willingness to
attribute a permission prompt to the click behind it. The request therefore always arrived a tick too late
and was refused outright, so the box sprang back with an error and no prompt appeared. The request is now
made first, before anything is awaited, and the helper returns its promise rather than awaiting inside.
Nothing to do with Obsidian's own search setting, which was a red herring the error message invited.

**There was no way to keep a whole page.** Note capture was reachable only from a view already configured as
note-shaped in Obsidian, and the plugin refused a note into a row-shaped view — so anyone whose views were
all row-shaped could not do the one thing every clipper does. A capture may now state its own shape: the
**Whole page** tab appears whenever a page has an article, and writes a note regardless of how the view is
set up, falling back to a `Captured` folder when the view has no note settings of its own.

**And the sidebar layout was a popup with a width override.** Both pages are now generated from one
structure — the accumulated scripted edits had left `#app` nested inside the capture panel and the header
outside it, which no stylesheet was going to rescue. The sidebar is now a proper column: fixed header and
tabs, one scrolling region sized to the real height, tabs that wrap when dragged narrow, a dashboard that
fills the space rather than guessing at a fraction of the viewport, and labels that move beside their fields
when there's room.

1209 tests (was 1198): both pages carrying every element the shared script needs; tabs and panels matching
each way round; the dashboard belonging to the sidebar alone without the popup pretending otherwise; the
whole-page tab being on both; the tolerance for a panel that isn't on this page; and the shape override,
including that a note asked of a row-shaped view still gets somewhere to live and that a note-shaped view
keeps its own folder.

## Phase 156 — settings that are about your vault, not about sockets

The last of the five phases. Until now the companion's settings were entirely plumbing — an address, a
pairing code, a queue — and every decision about *where things go* had to be made again on every capture.

**Rules for particular sites.** Papers from arXiv always belong in the same view, in the same shape; articles
from a newspaper belong somewhere else. Saying so once removes a decision from every capture rather than
adding one, which is why this is the highest-value setting rather than merely another checkbox — a choice
small enough to tolerate and frequent enough to grate is exactly the friction that quietly stops people using
something.

Matching is by host, and the most specific rule wins, so `scholar.google.com` can behave differently from
`google.com` without either rule having to know the other exists. Order in the list is deliberately not
significant: a rule that worked only because of where it happened to sit would be a miserable thing to
debug. A rule can also fix the shape and add tags, which are merged with anything the page supplied rather
than replacing it.

**And the rest of what was missing:** a default view, returning to whichever view was used last, whether a
captured note includes the article body, whether a selection is kept as a quotation, tags added to
everything, and which mode search opens in. Every one of them actually changes behaviour — a setting that
does nothing is worse than an absent one, because it invites someone to believe they've configured
something.

**Views can now be re-read on demand.** The extension caches the view list, since asking on every popup open
would make the popup slow for something that changes rarely — but it does change, and without a refresh a
rule could silently point at a view that no longer exists. Rules referring to a missing view now say so in
plain words rather than showing a bare identifier.

**One place for preferences.** They had accumulated as loose storage keys read wherever they were needed,
each with its own idea of the default. That works until two places disagree, at which point a feature is on
in one half of the extension and off in the other, and the bug is close to invisible. There is now one shape,
one set of defaults, and one normaliser that copes with whatever is actually in storage — including settings
written by earlier versions, which are preserved rather than reset.

**A correction to the previous phase's notes.** I warned that annotation anchors would appear as stray
columns. They don't: unmapped capture fields are handed back separately and never written, so anchor fields
land only where a view already has columns for them. The concern was unfounded and the code needed no
change.

1198 tests (was 1171): host extraction from urls, bare hosts, and ports; subdomain matching, and the refusal
to match a domain that merely ends the same way; specificity beating list order; rules that would never fire
being rejected rather than stored; tag merging without repetition and regardless of case; and preference
normalisation against absent, malformed, and out-of-range values, including that a missing boolean takes its
default rather than silently becoming false.

## Phase 155 — two surfaces, and the vault in a sidebar

**The sidebar.** A popup closes the moment you touch the page behind it, which is fine for filing something
and actively unpleasant for working through a list. The sidebar stays open, and so it can hold what the
popup can't: your **views**, browsable and editable while you read. A reading queue you tick off, a paper
list you re-rank, a backlog you move along — without switching applications.

Both surfaces run the same code. The popup and the sidebar are one implementation that differs in which
panels are worth showing and how much room there is to show them, which is what keeps the same action from
behaving subtly differently in the two places. Firefox and Chrome disagree about how a sidebar is declared
(`sidebar_action` against `side_panel`), so both are declared.

**Popup sizes.** Small, medium and large. Some people want a glance; others do most of their filing from it.
One fixed width makes one of those groups unhappy, and the cost of offering three is a class name.

**Dashboards needed a new endpoint.** Everything before this could answer questions *about* a page;
`POST /rows` hands back the view itself, paged and filterable, with the same opaque handle `/update` expects
on every row — so a status can be changed straight from the list, through exactly the path the popup uses
and with exactly the same safeguards. Rows also declare the columns they don't own, so the panel greys them
rather than discovering the refusal after someone tries. They're shown rather than hidden, because a value
you can see but can't change is information, while one that silently vanished is confusing.

In a narrow panel a twenty-column view is unreadable, so what's shown is chosen: whatever identifies the row,
then what you act on, then dates — and prose left out entirely.

**Highlights.** Select text and save it. What's stored is the quoted passage plus a little of what surrounds
it, never a position — a page rerenders, an advert loads, a paragraph is edited, and an offset points at
something else. When a passage genuinely has gone, reporting it missing is correct; a highlight silently
reattached to the wrong sentence would never be noticed. Highlights already saved from a page are listed on
return, since seeing what you previously thought is most of the value of having written it down.

Writing the anchor tests found a real bug in the first implementation: it joined the quote to its context
before searching, which loses the whitespace between them, so context matching never fired and a page with a
repeated sentence resolved to the **wrong** occurrence — precisely the failure the design exists to prevent.
It now scores each occurrence against its actual surroundings.

1171 tests (was 1140): anchor building at the edges of a document, whitespace collapsing, and surviving
reflow, one-sided edits and rewritten context; refusing to guess between duplicates with nothing to
distinguish them; reporting a passage genuinely gone; reading a view including paging, an absurd page size,
filtering by query and by page — including a URL written differently — declaring unowned columns, requiring
read permission, and answering identically for hidden and non-existent views; and the dashboard's column
choice, which puts identity first, then what you act on, and omits prose.

## Phase 154 — editing what you already have

Last of the three phases, and the one that changes what this is. Until now the companion could only add:
everything went one way, and the moment you wanted to mark a paper read or move its status you had to leave
the page and find the row by hand. That is what made it feel like a filing tool.

**A page already in your vault now gets an Update tab.** Change a status, set a rating, add tags, mark it
read — from the page that prompted the thought. This is the capability the plugin was built around, since
views here are editable dashboards that write back to the files beneath them, and it is the one thing a
general web clipper structurally cannot offer: it has no idea what a row is.

Two safeguards, both deliberate, both tested.

*A row reference is matched, never dereferenced.* The obvious way to say "update this row" is to send back a
file path and position — and a caller that can name a location can name **any** location, which turns an
edit into a write to somewhere nobody intended. So the handle is opaque, derived from the row's own
provenance, and on the way back in it is only ever compared against rows the vault itself produced. A forged
handle matches nothing. So does a stale one: the handle includes the row's content fingerprint, so an edit
made against an old view of the data is refused rather than applied to whatever now occupies that position.

*Read-only fields are re-checked at the bridge.* In the app they're enforced by the editing surface, which
means nothing below it would stop a computed value being overwritten with a literal — silent corruption of
the worst kind, because the number still looks like a number. A refusal is reported with its reason rather
than dropped, and the rest of the edit still applies.

Edits are deliberately **not** queued when the vault is unreachable. A capture held for hours is still worth
writing; an edit held for hours would be applied to a row that has since moved on, and quietly overwriting
someone's later change is worse than asking again.

**And the companion can now mark search results you already have.** The toolbar badge answers "do I have
*this* page?", which helps once you're already somewhere. The more valuable question comes earlier: of these
twenty results, which have I read? Answering it turns the vault from an archive you visit into something that
informs where you go.

Only the addresses are checked, and the vault answers with nothing but which of them it recognised — no
titles, no paths, no view names. The page-side script never holds the vault token; it asks the extension's
background worker, which asks the vault. A script sharing a page with whatever else that page loads is the
last place a credential belongs.

Matching is done on normalised URLs, so a result carrying campaign parameters still recognises the page you
saved without them — while parameters that genuinely identify a page (`?v=`, `?id=`) are kept, since dropping
those would merge two different pages into one.

**The install prompt is unchanged.** Declaring the search hosts in the manifest would have put "Google, Bing,
DuckDuckGo, PubMed…" in front of everyone at install, including those who never turn the feature on. Instead
all ten are requested together at the moment it's enabled — one decision rather than a site-by-site
interrogation, and none at all for anyone who doesn't want it. The script is registered at that point and
unregistered when switched off.

1140 tests (was 1107): row handles being stable, order-independent, distinct across files, positions and
extractors, changing with content, and revealing no path; forged and stale handles matching nothing; the
read-only check including case-insensitivity, partial application, and reporting what it refused; URL
normalisation recognising the same page across www, scheme, trailing slash, fragment and campaign parameters
while keeping identifying ones and ordering the rest; and search-result detection separating results from
navigation, handling academic sites where the site's own pages are the results, deduplicating, and capping
how many are asked about.

## Phase 153 — note capture, properly

Second of the three phases. Note-shaped capture was worse than missing: a view configured for notes *was*
reachable, and the bridge *would* write one — but the companion only ever sent column values, so what landed
was frontmatter with an empty body. A capture that keeps none of the article while appearing to succeed is
the worst possible failure, because nobody notices until they go looking for something that was never saved.

**The companion now reads the article.** Mozilla's Readability finds which part of a page is actually the
piece, and Turndown converts it to Markdown. Both run on a **clone** of the document — rearranging the page
someone is reading, while they watch, would be an unforgivable thing for a capture tool to do.

**This required changing how pages are read.** The old approach injected a self-contained function, which was
fine for scraping meta tags but can't carry libraries across the extension boundary. Reading is now a content
script that answers messages. That also sidesteps a trap the earlier design hit: an injected *file* is
wrapped in a function whose value never comes back, so returning a result from one silently yields nothing.

**Templates follow Obsidian Web Clipper.** `{{title}}`, `{{content}}`, `{{author}}`, `{{date|date:"YYYY-MM-DD"}}`
— so a template written for that tool works here rather than quietly producing blanks. Filters live in a
registry, which is what lets KVS's own sit beside the familiar ones without either knowing about the other:
`|wikilink` makes an Obsidian link, `|tags` turns a keyword list into tags, `|yaml` quotes anything that
would break frontmatter, `|plain` strips Markdown back to text. A custom filter can override a standard one
of the same name.

Rendering never throws. A template is edited by hand, usually while looking at a page someone wants to keep,
and failing the capture over a stray brace would lose the thing they were trying to save. Unknown variables
resolve to nothing; unknown filters pass their input through.

**The engine lives in `shared/`,** which is what makes the preview honest — the plugin renders the template
when it writes the note, and the companion renders the same template with the same code to show what you'll
get, so the two cannot drift.

**In the popup:** a Note tab appears when the chosen view captures notes, offering the whole article, just
your selection, or properties only. A selection is the default when there is one, because choosing text is a
more deliberate act than landing on a page. The file name is editable and the rendered result is previewable
before anything is written. **In Obsidian:** each note-shaped view gets a template and a file-name pattern,
with the available variables and filters listed beneath them.

Selections now keep their formatting — a highlighted list arrives as a list rather than as a run-on line.

1107 tests (was 1061): variable substitution, case and spacing tolerance, unknown variables and filters;
filter chaining, including that a chained value carries through each step rather than restarting; quoted
arguments and pipes inside quotes; every filter's behaviour; date formatting and its refusal to invent a
date from nonsense; path sanitising that leaves non-Latin titles intact; custom filters overriding and
chaining with standard ones; and the variables a page produces, including byline precedence, unparseable
URLs, and formatted selections.

The extension bundle grows to roughly 47KB for the content script, which is what the two libraries cost. The
plugin bundle is unaffected — verified, not assumed.

## Phase 152 — setup that doesn't fight you

First of three phases addressing how the companion actually feels to use. This one is about the twelve steps
between installing it and capturing anything.

**The dead end is gone.** A view with no capture target simply refused, and the companion reported "no view
can receive captures" — which reads as a fault, not as a setting nobody had been asked to fill in. Obsidian's
bridge settings now open with a checklist: bridge running, extension paired, how many views can receive
captures, whether searching is allowed. The view line has a **Set them up** button that proposes a target for
each view that lacks one, following where that view's rows already live — a view assembled from
`Reading/Books.md` captures back into `Reading/Books.md`, because anywhere else would scatter one collection
across two files. Views with no rows to follow get a new file named after them. Spreadsheet sources are never
proposed: the write path there has different rules and a mistake is harder to see.

**Nothing needs retyping.** The pairing code has a copy button, and beside it a **Copy connection link**
button that carries the port as well — so one paste on the extension side replaces two fields filled in by
hand. The extension's box accepts either that link or a bare code, forgiving the stray spaces and dashes a
pasted code arrives with. The code itself is still exact, still expires, still works once.

**The port question mostly stops existing.** A new `GET /ping` lets the companion find the bridge itself,
trying the default and a few neighbours. Its answer is the protocol version and nothing else — not the
vault's name, not whether anything is paired, not what views exist. The setup screen now checks on open and
says what it found before anyone has typed anything, with the manual address tucked behind a disclosure for
the case where it's genuinely needed.

**A security hole closed on the way.** Designing `/ping` surfaced that an empty origin allowlist permitted
*any* origin — including an ordinary web page. Since a page you're merely visiting can issue requests to
127.0.0.1, that meant any website could have discovered this plugin was installed and probed its endpoints,
with only the token turning them away. An empty allowlist now means "any **extension**", not "anything":
web origins are refused unless deliberately listed, while requests with no Origin at all (a script, curl)
are still treated as same-machine callers. An explicit allowlist is still honoured literally, so anyone with
a reason to permit a web origin still can. This closes a real gap that predates the ping endpoint.

1061 tests (was 1030): pairing input in both forms, with forgiving spacing, misordered parameters, missing
and out-of-range ports, and a round trip through the link Obsidian offers; ping recognition against
everything else that might answer on a port; target suggestion — following the dominant file, ignoring
spreadsheets, naming after the view, sanitising paths, and tie-breaking; the checklist's notion of a usable
target; and the ping endpoint's disclosure limits, including that it is unreachable from a web page.

## Phase 151 — store readiness

Step 5: getting the companion to the point where submitting it is a form-filling exercise rather than an
engineering one. Two real blockers were in the way.

**No icons.** Both stores reject an extension without them, so there are now proper ones at 16, 32, 48 and
128 pixels — a rows glyph, deliberately made of very few shapes, because anything more detailed turns to
mush at the 16-pixel size that actually matters in a toolbar.

**`tabs` was an install-time permission.** That's the permission that makes Chrome say *"read your browsing
history"* on the install prompt — an appalling first impression for a tool whose entire argument is that
nothing leaves your machine. It was only ever needed for the optional toolbar marker, so it's now an
**optional** permission, requested at the moment someone turns that feature on and not before. The
background worker checks whether it has been granted before attaching any navigation listener, and a refusal
switches the setting back rather than leaving a feature that silently does nothing.

That change is worth more than it looks. The difference between an install prompt that mentions browsing
history and one that doesn't is, for a privacy-first tool, most of the argument.

**Packaging** is now `npm run ext:package`, which produces both archives the stores want: the extension
itself, and — for Firefox, which requires it whenever submitted code has been bundled — a source archive
that reproduces the build. Missing that second one is the commonest reason a Firefox submission stalls.

**PRIVACY.md** states plainly what is handled and where it goes, since both stores require a policy and the
honest answers here happen to be the reassuring ones. **PUBLISHING.md** is a walkthrough for someone who
hasn't submitted an extension before: account setup, both consoles, the listing copy ready to paste, a
permission justification written for each entry, what screenshots to take, and the update process.

No test count change — this phase is packaging, metadata and documentation, none of which is unit-testable.
It's also where I stop being able to help directly: submission needs your own developer accounts (Chrome
charges $5 once; Firefox is free), and the screenshots have to be taken from a running browser.

## Phase 150 — many rows at once, and your selection where you want it

**Capturing a page that is already a set of rows.** A journal's contents page, a search result, a
bibliography, a comparison table — these are rows already, and every clipper in existence flattens them into
one note, because a note is the only thing it can make. A **Rows** tab now appears when a page holds a set
like that, and captures all of them together in a single write.

This is the clearest thing a row-shaped tool can do that a note-shaped one cannot, and it's why the data
model was worth building on.

Two sources are trusted, both because they carry their own structure rather than requiring it to be guessed
from layout: real HTML tables, and JSON-LD lists (how most sites describe their own listings). Repeated
`<div>` patterns are deliberately **not** inferred — that guess is wrong often enough to produce confident
nonsense, and a wrong row is worse than a missing one because someone has to find it later. Layout tables are
filtered out by the same reasoning: too few rows, one column, or headers that were never filled in.

Nothing goes in unseen. The tab reports what it found and previews the first rows before writing, because
bulk import is exactly where a wrong guess costs most — twenty bad rows take far longer to remove than one.
The whole set is written in **one** file operation rather than one per row, so a failure partway can't leave
half a table behind, and duplicates are counted and reported rather than blocking the import one refusal at
a time.

**Your selection, in the column you meant.** Selecting text before opening the popup used to mean it became
the description, always. Now every field gains a *Use selection* button, so a highlighted abstract, price,
author or date goes where it belongs. The popup can't watch the page's selection live — opening the popup
ends it — so the selection is captured with the page snapshot and offered here, which is the honest version
of the same idea rather than a promise the browser won't keep.

1030 tests (was 997): header normalisation, including making duplicate headers distinct so one can't
overwrite another; telling data tables from layout ones; row alignment, padding, empty-row removal and the
cap on a runaway page; JSON-LD `ItemList` reading including `ListItem` unwrapping, object-valued authors, and
only offering columns something actually fills; preferring a structured list over a table; finding nothing on
an ordinary article; and batched writing — order, appending rather than replacing, creating the table exactly
once for the whole set, refusing an empty batch, and escaping pipes in every row rather than only the first.

## Phase 149 — the read path: your vault, from the browser

Step 4 turns the companion from something that only writes into something worth keeping open.

**Search your vault without leaving the page you're on.** A second tab in the popup searches the vault
itself — notes, table rows, annotations, attachments and Zotero — three ways: keyword (phrases, exclusions,
tags), by meaning, and by asking a question and getting the passages that answer it. Results open in
Obsidian; Zotero items and saved links open where they actually are.

This is where being one system pays. Comparable extensions can't search on their own — they require you to
install a separate search plugin and then borrow it. Here the index is already in the vault, so all three
modes come from one place, and they reach rows and attachments rather than just note titles. Nothing about
it needs an account, a service, or a model.

**Searching is its own permission, and it starts off.** Reading tells a caller what your views are *shaped*
like; searching can return the text inside your notes. Those are different sizes of grant and shouldn't
share a switch — so `allowSearch` is separate from `allowRead`, defaults to off for existing vaults as well
as new ones, and is described in settings in those terms rather than as another checkbox.

**The companion can also tell you what you already have.** With it turned on, the toolbar icon is marked
when the page you're looking at is already in your vault — the quiet half of a companion, useful precisely
when you *aren't* capturing. It's off by default and asked for explicitly, because turning it on means every
page you visit is checked. That check never leaves the computer, but it is still a thing to choose rather
than assume, and answers are briefly cached so a revisit doesn't re-ask.

Search-as-you-type is debounced, and a slower earlier query can't overwrite a newer one's results — the kind
of thing that looks like a glitch and is actually a race.

997 tests (was 987): the search permission as a genuinely separate grant, its default, and that it still
requires a valid token; and snippet extraction — cutting around the matched term rather than the start of a
document, marking where it cut, falling back to the opening when nothing matches, collapsing whitespace, and
ignoring one-character terms that would otherwise match everywhere and make the fragment meaningless.

## Phase 148 — the browser companion

Step 3: the extension itself, for Chrome and Firefox, ready to load unpacked.

**The form is built from your vault, not from a template you wrote.** This is the whole difference between
this and a clipper. The companion asks `/schema` what a view looks like — its columns, their types, and the
values each choice column already uses — and renders the right form from that. A choice column becomes a
dropdown of the terms you already use. A date column gets a date field. A view created five minutes ago
works immediately, with nothing configured: no template, no JSON, no mapping file. Every other tool in this
space needs one, because none of them can see your schema.

**It reads the live page, which is the reason to be in the browser at all.** A URL handed back to the plugin
could only be re-fetched, and a re-fetch gets the markup a server sends a stranger. The companion reads what
you are actually looking at: content rendered by script, sections you expanded, pages you are logged into,
and whatever you selected — a selection becomes the description, since it's the most deliberate thing on the
page. It reads OpenGraph, Dublin Core and academic citation tags, Schema.org JSON-LD (including `@graph`
containers and authors published as objects or lists), and falls back to a representative opening paragraph
when a page describes itself no other way.

**Captures aren't lost because Obsidian was closed.** If the vault can't be reached the capture is held and
retried in the background with a widening gap between attempts, and anything waiting is listed in the
extension's settings. A capture tool that silently drops things in exactly the moment you needed it is worse
than none, because the failure is invisible until you go looking for something that was never saved. Held
captures are deduplicated, capped, and given up on after a week rather than surfacing as a surprise.

**It also tells you what you already have.** Before you save, it checks `/lookup` and says if the page is
already in that view and what it matched on — but never blocks the capture, and a failed check never gets in
the way.

Pairing is a six-digit code from Obsidian's settings, typed once. No account, no service, nothing leaving
the machine.

**`shared/` now holds the wire contract and the pure logic both halves use**, so a change to a shape is a
compile error in whichever side hasn't kept up — the reason both live in one repository. The extension has
its own tsconfig and build (`npm run ext:build`) and releases separately, so a store review can never hold
up a plugin release.

987 tests (was 957): meta-tag lifting including academic citation tags and case-insensitive keys; JSON-LD
reading through `@graph`, with authors as strings, objects or lists, and surviving malformed blocks;
precedence — a selection over a description, structured data over a generic tag, and never recording a key
twice; DOI detection with trailing punctuation trimmed; and the queue's whole life — ordering, retry limits,
backoff bounds, deduplication, the cap on a long offline stretch, and what it gives up on.

Deliberately not yet built: multi-row capture from tables, highlight-to-field mapping, an already-saved
badge, and vault search from the browser. The bridge already has the shape for all four.

## Phase 147 — the browser bridge

Step 2 of capture: a small local server the browser companion will talk to. Off until you turn it on.

**Nothing is assumed and nothing is fixed.** Installing this doesn't open anything — the bridge is disabled
by default, and a vault that predates it reads the missing setting as "no", because enabling a server as a
side effect of a plugin update would be indefensible. Once on, every part of it is a setting rather than a
constant: the port, reading and writing as *separate* permissions, which views are visible at all, the
largest request accepted, and whether activity is recorded. A shared laptop and a personal desktop want
different answers, so the plugin doesn't presume one.

**It listens on this computer only.** Bound to 127.0.0.1, never the network. Browsers also treat
`http://127.0.0.1` as a secure context, which sidesteps the self-signed-certificate friction that comparable
plugins hit. It's desktop-only: on mobile there's no server at all, and "your vault is reachable on a port"
is a very different promise on a phone.

**Pairing is a code you can see.** Settings generates a six-digit code, valid for five minutes and usable
once; the extension exchanges it for a long token it keeps. Tokens are compared in constant time, so the
comparison can't be used to recover one character by character. A paired client can be revoked at any point,
and the activity list shows what the bridge has actually been asked to do.

**Four endpoints, registered rather than hard-wired.** `/pair` trades a code for a token. `/schema` describes
each view's columns, types and existing vocabulary — the endpoint that lets an extension build a correct,
validating form for a view it has never seen, with no template written by hand. `/lookup` answers "is this
already saved?" on identity alone. `/capture` commits, reporting a duplicate alongside a successful write
rather than refusing. Routes declare the permission they need and the router enforces it, so a future
endpoint can't ship without an access check — which is what makes adding search or annotations later a
registration rather than a rewrite.

Two things are deliberate and worth stating. A hidden view answers exactly as a non-existent one does, so the
bridge can't be used to discover what you chose not to expose. And an internal error never reaches the
caller — the message can carry vault paths, so it goes to the local activity record and the browser learns
only that something failed. Both are pinned by tests.

957 tests (was 901): token and code generation; constant-time comparison including the equal-prefix case;
pairing success, wrong codes, expiry and single use; the full access ladder — disabled, origin, read and
write separately, unpaired, wrong token; view exposure; CORS echoing a permitted origin rather than a
wildcard; route matching across casing, trailing slashes and query strings; denial before disclosure of
unknown paths; handler errors becoming a 500 that leaks nothing while still being reported locally; bearer
parsing; body parsing; and each endpoint's behaviour including the hidden-view indistinguishability.

**Lint baseline moves from 27 to 32 warnings, all accounted for.** Four more `no-deprecated` (`setCta`,
`setWarning`, and two `display` calls) — the same three Obsidian APIs as the existing twenty-six, whose
replacements need 1.13.0, above our declared `minAppVersion` of 1.10.0, so continuing to use them is the
honest choice rather than a lapse. One `no-nodejs-modules` on the guarded `require("http")`: that rule can't
be disabled, and what it prescribes — a require behind `Platform.isDesktop` — is exactly what's there.

## Phase 146 — capture: the pipeline that turns outside content into rows and notes

The first step towards capturing from outside Obsidian, built plugin-side and useful on its own — no network,
no extension, nothing to install.

**A view already knows the shape of its data, so capture doesn't have to be told.** Every other tool in this
space asks you to declare a schema by hand: Web Clipper wants a template, Modal Forms wants JSON, QuickAdd
wants a format string. They ask because they can't know. A view here already declares its columns, their
types, their roles and the vocabulary each column already holds — so captured fields are matched to columns
automatically. An exact column name wins first; failing that, fields are matched by meaning, so a title
arriving as `og:title`, `schema:name` or `dc.title` all reach the Title column. Anything that matches no
column is handed back rather than dropped, and a value going into a choice column is snapped onto the
spelling that column already uses, so "in progress" doesn't become a second version of "In progress".

**Values are normalised on the way in, and never guessed at.** Dates arrive in every national order: ISO,
`2026年7月18日`, "18 July 2026", `18/07/2026`. All of those are read. But `03/07/2026` is the third of July in
most of the world and the seventh of March in the United States, and nothing in the string says which — so it
is left exactly as it came and surfaces for a person to settle. A wrong date that looks right is the worst
kind of error. Numbers follow the same principle: `1,234.56` and `1.234,56` both resolve, because whichever
separator comes last is the decimal point, and anything that isn't a number is passed through untouched
rather than blanked. Text is composed to a canonical Unicode form, which is what makes duplicate detection
and choice-matching work at all on non-Latin content.

**Captures can land as a row or as a note with properties** — chosen per view, in the view's own settings,
along with which note and heading receive rows. The row path addresses the table directly and will write the
note, heading and header row on the first capture. That also fixes something that could bite you today: the
existing "Add row" locates its table through an *existing* row, which is why a view with no rows yet reports
"This view has no rows yet" and can't be added to. Capture has no such limitation.

**Duplicates are reported, not blocked.** The check runs only on identity fields — url, DOI, ISBN — and never
on a title, because two papers can share one and refusing a legitimate item is worse than allowing a
duplicate. You're told what matched, and you decide.

A new command, **"Capture clipboard into a view"**, drives the whole path: it reads a URL, `Key: value` lines,
or a loose snippet, asks which view, maps, checks for duplicates and writes. It exists to be useful now, and
to keep the pipeline honest — the browser companion will drive exactly this path, so any rough edge shows up
here first.

901 tests (was 842): Unicode composition and invisible characters (including joiners that must survive
because they carry meaning in several scripts); international date and number reading, and the refusal to
guess ambiguous ones; alias, role and type matching, precedence, and vocabulary snapping; table location by
heading without reaching into a later section; table creation; pipe escaping and CRLF preservation; duplicate
detection on identity fields only; YAML-safe note building; file-name sanitising that leaves non-Latin titles
intact; and the capture target surviving a profile round trip.

## Phase 145 — scaling: pivot in one pass, and the cap stops lying to aggregate views

The last scaling step went looking for "turn pagination on by default" and found two better things instead.

**Pivot no longer degrades quadratically.** `buildPivot` filtered the entire dataset once per cell — for every
(row key × column key) pair — so its cost was rowKeys × columnKeys × rows. Pivoting a high-cardinality field
(where nearly every row is its own key) was close to quadratic and could block the UI for seconds; the only
reason this hadn't bitten was the 1000-row cap hiding it. It now visits each row once, dropping it into its
cell, row and column buckets, then aggregates per bucket: O(rows + cells). Output is unchanged — keys still
appear in first-encounter order, totals are still computed over whole groups rather than summed from cells
(so averages stay correct), and sparse cells still read as the aggregate's empty value.

**Charts and pivots are no longer capped.** The row cap exists to stop a view building a DOM node per row.
Chart and pivot don't do that — a chart draws grouped series and a pivot draws distinct keys, so their DOM is
bounded however many rows arrive. Capping them freed nothing and quietly falsified them: an average or total
over "the first 1000 rows" is not the number the reader is being shown, and nothing on screen said so. Views
now declare whether they aggregate, and aggregate views see every filtered row.

**The truncation banner stopped giving impossible advice.** It suggested setting a page size even in views
that ignore paging entirely (gallery, board, calendar) or when rows are grouped — advice that could not work.
It now only suggests a page size where one would take effect, and otherwise suggests filtering or grouping by
a field with fewer values.

**What was deliberately not done:** turning pagination on by default. That was the original plan for this
step, written before the card layouts rendered progressively. With the table virtualized, cards and gallery
progressive, board columns limited, and aggregate views bounded by construction, every layout is already
bounded — so defaulting pagination on would change how existing views behave in exchange for safety that is
already there. It stays available as a per-view setting.

842 tests (was 836): pivot equivalence across empty input, first-encounter key order, sparse cells,
whole-group averages, blank keys grouping together, and a high-cardinality case that the old shape would have
choked on.

## Phase 144 — scaling: the card layouts paint immediately

Following the cap in the last phase, the card-shaped layouts no longer build their whole grid before the
first paint.

**Why not the table's approach.** The table virtualizes with measured heights and a scroll window, which
works because one row is one item of roughly known height. Cards don't fit that model: they sit in an
`auto-fill` grid, so the column count changes with the pane width, and their heights genuinely vary — a card
only draws the fields that aren't empty. Measured windowing there means tracking grid rows across resizes and
continuously correcting estimates, and its failure mode is visible scroll jumping.

**What we do instead.** The cost that actually hurts is the first paint — building hundreds of cards, each
running cell renderers and markdown, blocks the main thread before anything appears. So Cards and Gallery now
draw a first chunk immediately and grow on demand: a sentinel sits below the grid, and as it comes near the
viewport the next chunk is drawn. Nothing is torn down or re-measured, so scrolling stays smooth and there is
no jumping. This applies to ungrouped grids, to grouped sections, and to a group expanded with "show more"
(which could otherwise draw hundreds of cards in one go).

**Gallery images are lazy now too.** Obsidian builds the `<img>` elements, so the loading hints are applied
once it has: images are marked `loading="lazy"` and `decoding="async"`, and the browser then skips fetching
and decoding everything scrolled off screen — the difference between an image grid appearing at once and
stalling on the whole set.

Board columns and grouped card sections were already bounded (`limitRows` plus a "show more" button), so they
needed no change beyond the above.

**The trade-off, stated plainly:** the DOM grows as you scroll rather than staying fixed. It's bounded by the
row cap from the previous phase, so the worst case is the cap rather than the whole dataset, and reaching it
means scrolling through everything. If measurement ever shows the grown DOM is the bottleneck, the new
`views/progressive.ts` is the seam where true windowing would go.

836 tests (was 830): chunk scheduling — only chunking when a list exceeds one chunk, treating a non-positive
size as "no chunking", advancing without overshooting the total, always progressing (no stall), tolerating a
negative count, and a guard on the first-chunk size.

## Phase 143 — scaling: close the one unbounded rendering path

A scaling audit found a single combination that could try to build a DOM node per row on a large vault:
**grouped views were bounded by nothing.** Grouping deliberately bypasses pagination (a page of groups is a
confusing unit), and the row safety cap was *also* skipped whenever results were grouped — so a grouped view
had no page, no cap, and, outside the virtualized table, no windowing either. On a few thousand rows a grouped
Gallery or Board would attempt to render all of them.

The cap now covers grouped results. Groups are kept whole while the row budget lasts, the group that straddles
the limit is trimmed, and the rest are dropped — in sorted order, so what you see is the start of the result
rather than a scattered sample, and no empty groups are produced. The banner's advice is now
context-appropriate too: grouped views ignore page size, so it suggests filtering or grouping by a
lower-cardinality field instead of telling you to set a page size that wouldn't help. The reported total stays
the honest unfiltered-by-cap count.

This is a backstop, not a substitute for windowing: the table already virtualizes (above 100 rows, with
per-row measured heights), and virtualizing the card-shaped layouts — Cards, Gallery, Board columns — is the
next scaling step.

830 tests (was 824): counting rows across groups; keeping whole groups within budget and trimming the
straddling one; preserving group order; passing everything through when it already fits; treating a
non-positive cap as no cap; and never emitting an empty group.

## Phase 142 — UI polish: command names, settings coherence, keyboard focus

Three UI passes from the design audit (all display-only or CSS — no ids changed, so hotkey bindings are safe).

**Command-palette naming.** Normalised the command list for consistency and discoverability: one verb per job
(Show → Open), dropped stray articles, fixed punctuation (`BibTeX / CSV` → `BibTeX, CSV`), clearer wording
(`Shard` → `Split`), and — the nicest win — the three view-creation commands now share a phrasing so they
cluster: “Create view from current note's table”, “Create view from starter template”, “Create view from
pasted rows”. 12 commands renamed; every command id is unchanged.

**Settings-surface coherence.** The view-settings editor's compact custom fields now pin to the *same* input
anatomy as Obsidian's native Settings — border, background, radius, and hover/focus colours from the same
tokens — so the editor modal and the plugin-settings tab read as one design language across themes, differing
only in the editor's intentional density. The editor's section titles were aligned to the native
setting-heading type as well.

**Keyboard focus (a11y).** Two controls that were mouse-only divs — the table's summary cell and the paper
attachment cards — are now proper buttons: focusable (Tab), operable (Enter/Space), and labelled for screen
readers. Focus-visible rings were extended to them and to the reference (DOI) chip and the editor's inputs,
matching the outline treatment used elsewhere, so keyboard users get clear focus indication throughout.

Still 824 tests (UI/CSS/interaction changes).

## Phase 141 — getting-started guide: broader opening, Zotero search, attachments, and the gaps

An audit of the guide against the full feature set, plus the specific gaps you flagged:

- **Step 1 no longer under-sells the plugin as just table rows.** It now opens on the real scope — the rows in
  your tables, note properties, tasks, inline fields, *and* Excel sheets — turned into live, editable
  dashboards, while keeping the one-idea-per-screen shape (the detail still unfolds in the next two steps).
- **Search now says it reaches Zotero.** The search step lists your **Zotero library and its annotations** as
  things it searches (opt-in, alongside notes/rows/attachments/links/images), with a dedicated “Zotero” entry,
  and mentions the **Related notes** panel that surfaces connected notes from the same index.
- **Promoted paper notes' attachment shelf is described.** The research step now covers the `kvs-paper`
  attachment shelf on each paper note — drop in PDFs, EPUBs, images, Word/Excel/PowerPoint files, or web links,
  all in one place.
- **Filtering/sorting/grouping and computed columns are no longer invisible.** The layouts step now states you
  can filter, sort and group in any layout, and the closing step lists computed (formula) columns — two core
  capabilities the guide never actually named.

Still 824 tests (an onboarding/UI change).

## Phase 140 — three more corrections: summary height, the "⋯" glyph, and typed column widths

- **The summary footer is tighter still.** Its floor was the button's min-height and the text line-height; both
  are reduced (no vertical padding, min-height 16px, line-height 1.1), so the totals line now takes about half
  the vertical space of a normal row instead of sitting tall.
- **Row-tools uses a horizontal "⋯", not a vertical "⋮".** The actions handle now uses the horizontal ellipsis,
  which is shorter vertically — so, stacked beneath the checkbox, it adds almost no height.
- **Typing a column width in view settings works again.** Drag-resize (and "freeze widths") store per-column
  overrides in `columnWidths`, which take precedence in `widthFor` — so a width typed in settings was silently
  ignored whenever an override existed for that column (and the freeze path can create one for every column,
  which is why it looked broken across the board). The width field now clears that column's override as it
  sets the value, in a single patch, so the typed width takes effect immediately.

824 tests (was 822): the width precedence (an override wins; clearing it restores the typed/config width). The
summary height and glyph are CSS/DOM.

## Phase 139 — three corrections: DOI double-click, the summary line, and row-tools alignment

- **Double-click a DOI cell to edit — without copying twice.** Double-click enters edit mode, but each of its
  two clicks was also firing the cell's copy/open affordance ("copy twice, then edit"). The chip, the full-mode
  link, and the copy button now disambiguate: a single click acts after a short beat, and a following second
  click cancels it so the double-click just edits. Single-click copy/open still works (with an imperceptible
  delay); double-click is clean.
- **The summary footer now reads as a quiet totals line, not a heavy bar.** It was a solid secondary-coloured
  block with a bold value. It now uses the pane's own background (still opaque, since the sticky footer must
  occlude rows scrolling under it), set off by a single hairline like the header, with a lighter value and a
  slightly shorter row — so it blends with the dashboard.
- **Row-tools no longer looks off-centre.** The checkbox and the "⋯" button were in a horizontal row with the
  (hover-hidden) "⋯" reserving space to the right, which pushed the checkbox left of centre. They now stack
  vertically — checkbox centred on top, "⋯" centred directly beneath — in a narrower lane, with the gutter cell
  un-padded so stacking doesn't inflate row height (only the transient selection mode is affected). The corner
  promoted-flag is unchanged.

Still 822 tests (interaction/CSS changes; the DOI disambiguation is DOM behaviour verified by hand, and the
stylesheet-selectors gate stays green).

## Phase 138 — DOI columns: a compact chip instead of an unreadable link

A DOI is a link, not reading material — a column of `10.1145/3292500.3330701` is unreadable and eats the
width, and on a dashboard you never actually read the digits. So reference columns (DOI, arXiv, PubMed) now
render a **compact chip** by default: a small pill (“DOI ↗”) that opens the paper, copies on hover (the copy
icon is revealed on cell hover), and keeps the full identifier one tooltip away. The column shrinks to fit the
chip, and — a bonus — which rows *have* a DOI is now scannable at a glance instead of being a wall of
identical-looking digits.

Two other per-view modes on the column's “Show as” setting (same mechanism as the number bar/ring modes):
- **Full identifier** — the whole string as before, for anyone who wants to eyeball it (now with a copy button).
- **Publisher** (DOI only) — shows the registrant from an offline prefix map (`Nature ↗`, `ACM ↗`, `ACL ↗`,
  …), falling back to the bare prefix for the long tail, so the column reads meaningfully. No network, no
  favicons — a curated map covering the common CS/physics/biology/medicine/chemistry/generalist registrants.

Compact is the default (unset `display`), so existing DOI columns become chips automatically; set “Full” to
keep the old look. arXiv/PubMed get compact + full (publisher is DOI-specific).

822 tests (was 819): the prefix extractor (resolver/`doi:` forms) and the publisher lookup (known prefixes,
plus null for the long tail and non-DOIs so callers fall back cleanly).

## Phase 137 — a professional redesign of the row-tools column

The first column (row tools) was functional but crowded and inconsistent: up to three mismatched controls
side by side — a native checkbox, a faint source-link icon, and an accent promoted-note icon — which widened
the column and left no clean way to add commands.

Redesigned around one idea: a single quiet handle plus at-a-glance status.
- **One actions button ("⋯")** replaces the separate source and note icons. It opens the *same* menu as
  right-clicking a row — now the single source of truth for row commands (open source note, open dedicated
  note, view details, cite, fill from DOI/Zotero, promote, duplicate, delete) — so new commands never need a
  new icon, just a new menu item. It's revealed on row hover and kept in the layout so nothing shifts.
- **A promoted flag** — a small accent diamond pinned to the cell corner — shows which rows have a dedicated
  note at a glance, costing no width (the open action lives in the menu). A promoted row also shows its handle
  faintly at rest, hinting it's actionable.
- The column is now **noticeably more compact** (roughly half the old width in the common case) because source
  and promote collapse into the one handle; the menu builder was extracted so right-click and the button can't
  diverge.

Still 819 tests (a UI/CSS change; the shared row-menu builder is exercised by the existing table paths, and
the stylesheet-selectors gate confirms the retired `.kvs-source-link` / `.kvs-promoted-link` classes are gone
and the new ones are real).

## Phase 136 — getting-started guide: import, export, rich copy, and sharing a view

The revised guide still under-sold one of the plugin's strongest areas — getting data in and out — reducing it
to a single line. It now has a dedicated **"Take your data anywhere"** step covering:
- **Import**: tables from CSV, Markdown or Excel (and references from BibTeX), each becoming a note and a view.
- **Export**: a view to Word, Excel, PDF, CSV or Markdown — with images embedded and links kept as real
  hyperlinks, and a live preview while you choose options.
- **Rich copy** ("Copy as ▾"): Markdown/CSV/JSON/bullets or re-importable KVS rows, emitting both plain text
  and rich HTML so a paste into Word, Docs or Excel lands as a real table rather than text.
- **Embed a view**: "Copy as live view" gives a block you paste into any note to render the view live.
- **Archive a view**: the self-contained `.kvspack` — settings, data and every image/attachment bundled in,
  optionally encrypted, openable read-only anywhere without importing, and restorable even if the source notes
  are gone — alongside the lighter, settings-only `.kvsview`.
The closing step's one-line "copy & export" mention was removed now that it has a proper home.

Still 819 tests (a UI/onboarding change).

## Phase 135 — getting-started guide, brought up to date

The guided first run had fallen behind the plugin: it still said "six" layouts (there are seven — Chart
joined), and its search and research steps predated the quick launcher, image OCR, searchable links, the live
Zotero library, DOI/Zotero metadata fill, literature notes, Office annotation, and the summary row. So a new
user was pointed at a fraction of what's here.

It's revised to cover everything, without piling it on: same stepped, one-idea-per-screen, fully-skippable
shape, but the essentials still come first and the optional surface is pushed to a final "a few more things
when you want them" screen rather than the front. Specifically: the layouts step now lists all seven (and
notes that Board/Calendar/Pivot also work inside Obsidian's Bases); the search step leads with the new quick
launcher (with a button to open it) alongside the full search view, and mentions title-first ranking, image
OCR, and searchable links; the research step (shown when the Academic kit is on) now covers the live Zotero
library, metadata fill with exact Better BibTeX keys, DOI-matched literature notes, and PDF/Office annotation;
and the closing step surfaces the summary row and copy/export. The guide gained a "jump to a note" action wired
to the launcher.

Still 819 tests (a UI/onboarding change).

## Phase 134 — search: title-first ranking, a quick launcher, image OCR, and searchable links

Four search improvements, studied from the focused launcher plugin Searchosaurus and adapted to KVS's index.

### The note you meant comes first

Search now applies **deterministic title-first ranking**: an exact title (or alias) match is pinned to the
top, a title prefix or word-prefix (`mi ho` → “Mira Holt”) comes next, and everything else follows by
relevance. Field boosts alone couldn't guarantee that a person's own note beat the thirty notes that merely
mention them; this does. It's a final ordering pass over results the engine already found — nothing new
appears, the right thing just leads.

### A Spotlight-style quick launcher

A new command, **“Quick search (jump to note)”** (bind it to a hotkey), opens a launcher modal over the same
index: type a few letters, the note you meant is at the top, Enter jumps to it (Cmd/Ctrl+Enter opens a new
tab). It collapses a note's many section documents into one row so it reads like a note picker, and reuses the
already-built index — no second index, no extra memory.

### Text inside images is searchable (offline OCR)

Turn on **“Search text inside images (OCR)”** in settings and KVS recognises the text in your screenshots and
photos so they become findable. It's fully offline: the recognition engine and language models download once
(from a release asset, checksum-verified), then everything runs locally. Recognition happens in the
background in idle time, one image at a time, and every result is cached in a file that syncs to your other
devices — so no image is ever recognised twice, anywhere. Desktop-only (mobile reads the synced results for
free), off by default, and PDFs' text was already indexed. *Note: this ships behind an asset bundle you host
on your own release — see the OCR section below and `scripts/build-ocr-assets.mjs`.*

### Saved links are their own search results

Every external URL in a note is now indexed as its own document, searchable by its link text and its URL — so
“that article I linked about tokenizers” is findable even when the note it lives in is about something else.
Choosing a link result opens the URL.

819 tests (was 798): title-first tiers (exact/alias/prefix/word-prefix, diacritic folding, stable fallback);
link extraction (markdown + bare URLs, de-duplication) and link-doc emission; and the OCR queue (priority,
replace, drop, error isolation) and its signature-keyed synced cache.

## Phase 133 — summary row: smaller, and switchable per view

Two follow-ups now that the summary picker works.

- **It's more compact.** The footer was taller than it needed to be (a 28px minimum plus generous padding);
  it's now trimmed to sit closer to header density, so it takes noticeably less vertical space while staying
  readable. The empty per-column pickers were already hidden until you hover the row, so only your chosen
  summaries show.

- **It's a per-view toggle.** Each view's editor now has a **Summary row** switch (next to "Freeze header
  row"). Leave it on (the default) to keep the aggregation footer; turn it off to reclaim the space entirely
  on views that don't need it.

While wiring the toggle, a latent persistence bug surfaced and is fixed: the profile builder wasn't carrying
`showSummaryRow` **or** `dedicatedNoteKey` through a save/reload, so the summary toggle would have reset — and
the per-view note-match field (from v0.130) had silently not been persisting either. Both now round-trip
correctly, and `false` is preserved for the toggle rather than being folded back to the shown default.

798 tests (was 796): both fields surviving a create/deserialize round-trip, and `showSummaryRow: false` being
kept rather than dropped.

## Phase 132 — fix (the real cause): the summary picker now takes effect

The summary row was still stuck on "none" no matter what you picked, and my previous alignment fix — a real
but *separate* bug — didn't touch the cause. The actual problem was upstream of the footer: when a view's
columns are resolved for rendering, the resolver was **dropping the column's `summary` setting** (and its
number display mode). The footer reads the summary off the *resolved* column, so it always saw "none";
choosing Sum/Count/Average updated the saved view but the footer never read it back, so it snapped straight to
"none" again — exactly "stuck, can't change it."

The column resolver now carries `summary` (and `display`/`displayMax`) through onto the resolved column, so
the footer sees your choice and computes it, and the picker works. Number columns set to a bar or ring display
were being ignored for the same reason and now render correctly too.

796 tests (was 794): the resolver preserving a column's summary and display settings, and leaving them unset
when not configured.

## Phase 131 — fix: the summary row, and the edit-time slowdown

### The summary row lines up and works again

The table's summary footer was emitting the wrong number of leading cells — one or two keyed only on whether
selection was on — while the header and every body row emit exactly one combined gutter cell (or none). So
every summary landed one column to the left of its data, the rightmost column had no cell at all, and the
whole row looked broken and unclickable. (v0.130.0's change to when the promote indicator shows shifted which
views hit the mismatch, which is why it surfaced now.) The footer now uses the *same* single predicate as the
header and body — extracted into one shared function so the three can't drift apart again — so every summary
sits under its own column and the picker works.

### Editing is fast again on academic dashboards

v0.129.0 added a "which notes already exist for these rows" index (a scan of every note's frontmatter, used
for the DOI note-indicator). v0.130.0 cached it across renders but rebuilt it whenever any note's metadata
changed — and editing a cell *is* a metadata change, so every edit on an academic dashboard rescanned the
whole vault before repainting. That's the slowdown.

The index is now **maintained incrementally**: built once, then updated one file at a time from the metadata
events (a note changed, was deleted, or renamed). Editing a cell now costs a single map update instead of a
full-vault scan, so the dashboard stays responsive no matter how large the vault. Non-academic dashboards
never build the index at all, and searching, sorting, and scrolling touch no files, so none of them pay any
scan cost. (The data query was already cached per-file, and duplicate renders are already de-duplicated by the
render-sequence guard, so this was the remaining hot-path cost.)

794 tests (was 790): the summary footer's leading-cell count matching the header/body across selection,
source-column, and promote-indicator combinations; and the incremental index applying edits, additions, and
deletions to a single file without rebuilding, plus staying safe when an event arrives before the first build.

## Phase 130 — a speed pass on the Academic Dashboard and Zotero, and a per-view note-match dropdown

### The dashboard is snappy again

Two things had crept in that made large academic dashboards feel baggy, and both are fixed by doing work
once instead of repeatedly.

- **The dedicated-note index no longer rescans the vault on every render.** v0.129.0 built the "which notes
  exist for these rows" index (a whole-vault frontmatter scan) once per render — which meant every search
  keystroke, sort, and filter rescanned every note. It's now a process-wide cache that rebuilds only when a
  note's metadata actually changes (the plugin listens for that). Searching, sorting, scrolling, and paging
  never touch the vault scan now, so they're instant regardless of vault size. Row lookups against the index
  are O(1).

- **All Zotero library access shares one cached fetch.** Previously the library view, "Fill from Zotero",
  "Promote", and search each read the whole library independently — opening the library right after a fill
  re-read everything. They now share the same 60-second cache: the first read pays the cost, and the rest
  (across all four features) are instant until it expires or you hit refresh. The library view also skips its
  separate reachability round-trip whenever the library isn't empty, shaving a request off every open.

Together with the parallel pagination and the earlier cache, a Zotero-heavy workflow — open library, send to
dashboard, fill, promote, search — now fetches the library about once rather than once per action.

### "Match dedicated notes by" is now a per-view dropdown

Matching promoted notes by a frontmatter field (added in v0.129.0) is now a proper selectable option in each
view's editor, not a text box. It's a dropdown of the frontmatter properties actually used in your vault
(plus common identifiers like `doi`, `isbn`, `url`, `zotero-key`), so:

- **Academic views** default to matching by **DOI** — unchanged, but now visibly selectable.
- **Any other view** can pick whichever frontmatter property identifies its notes (an `id`, a `slug`, a
  project key…), so the "does this row have a dedicated note?" indicator and duplicate-proof promote work for
  non-academic dashboards too.

790 tests (was 788): the frontmatter index cache serving repeated reads without rescanning, holding until
invalidated, and rebuilding on invalidation or a key change.

## Phase 129 — cite keys that match Better BibTeX, and dedicated notes linked by DOI

### Our cite keys now match Better BibTex exactly

The previous version generated a fallback cite key (author + year + title word) when Zotero didn't hand one
over. That was the bug: BBT's key formula is user-configured, so any key *we* invent will eventually disagree
with BBT's — and once we'd written our guess into a row, it stuck. The scenario that exposed it: add a paper
to a dashboard before it's in Zotero (we'd invent a key), later add it to Zotero (BBT assigns a different
one), and now the two disagree forever.

The fix is to only ever use BBT's own key, never a guess:

- **We no longer fabricate cite keys.** The generator is gone. A cite key comes only from Better BibTeX.
- **We fetch the exact key from BBT's JSON-RPC endpoint.** BBT's key usually isn't in the standard Zotero
  API unless it's been *pinned*, so reading the API alone missed it. When filling from Zotero we now ask BBT
  directly (its `item.citationkey` method) for the paper's exact key — byte-for-byte what BBT would emit.
  If BBT isn't reachable we fall back to a pinned key if the item carries one, and otherwise leave the cell
  empty. Never a guess.
- **The cite key is authoritative from Zotero.** Because it's BBT's to own, "Fill from Zotero" now *updates*
  the cite key even if the cell already holds something — so the "added to Zotero later" case reconciles to
  BBT's real key the next time you fill. (Every other field stays fill-empty-only, so your edits are safe.)

The upshot: whether a paper is in Zotero now or added later, its cite key ends up identical to Better
BibTeX's, with no drift.

### Dedicated notes are matched by DOI, not by folder or filename

"Promote to dedicated note" used to recognise a paper's note only by a `[[wikilink]]` stored in the row, or
by a note that happened to be *named* after the cite key. Both broke when a note was renamed or moved, and
both created duplicates when the link was missing — promoting the same paper twice made two notes.

Now a note is matched by a stable identifier in its **frontmatter** — the DOI, for academic views:

- **Promote finds an existing note anywhere in the vault** by matching the row's DOI against notes'
  frontmatter `doi`, regardless of folder or filename, and opens it instead of making a duplicate. (New
  promoted notes already write `doi` into frontmatter, so they're found next time.)
- **The ↗ "has a note" indicator uses the same match**, so a row shows its note even when there's no
  wikilink — as long as a note somewhere carries the matching DOI.
- **The match field is configurable** per view ("Match dedicated notes by" in the view editor), defaulting
  to `doi` for academic views. Different forms of the same DOI (a `https://doi.org/` prefix, casing) are
  normalized so they still match.

788 tests (was 767): BBT endpoint derivation and the `item.citationkey` request/parse (including unreachable
and empty-key paths); cite-key resolution now returning empty rather than a fabricated key; and dedicated-note
identifier normalization, index building, and lookup (url/bare DOI equivalence, missing keys, duplicates).

## Phase 128 — after it worked: cite keys, speed, and one promote template

Four refinements once "Fill from Zotero" was working.

### Cite keys now always come through

Filling from Zotero wasn't producing a cite key, because we only read it from the "Citation Key:" line in
the item's `extra` field. Better BibTeX also exposes the key as a `citationKey` data field (newer versions),
so we now check that first, then `extra` — and if a paper has no cite key at all, we generate a reasonable
one (first author's surname + year + first title word). A cite key always fills now.

### Filling and promoting from Zotero are much faster

Both had to scan the library to find a paper by DOI (the search endpoint is unreliable, so we match against
the same item list the library view uses). Two changes make that fast:

- **Parallel pagination.** Listing the library fetched pages one at a time; a large library meant dozens of
  sequential round-trips. Pages after the first are now fetched in concurrent batches, so a big library
  loads several times faster.
- **A shared, 60-second library cache.** Fill, promote, and search now share one cached fetch instead of
  each re-reading the whole library. The first operation pays the cost; the rest are instant for a short
  window. The "Refresh Zotero" commands clear it, and the live library *view* still fetches directly (its
  whole point is being live). This is what took "promote a Zotero paper" from over a minute to quick.

### One template for promoted notes, wherever they come from

A promoted note looked completely different depending on whether the paper was in Zotero — the Zotero path
built a literature note, the non-Zotero path used the promoted-note template. Now there is a single template.
Promotion always renders it; when the paper is in Zotero, the extra fields (abstract, annotations, and the
`zotero-key` link) fill in and Zotero's richer metadata is preferred, and when it isn't, those sections are
simply empty. Same structure either way. The default template gained an Abstract section, an Annotations
section, and a `zotero-key` frontmatter field, and `renderPromotedNote` gained the matching placeholders.

767 tests (was 761): cite-key resolution (BBT field, extra, generated fallback, precedence); parallel
pagination completeness with no duplicates; and the unified template filling or emptying its Zotero fields
while keeping identical structure.

## Phase 127.4 — fix: match the DOI against the endpoint that actually works, and report why when it doesn't

The error message from 127.3 gave the decisive clue: "status 0" (a network-layer failure) at
`127.0.0.1:23119` — while the settings Test button, using the *same* transport and the *same* host, returned
"Connected." A refused connection can't be true for one and false for the other. The real difference was the
URL: Test and the library view hit `/items…` and `/items/top`, which work; "Fill from Zotero" used a
*different* request — `/items?q=<doi>&qmode=everything`, a full-text search — which fails or times out on some
libraries even when plain listing works.

Two changes:

1. **Fill and Zotero-aware promote now find the item by matching the DOI against `provider.listItems()`** —
   the exact `/items/top` call the Zotero library view uses successfully. If your library view can list your
   papers, fill can now find them, because it's the same request. The fragile full-text search endpoint is
   gone from this path.

2. **The transport now reports *why* a request failed** instead of collapsing everything to status 0:
   `ECONNREFUSED`, a timeout (with the duration), or the thrown error's message. Fill's reachability check
   uses the identical request the Test button succeeds with and, on failure, shows that reason — so a
   genuine connection problem is now named, not blank. The request timeout was also raised from 5s to 15s.

Root-cause honesty, three tries in: 127.1 fixed a dropped callback, 127.2 fixed the transport, 127.3 removed
a mismatched probe — each real, none the whole story, because I kept reasoning about the code instead of
starting from the one fact that mattered (the library view works, so *use what it uses*). Matching against
the proven `listItems` call is that fix. Trade-off: fill now fetches the library to match one DOI rather than
issuing a targeted search; correctness on every setup is worth more than the round-trip, and it can be
cached later.

761 tests. Four gates green.

## Phase 127.3 — fix (for real this time): "Fill from Zotero" reachability check hit the wrong endpoint

Two prior attempts didn't fix this, so this time I traced the whole path instead of pattern-matching. The
transport fix in 127.2 was correct but insufficient, and the actual culprit was a separate one:

**"Fill from Zotero" gated on a reachability probe (`provider.ping()`) that hit a *different* Zotero endpoint
than the working code.** `ping()` requests `/items?limit=1`, but the library view — which works and shows
the whole library — loads from `/items/top`. If a user's Zotero serves `/items/top` but the bare `/items`
probe fails (which can happen with certain library or configuration setups), the probe reports "unreachable"
even though every real operation would succeed. So the check that was *supposed* to give a friendly error was
itself the thing failing.

The fix removes the separate probe entirely. Fill and Zotero-aware promote now run the **real DOI query** and
decide from *its* actual HTTP status — via a new `zoteroDoiLookup` that returns `{ status, keys }`. That lets
the three outcomes be told apart honestly:

- status 0 → genuinely couldn't connect → "Couldn't connect to Zotero at <url>" (and it names the URL).
- non-200 → reached but the API errored → "Zotero returned status N" (names the status and URL).
- 200 with no match → reached fine, the DOI just isn't in the library → "Reached Zotero, but that DOI isn't
  in your library."

Because the reachability verdict now comes from the same request that does the work, there's no second
endpoint that can disagree with the first. And the error messages name the status and URL, so any remaining
environment issue is diagnosable instead of a blank "can't reach."

Root-cause honesty: the earlier "verified end to end" checks looked at whether callbacks were wired and
which transport was used, but never questioned that the *reachability probe and the real query hit different
URLs*. Removing the probe (rather than adding a third patch on top of it) is what actually resolves it.

760 tests (was 755): `zoteroDoiLookup` distinguishing unreachable (0), API error (non-200), match (200+keys),
and reachable-but-empty (200, no keys); plus a guard that the fill path no longer uses a separate ping probe.

## Phase 127.2 — fix: "Fill details from Zotero" said Zotero was unreachable when it wasn't

Once the option appeared (127.1), it still failed: clicking it reported "Can't reach Zotero," even though
Zotero was running, the settings Test button passed, and the Zotero library view showed everything. The tell
was that the *library view* worked while *fill* didn't — meaning they weren't talking to Zotero the same way.

They weren't. The library view (and all the other Zotero features) reach the local API through
`createZoteroFetcher`, which uses Node's `http` module. "Fill details from Zotero" — and the Zotero-aware
promote — instead used a fetcher built on Obsidian's `requestUrl`. `requestUrl` rejects the Zotero *local*
API's responses, so the request threw, the fetcher returned status 0, and `ping()` read that as
unreachable. Two code paths, two transports, and the one added later was the wrong one.

The fix routes the controller's Zotero access through the same `createZoteroFetcher` the rest of the plugin
uses, so there is now a single transport for the Zotero local API everywhere. An audit confirmed every other
Zotero call already used it; the academic controller was the only offender. (`requestUrl` stays for the
Crossref DOI lookup, which is a normal HTTPS endpoint it handles fine.) A guard test pins the controller's
Zotero fetcher to `createZoteroFetcher` and asserts no `requestUrl` wrapper is used for the local API, so
the transports can't diverge again.

Root-cause pattern worth noting: the same feature was built twice against Zotero (library view vs. academic
kit) and the second build quietly reinvented the transport instead of reusing the proven one. Sharing the
one fetcher is both the fix and the prevention.

755 tests (was 753).

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
