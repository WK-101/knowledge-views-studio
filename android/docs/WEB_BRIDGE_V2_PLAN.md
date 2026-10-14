# Hexis Web Bridge v2 — reliability, speed, and a desktop-grade UI

Research-backed plan for three problems reported after v0.1 went functional: the live connection
"keeps reconnecting", the UI is "slow and crude", and the server "takes a long time to start". Research
covered current (2024–2026) best practice and real implementations (PlainApp — our closest analog, cloned
and read; Immich, Home Assistant, Nextcloud, Jellyfin, Syncthing; and the desktop-web of TickTick, Todoist,
Microsoft To Do, Google Calendar/Tasks, Notion, Obsidian, Google Keep, Morgen).

Status: **R1 + R2 shipped in web-bridge 0.2.0.** R3 (UI) is the blueprint below.

---

## R1 — Robust live connection  ✅ shipped

**Diagnosis.** v0.1 used a 25 s encrypted long-poll (`kind:"await"`). It held an **idle HTTP/2 stream**,
which Ktor-Netty's write-idle watchdog (~10 s default), HTTP/2 GOAWAY, and Android Wi-Fi power-save reset —
and the SPA flipped to "Reconnecting…" on the first throw, with a fixed 3 s retry, **no heartbeat, no
backoff, no online/offline handling**. HTTP/2 also shares one TCP connection across all streams, so packet
loss stalled the poll *and* every query together (the "slow/crude" feel).

**Decision (matches PlainApp / Immich / Home Assistant / Nextcloud / Jellyfin — all use WebSocket; only
Syncthing still long-polls): move live updates to a WebSocket.** A WS upgrade negotiates its own HTTP/1.1
connection, so its fate is isolated from `/api` traffic and there is no idle-stream reset. SSE was rejected:
`EventSource` can't set our per-device auth and forces base64 (our frames are AES-GCM binary), and it rides
the shared HTTP/2 socket.

**Shipped.**
- Server: `install(WebSockets)` (pings 20 s / timeout 15 s / maxFrame 1 MiB) + `webSocket("/live")`. Auth
  reuses the exact `/api` crypto — first frame is a sealed `hello` carrying the client's `{domain:version}`
  cursor; the server trial-decrypts against the live client set, replay-checks the nonce, sends an immediate
  catch-up if it already moved past the cursor, then pushes a sealed version snapshot on every `ChangeHub`
  tick. Origin/Host guarded like `/api`.
- Client: a single-flight connection manager — exponential backoff + full jitter (1 s → 10 s, reset on
  open), app-level ping/pong (25 s / 10 s pong-wait) to detect a silently-dead socket (onclose often doesn't
  fire on Wi-Fi drop — the Jellyfin "dead websocket" bug), `online`/`offline` + `visibilitychange`
  reconnect, and a **2 s debounce** on the "Reconnecting…" banner so a brief blip never flashes. Gap-free
  resume via the version cursor.

---

## R2 — Fast, reliable start + fast load  ✅ shipped

**Diagnosis.** First "Start server" tap paid a one-time **RSA-2048 keypair generation** (0.2 s on a
flagship, up to several seconds on mid-range phones — KeyDroid 2025, W3C WebRTC estimates) plus a PKCS#12
parse (~0.5 s), plus Netty cold class-loading. And the SPA's initial load made **five sequential** encrypted
round-trips.

**Decisions.**
- **Keep Ktor-on-Netty.** Ktor CIO has no server TLS (`UnsupportedOperationException` — confirmed);
  NanoHTTPD is unmaintained (last release 2016) with "experimental" TLS; Jetty/raw-socket buy nothing.
  PlainApp (same stack) reached the same conclusion.
- **EC P-256 would be the ideal cert** (≈1000× faster keygen, identical browser trust, no ALPN downside),
  but Ktor's `buildKeyStore` is **RSA-only** (its `KeyType` is cert-role CA/Server/Client, not key
  algorithm), and EC would require vendoring a DER encoder. So instead **move generation off the critical
  path**: pre-generate + parse the keystore on a background thread (prewarm on control-screen open) and cache
  it per-process — same user-visible result (the tap no longer waits on keygen). (EC via a vendored
  generator remains a future option if first-run time still bites on slow phones.)
