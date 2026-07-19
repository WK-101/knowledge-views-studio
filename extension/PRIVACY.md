# Privacy policy — Knowledge Views Studio Companion

_Last updated: 19 July 2026_

## The short version

This extension does not collect anything, does not send anything to us, and has no server to send it to.
Everything it does happens between your browser and Obsidian running on the same computer.

There is no account, no analytics, no telemetry, no advertising, and no third party of any kind.

## What it handles, and where that goes

**The page you choose to capture.** When you click the toolbar button, the extension reads the page you're on
— its title, address, description tags, structured data, any tables on it, and any text you had selected. It
does this only for the tab you are looking at, and only when you click.

That information is sent to **Obsidian on your own computer**, over a connection to `127.0.0.1`, which is
your machine talking to itself. It does not travel over the internet and cannot be reached from your network.
Obsidian then writes it into your vault, which is a folder of files you own.

**Searches you type.** If you use the search tab, what you type is sent to Obsidian on the same local
connection, and the results come back the same way. Nothing is logged anywhere else.

**Which page you're on, if you turn on the toolbar mark.** This feature is **off unless you switch it on**,
and switching it on is when the browser asks your permission for it. With it on, the address of each page you
visit is checked against your vault — again, locally — so the icon can show whether you already have it.
Recent answers are remembered briefly to avoid asking repeatedly. Turn the feature off and the checking
stops.

**Things waiting to be saved.** If Obsidian isn't running, a capture is stored in the browser's own extension
storage until it can be delivered, then removed. You can discard anything waiting from the extension's
settings.

**Your pairing token.** Pairing with a vault produces a token, kept in the browser's extension storage. It
grants access only to that vault, on that computer, and you can revoke it from either side.

## What is never collected

- No browsing history is gathered, stored or transmitted anywhere.
- No personal information is requested.
- Nothing is sent to the extension's authors, or to anyone else.
- Page content is not read in the background — only for the tab you're on, when you ask.

## Permissions, and why each exists

| Permission | Why |
| --- | --- |
| `activeTab`, `scripting` | To read the page you are capturing, at the moment you click. |
| `storage` | To keep your settings, your pairing token, and anything waiting to be sent. |
| `alarms` | To retry a delayed capture later. |
| `http://127.0.0.1/*` | To reach Obsidian on your own computer. This address is your machine only. |
| `tabs` (optional) | Only if you turn on the toolbar mark, and only asked for at that moment. |

## Your control

Obsidian decides what the extension may do. Reading, writing and searching are three separate permissions
there, all of which you set, and the bridge is off entirely until you enable it. You can revoke pairing from
either side at any time. Removing the extension removes everything it stored.

## Contact

Questions or concerns: https://github.com/WK-101/knowledge-views-studio/issues
