# Knowledge Views Companion (browser extension)

Captures pages into your Obsidian vault's Knowledge Views, using **each view's own columns**.

Unlike a general web clipper, this doesn't ask you to write a template. It asks your vault what its views
look like — the columns, their types, and the values each choice column already uses — and builds the right
form from that. A view you created five minutes ago gets a correct form with nothing configured.

Everything stays on your computer. There is no account and no server beyond Obsidian itself.

## Status

Early. Built to be loaded unpacked while the plugin side settles; not yet submitted to any store.
Targets **Chrome and Firefox on Windows** first.

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

Multi-row capture from tables and lists, highlight-to-field mapping, a badge showing pages already saved,
and searching the vault from the browser. The bridge already has the shape for them.