- **Parallelize the SPA load** — five domains now fetch concurrently (`Promise.all`), ~5× faster first paint.

**Future R2 polish (not yet done):** Netty class-load prewarm at launch (measure first — PlainApp found
racing the prewarm against an immediate start *doubled* engine-create), fail-fast `shutdownGracefully` and
`SO_REUSEADDR` for instant rebind, and phase timing logs (`keystore / engine-create / bind`).

---

## R3 — Desktop-grade web UI (blueprint, not yet built)

Today's SPA is a single 760 px column with five top tabs and a modal for every edit — a "stretched phone
list". Every reference app splits the width into panes. Target shell (TickTick / Todoist / MS-To-Do model):

```
┌────┬───────────────┬────────────────────────────┬──────────────┐
│NAV │ CONTEXT        │ MAIN (per-view)            │ INSPECTOR    │
│RAIL│ SIDEBAR        │ list / board / matrix /    │ (right pane) │
│56px│ (lists/filters │ calendar grid / reports    │ detail/edit  │
│    │  /mini-cal)    │                            │ 320–380px    │
└────┴───────────────┴────────────────────────────┴──────────────┘
```

- **Nav rail** (left, ~56px): the five domains + theme toggle; replaces the top tabs; tooltips in icon mode.
- **Context sidebar** (~240px, collapsible, `M`): per-domain filters/smart-lists/mini-month.
- **Main**: the per-view workspace.
- **Right inspector** (~320–380px, slide-over, not a modal): task/note detail+edit; opens on row click /
  `Enter`, closes on `Esc`. Docks on wide screens, overlays below ~1100px. *Moving edit from a centered modal
  to a right inspector is the single biggest "feels desktop" win.*

**Per feature**
- **Tasks**: view switcher **List / Board / Eisenhower matrix** (2×2 droppable grid mapping our priority
  field); outliner nesting (`Ctrl+]`/`[`), drag-reorder, hover row actions, right-click menu; quick-add bar
  (`Q`/`A`) with `!due *star #list` tokens; multi-select bulk bar (reuse existing `data-bulk`).
- **Notes**: Google-Keep **masonry card grid** (pinned group separated, attachment thumbnails) + an
  Obsidian-style markdown editor in the inspector/full width.
- **Calendar**: **Month grid / Week-Day time-grid / Agenda** switcher (reuse current list as Agenda), a
  mini-month navigator in the sidebar, event popovers, and tasks-with-due-dates overlaid (Morgen-style).
- **Time** → a **report dashboard**: summary stat tiles + a bar chart (per `dataviz` conventions) + the
  detail table.
- **Habits** → a **habit × day heatmap** (or card grid with streaks) instead of a flat list.

**Cross-cutting**: a **Cmd/Ctrl-K command palette** (the strongest "native desktop" signal), a real keyboard
model, **optimistic updates** (mutate local state immediately; the WS reconciles; roll back + toast on
error), density + hover + right-click affordances, illustrated empty states, light/dark via the existing
tokens.

**Responsive ladder** (keep phone unchanged): ≥1280 all four columns · 960–1280 inspector overlays ·
720–960 sidebar off-canvas, nav rail icon-only · <720 today's single column + bottom nav + bottom-sheet edit.
`--maxw` applies **inside a pane**, never to the whole app.

**Implementation note**: the shell is a plain CSS grid (`56px auto 1fr auto`) with columns collapsing via
`data-*` + media queries — no framework. Keep the current per-domain renderers as the narrow/phone
renderers; add wide renderers that reuse the same data + row builders, so phone behavior is untouched and
risk is contained. "Same data, many views": one dataset + a `view` state swapping the main renderer,
persisted per-domain in `localStorage` (wrapped in try/catch).

Sources are recorded in the three research threads (WebSocket/SSE/long-poll comparisons and reconnection
values from oneuptime/openreplay/Home-Assistant/socket.io; keygen timings from KeyDroid arXiv 2507.07927 and
W3C; CIO-no-TLS from the Kotlin Slack; layout patterns from each app's docs/reviews).
