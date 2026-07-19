# Publishing the companion extension

A walkthrough for submitting to the Chrome Web Store and Firefox Add-ons, written for someone doing it for
the first time. Everything you need to paste is in here.

Chrome and Firefox are separate submissions with separate accounts. Do one, then the other. Neither depends
on the other, and neither affects the Obsidian plugin — the extension has its own version number and its own
release, so a slow review can never hold up a plugin update.

---

## Before you start

Build the packages:

```bash
npm run ext:package
```

That produces two files in `extension/packages`:

- **`kvs-companion-0.1.0.zip`** — the extension. This is what you upload to both stores.
- **`kvs-companion-0.1.0-source.zip`** — the source code. **Firefox only** (explained below).

You'll also want the privacy policy hosted somewhere with a public link. The simplest route: the file
`extension/PRIVACY.md` is already in your GitHub repository, so once you've pushed, its URL is

```
https://github.com/WK-101/knowledge-views-studio/blob/main/extension/PRIVACY.md
```

Both stores accept a link like that.

---

## Chrome Web Store

### 1. Create a developer account

Go to https://chrome.google.com/webstore/devconsole and sign in with a Google account. There is a **one-time
registration fee of $5**. This is the only cost.

### 2. Upload

Click **Add new item** and upload `kvs-companion-0.1.0.zip`. It will unpack and read your manifest.

### 3. Fill in the listing

**Name**

```
Knowledge Views Studio Companion
```

**Short description** (132 characters maximum)

```
Capture pages into your Obsidian vault as rows, using each view's own columns. Local only — no account, no server.
```

**Detailed description**

```
Capture what you're reading into your Obsidian vault — as rows in your own dashboards, not just another note.

This is the browser companion to the Knowledge Views Studio plugin for Obsidian. It talks only to Obsidian running on your own computer. There is no account, no server, and nothing leaves your machine.

BUILT FROM YOUR OWN VIEWS
Other clippers ask you to write a template. This one asks your vault what its views look like — the columns, their types, and the values each choice column already uses — and builds the right form from that. A view you made five minutes ago works immediately, with nothing configured.

MANY ROWS AT ONCE
When a page is already a list — a journal's contents, a search result, a bibliography, a comparison table — it can capture every row in one go. Ordinary clippers flatten those pages into a single note, because a note is all they can make.

READS THE PAGE YOU'RE ACTUALLY LOOKING AT
Content rendered by scripts, sections you expanded, pages you're signed into, and whatever you selected. Select text before you capture and you can send it to any column you like.

SEARCH YOUR VAULT WITHOUT LEAVING THE PAGE
Search notes, table rows, annotations, attachments and Zotero — by keyword, by meaning, or by asking a question and getting the passages that answer it.

KNOWS WHAT YOU ALREADY HAVE
Optionally marks the toolbar icon when a page is already in your vault. Off unless you turn it on.

NOTHING IS LOST IF OBSIDIAN IS CLOSED
Captures are held and sent when your vault is available again.

REQUIREMENTS
The Knowledge Views Studio plugin for Obsidian, with its browser bridge enabled. Desktop Obsidian only.

PRIVACY
No accounts, no analytics, no telemetry, no third parties. Reading, writing and searching are three separate permissions that you control from inside Obsidian, and the bridge is off until you switch it on.
```

**Category:** Workflow & Planning
**Language:** English

### 4. Privacy declarations

This section is where extensions usually get held up, so answer it carefully. It's all straightforward here
because the honest answers are the reassuring ones.

**Single purpose** — paste:

```
Capture web page content into the user's own Obsidian vault, and search that vault, via a local connection to Obsidian running on the same computer.
```

**Permission justifications** — one box each:

- `activeTab` — `Reads the page the user is currently viewing, only when they click the extension button, in order to capture it.`
- `scripting` — `Injects a single function into the current tab, on click, to read that page's title, metadata and tables.`
- `storage` — `Stores the user's settings, their pairing token, and any captures waiting to be delivered.`
- `alarms` — `Retries delivering a capture that could not be sent because Obsidian was closed.`
- `tabs` (optional) — `Only requested if the user enables the optional toolbar marker, which shows whether the current page is already saved in their vault.`
- Host permission `http://127.0.0.1/*` — `Connects to Obsidian running on the user's own computer. This address refers only to the local machine and cannot reach the internet.`

**Remote code:** answer **No**. Everything is bundled in the package.

**Data usage:** tick **nothing**. Then check the three declarations at the bottom — that you don't sell data,
don't use it for unrelated purposes, and don't use it for creditworthiness. All three are true.

**Privacy policy URL:** the GitHub link above.

### 5. Screenshots

You need at least one, at **1280×800** or **640×400**. Good ones to take:

1. The popup on an article, with the form filled from the page
2. The **Rows** tab on a page with a table, showing the preview
3. The search tab with results
4. Obsidian's Browser bridge settings, showing the permission switches

The fourth is worth including — it shows a reviewer, and a prospective user, that the vault stays in control.

### 6. Submit

**Publish status:** Public. Then **Submit for review**. First reviews typically take a few days.

---

## Firefox Add-ons (AMO)

### 1. Create an account

https://addons.mozilla.org/developers/ — sign in with a Firefox account. **Free.**

### 2. Upload

**Submit a New Add-on** → **On this site** → upload `kvs-companion-0.1.0.zip`.

An automatic validator runs immediately. Warnings are normal and don't block submission.

### 3. Upload the source code

AMO will ask whether your add-on's sources are minified or generated. **The answer is yes** — the code is
bundled with esbuild. So upload `kvs-companion-0.1.0-source.zip` when prompted, and paste these build
instructions:

```
Requirements: Node.js 18 or newer.

  npm ci
  node extension/build.mjs production

The built extension appears in extension/dist, matching the uploaded package.
Built with esbuild (see extension/build.mjs). No other tooling is required.
```

This step is mandatory. Skipping it is the most common reason a Firefox submission stalls.

### 4. Listing

Reuse the name, summary and description from the Chrome section. Categories: **Bookmarks** and
**Other**. Add the same privacy policy link.

### 5. Submit

Firefox reviews are often faster than Chrome's. You'll be emailed the result.

---

## After the first release

To publish an update:

1. Raise `version` in `extension/manifest.json` (e.g. `0.1.0` → `0.2.0`)
2. `npm run ext:package`
3. Upload the new zip as a new version in each console — and the new source zip for Firefox

Store versions have to increase, and each one can only be uploaded once, so bump before packaging.

---

## Things worth knowing in advance

**A rejection is usually about wording, not code.** The commonest cause is a permission justification a
reviewer didn't find convincing. The text above is written to pre-empt that, but if you're asked about
`scripting` or `127.0.0.1`, the honest explanation — it reads the page you asked it to, and talks only to
your own computer — is also the correct one.

**Chrome may ask about the local connection.** Extensions that talk to localhost do get extra attention.
Point to the privacy policy and to the fact that the vault holds separate read, write and search permissions
that the user controls.

**Firefox temporary add-ons vanish on restart.** That's only during development; a signed listing installs
permanently.

**Don't submit until you've used it yourself for a while.** Nothing in the automated checks catches a
workflow that's merely awkward, and the first review is the slowest one to redo.
