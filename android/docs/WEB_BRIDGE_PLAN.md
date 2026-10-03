# Hexis — Web Bridge Addon: Analysis & Plan

A plan for a **Web Bridge** addon: run a local web server on the phone so Hexis can be managed from a
browser on your computer (same Wi‑Fi / LAN), turning a mobile-only, offline, account-free app into one
that also has a **full desktop companion** — without a cloud, an account, or the core ever gaining a
network permission.

This is design-stage. It is grounded in a code read of **PlainApp** (`com.ismartcoding.plain`) and
research into comparable apps (KDE Connect, LocalSend, Syncthing, Obsidian Local REST API, Joplin,
Warpinator, AirDroid) and the Android/browser constraints. It deliberately **takes the good ideas and
beats the gaps** — it is not a port.

> Prereq reading: [`BRIDGE_AND_ADDONS.md`](BRIDGE_AND_ADDONS.md) (the core/bridge/addon system this builds
> on), [`SECURITY.md`](SECURITY.md).

---

## 1. Why this is worth building

- **The biggest limitation of a mobile-only, offline app is organizing on a small screen.** Capture is
  great on the phone; triage, outlining, bulk edits, time reports, and long-form note writing are painful.
  A browser UI on a big screen with a real keyboard is where that work wants to happen.
- **No competitor in the privacy niche offers this well.** PlainApp does phone management (files/SMS/
  contacts); a *productivity* app exposing its own rich structured data (tasks, notes, habits, calendar,
  time) to a desktop browser — with no account and no cloud — is a genuinely differentiated product.
- **It reuses the thing we already have.** The core already holds all the data and logic; the web bridge
  is a new *edge*, not a new app.

---

## 2. The architectural thesis (why ours beats PlainApp by construction)

PlainApp is a **monolith**: one process holds `INTERNET` + the web server + every dangerous permission
(SMS, contacts, storage…) + all the data + the crypto. Compromise that process (it is the network-facing
attack surface) and everything is exposed.

Hexis splits it:

```
        browser (your computer)
            │  HTTPS / app-layer E2E  (LAN)
            ▼
┌───────────────────────────────┐        ┌────────────────────────────────────┐
│  :web-bridge addon (new APK)   │  AIDL  │  :app  (core, 0 dangerous perms)     │
│  • holds INTERNET (the socket) │ ─────► │  • holds the encrypted DB + the key  │
│  • Ktor server + the SPA       │  bridge│  • enforces per-domain scopes        │
│  • app-layer crypto / pairing  │ ◄───── │  • audits every access, kill switch  │
│  • NO data, NO DB key          │        │  • returns ONLY what was granted     │
└───────────────────────────────┘        └────────────────────────────────────┘
```

**The network edge is isolated from the data and the key.** The web-bridge addon holds `INTERNET` and
terminates the browser connection, but it has **no Hexis data of its own** — it proxies every request to
the core over the existing Hexis bridge, and the core returns only what the user's granted scopes allow.
Consequences PlainApp cannot match:

- **Bounded blast radius.** Even if the web-bridge addon is fully compromised, the attacker gets only the
  granted scopes' data — never the DB key, never ungranted domains. The core's encrypted store is
  untouchable across the process boundary.
- **The core stays at zero forbidden permissions.** `INTERNET` lives only in the addon, exactly as
  `RECORD_AUDIO` lives only in the voice addon. The promise in [`SECURITY.md`](SECURITY.md) holds.
- **Instant, auditable revocation.** The core already has the grant/scope/audit/kill-switch machinery
  (`BridgeRegistry`); the web client is just another bridge consumer. Revoke → the server can read nothing.

This is the whole point: **we can offer a web UI without weakening the "your data can't leave the device"
guarantee**, because the part that *can* touch the network never touches the data.

---

## 3. What PlainApp actually does (code-verified) — adopt vs. beat

| PlainApp mechanism (from its source) | Verdict |
| --- | --- |
| App-layer E2E (**ECDH P‑256 → session key, XChaCha20‑Poly1305 AEAD, Ed25519-signed identity pinned from `/init`**), independent of TLS → secure even over plain HTTP | **Adopt the idea.** But use **AES‑256‑GCM** instead of XChaCha20: WebCrypto (`SubtleCrypto`) does P‑256 ECDH **and** AES‑GCM natively, so the browser needs **no libsodium.js/WASM** (PlainApp ships a JS ChaCha). Smaller, simpler, audited primitives. |
| Capability-URL media serving: `<img src>`/`<video>` use `/fs?id=<encrypted(path)>` so no auth header needed | **Adopt** for attachments — but with **real expiry** (PlainApp's default `rotateUrlTokenOnRestart=false` makes them long-lived). |
| Default-deny **per-feature permission enum**, reused for OS + web, enforced in each resolver | **Adopt** — but enforce it in the **core**, across the bridge, not in the server process. |
| Custom **typed binary WebSocket push** for live updates (not GraphQL subscriptions) | **Adopt the idea**; we'll likely use **SSE** (one-way, auto-reconnect) — our updates are server→browser refreshes, and SSE is simpler than WS and needs no binary framing. |
| mDNS `.local` discovery + QR of the **URL** + PWA served same-origin from assets | **Adopt** wholesale. |
| GraphQL (~95 queries / 129 mutations) via a vendored KMP KGraphQL + a custom KSP processor | **Reject the complexity.** We don't need a GraphQL engine or codegen; see §5.3. |
| Vendored **Ktor on Netty** | **Reject the engine.** Use Ktor **CIO** (coroutine-based, lighter, Android-friendly; Netty drags in `java.*` needing desugaring). |
| Auth = **6-char password auto-disclosed over `/init`** to the first caller; 2FA accept-prompt; 5/60s rate limit | **Beat it.** Never disclose a secret over the wire; pair with a **high-entropy key carried in the QR** (or a PAKE). Keep the in-app accept prompt + rate limiting. |
| **No Host-header / DNS-rebinding guard** found in the server | **Beat it.** Strict Host + Origin allow-listing from day one. |
| Plain HTTP connector always on; self-signed cert → browser warning pushes users to HTTP | **Beat it.** HTTPS-by-default with a clean trust UX (fingerprint pinning shown in the QR; optional name-constrained local CA for a real padlock), and the app-layer E2E so even HTTP stays confidential. |

External constraints that shape the design (from the research):

- **You cannot get a CA-signed cert for a LAN IP or `.local`** (Let's Encrypt refuses; can't domain-validate
  a private address). So LAN TLS is self-signed → trust must be bootstrapped out-of-band (our QR) or via a
  user-installed local CA. This is *the* crux, and the app-layer E2E is what makes it a non-blocker.
- **Chrome's "Local Network Access" permission prompt** (replacing Private Network Access, shipping ~Chrome
  142) will gate any **public** page that reaches a private IP. Serving the SPA **from the phone's own
  origin** (same-origin with the API) sidesteps it — another reason not to host the client anywhere else.
- **Android 14+ foreground-service types**: the server runs under a `dataSync` (or `specialUse`) FGS with a
  persistent "server running" notification; `dataSync` is time-boxed (~6h/day on Android 15), which fits an
  on-demand "start the server" session, not a 24/7 daemon.
- **WebCrypto gives us P‑256 ECDH + HKDF + AES‑256‑GCM natively** → the entire browser-side crypto is
  standard `SubtleCrypto`, no third-party crypto JS.

---

## 4. The product: what the web UI is for

The phone stays the capture device; the browser becomes the **organize / review / write** surface:

- **Tasks**: the full outliner on a wide screen — drag-to-nest, multi-select bulk edit, the Eisenhower
  matrix, Do‑Next, keyboard-driven quick-add, smart lists.
- **Notes**: a proper markdown editor with a side preview; backlinks; long-form writing with a keyboard.
- **Calendar / Time**: a week/month grid; time-tracking reports and charts that are cramped on mobile.
- **Habits / Goals / Countdowns**: review dashboards.
- **Bulk & power ops**: reschedule many, retag, triage the inbox, export.
- **Read-only "share a view"** (later): a scoped, expiring link to e.g. today's agenda for another device.

Framing it as "capture on phone, organize on desktop" is the workflow win — and it's all local.

---

## 5. The Hexis design

### 5.1 Modules & permission

- New `:web-bridge` addon APK, `depends on :bridge` only (never on `:app`). Holds **`INTERNET`** (+ the
  `dataSync`/`specialUse` FGS permission + `POST_NOTIFICATIONS`). No launcher beyond a small control
  screen (start/stop, pairing QR, port, which scopes are granted, connected clients, stop-all).
- Signed with the **same Hexis keyset** → the core's bridge trust + the signature `BIND_BRIDGE` permission
  apply unchanged.
- The core gains **no new permission**; it gains new *capabilities* it serves over the bridge.

### 5.2 The new core capability: `data` (the crux)

This is the first addon that reads/writes **core data** (voice only returned derived text), so the
capability + scope design is the heart of the work.

- Capability id `data` with methods `query` and `mutate`. The payload names a **domain + operation**
  (e.g. `tasks.list`, `tasks.upsert`, `notes.get`, `time.report`), mapping to a curated allow-list of
  repository operations — **not** the raw 294-method repository surface. A hand-picked, versioned facade.
- **Scopes**, default-deny, per domain + mode: `data.tasks.read`, `data.tasks.write`, `data.notes.read`,
  `data.notes.write`, `data.calendar.*`, `data.time.*`, `data.habits.*`, … The consent screen lets the
  user grant exactly the domains they want the browser to see, read-only by default.
- **Enforcement in the core.** The `data` handler checks the granted scope for each request's domain+mode
  before touching the repository, and writes a `BridgeRegistry` audit row. This needs a **small bridge
  extension**: today the dispatcher checks one `requiredScope(method)` at the gate; a multi-domain `data`
  capability needs the handler to see the token's **scope set** and check per request. (Concretely: pass
  the resolved `GrantToken.scopes` — or a `scopeCheck` lambda — into `CapabilityHandler`. Backward
  compatible; voice ignores it.)
- **Large payloads / the 1 MB Binder limit.** Lists are **paginated**; a large note body is fine as text;
  **attachments/media stream via a `ParcelFileDescriptor`** the core hands back (the same FD pattern the
  Open Transcribe path already uses), which also powers capability-URL media in the browser.
- **One contract, two transports (elegant option).** The browser↔addon HTTP request can be a thin shell
  the addon re-wraps as a bridge `RequestEnvelope` and forwards to the core, returning the
  `ResponseEnvelope`. The `data` contract is then defined once in `:bridge` and reused for both hops — the
  addon stays a dumb, data-less proxy + crypto edge.

### 5.3 Server & API

- **Ktor CIO** `embeddedServer`, bound `0.0.0.0` **only behind pair-before-serve** (no data endpoint
  answers until paired). HTTP + self-signed HTTPS connectors; ports with fallback (8443/8080-style).
- **API = small typed JSON over HTTP** (not GraphQL): `POST /api/{domain}.{op}` with a JSON body, mapped
  to the `data` capability. SSE at `/events` for live refresh. Versioned (`/api/v1/…`).
- **SPA served same-origin from the addon's assets** (`/`), PWA-installable. Same origin ⇒ no CORS, no
  mixed-content, dodges Chrome LNA.

### 5.4 Security model (beating PlainApp's gaps)

1. **Pairing (out-of-band, high-entropy).** The app shows a QR carrying `{url, tls-cert-fingerprint,
   pairing-key}` where `pairing-key` is a 256-bit random secret (not a 6-char password, never disclosed
   over the wire). Optional typed short code backed by **SPAKE2+** for users who can't scan.
2. **App-layer E2E on top of (optional) TLS.** Browser and phone do **P‑256 ECDH** (keys authenticated by
   the pairing-key/QR), derive a session key via **HKDF**, and encrypt every request/response with
   **AES‑256‑GCM** — all in `SubtleCrypto`, no WASM. The phone signs its identity key; the browser pins it
   from the QR fingerprint. Result: confidential + authenticated **even over plain HTTP**, and MITM on a
   hostile LAN is defeated because the attacker lacks the QR secret.
3. **HTTPS-by-default + clean trust UX.** Ship self-signed TLS; show the fingerprint in-app and in the QR
   so the browser's one-time warning is verifiable; offer an optional **name-constrained local CA**
   (Obsidian model) the user installs once for a real padlock. Never nudge users to plain HTTP.
4. **Host-header + Origin allow-listing** on every request (DNS-rebinding + CSRF defense PlainApp lacks).
5. **Token-in-header auth, not cookies** (no ambient CSRF); **anti-replay** (timestamp window + nonce
   cache, clock synced via an injected server time); **login rate limiting**.
6. **Capability URLs with real expiry** for media/attachments.
7. **Default-deny scopes**, enforced in the core, **audit-logged**, instantly revocable; a visible FGS
   "server running — N clients" notification; "stop now" everywhere.
8. **Refuse-to-start heuristics** on obviously public networks; always require pairing first.

### 5.5 Discovery & the web frontend

- **mDNS/NSD** advertises `hexis-xxxx.local` + the port; the control screen also shows the raw
  `https://<ip>:<port>` and a QR. Manual entry supported.
- **Frontend**: a small SPA. Candidate stack: **Svelte/SvelteKit (static adapter)** or Vue3 — both compile
  to static assets served from the addon; pick for bundle size + our familiarity. It talks only to the
  same-origin `/api` + `/events`, does the WebCrypto E2E, and is a PWA.

### 5.6 Optional remote access (later, opt-in)

- **WebRTC DataChannel** (DTLS-encrypted, our app-layer on top) for off-LAN access with **no inbound
  ports** — only a minimal signaling step that never sees data. Or a **Tailscale/WireGuard** "serve over
  the tailnet" mode for power users. Both strictly opt-in; LAN-only is the default.

---

## 6. Threat-model delta (what the addon adds)

| | Core today | With web-bridge addon installed + running + paired |
| --- | --- | --- |
| Network reachable | none | the addon's LAN port, **pair-gated**, app-layer E2E |
| Data exposed | none off-device | **only granted scopes**, via the core, audited, revocable |
| DB key / encrypted store | in the core | **never leaves the core**; the addon can't reach it |
| Attack surface if addon is popped | n/a | bounded to granted scopes; core + key intact |
| Core permissions | 0 forbidden | **still 0** (INTERNET is the addon's) |

The net new risk is "a paired browser on your LAN (or an attacker who steals the QR secret) can act within
the granted scopes." That is the intended capability, bounded and revocable — and strictly smaller than
PlainApp's "one compromised process = everything."

---

## 7. Phased roadmap (each phase shippable)

| Phase | Scope | Deliverable |
| --- | --- | --- |
| **W0 — Bridge `data` capability** | Define the `data` contract + per-domain scopes in `:bridge`; add the scope-set extension to `CapabilityHandler`; implement a **read-only** `tasks` + `notes` facade handler in the core + consent + audit. No server yet; unit-test over the dispatcher (like the voice loopback). | The core can serve scoped data over the bridge. |
| **W1 — Minimal server + pairing** | `:web-bridge` addon: Ktor CIO server, FGS + notification, QR pairing, P‑256/HKDF/AES‑GCM E2E, Host/Origin guard, a bare SPA that lists tasks + notes read-only. | A browser can securely view tasks/notes over LAN. |
| **W2 — Editing** | Write scopes + mutations (create/edit/complete/reschedule tasks; edit notes); the outliner + markdown editor; SSE live refresh. | A usable desktop editor. |
| **W3 — Breadth** | Calendar, time reports, habits/goals/countdowns; bulk ops; capability-URL attachments. | Full companion. |
| **W4 — Polish & trust UX** | Local-CA option, fingerprint pinning UX, per-client management, share-a-view read-only links. | Clean padlock, shareable views. |
| **W5 — Remote (opt-in)** | WebRTC / Tailscale mode. | Off-LAN access without open ports. |

A natural dividend: **W1's transport + E2E crypto is ~80% of a future device-to-device Sync addon** — so
this also de-risks the sync idea in [`ADDON_ROADMAP.md`](ADDON_ROADMAP.md).

---

## 8. Risks & open questions

- **New frontend codebase.** A SPA is real, ongoing work; mitigate by starting read-only + tasks/notes and
  growing. Decide the framework up front (lean to Svelte for size).
- **Bridge data-capability is a public-ish contract.** Version it (`/api/v1`, `contractVersion`); keep the
  facade curated, not the raw repository.
- **Binder payload sizes.** Enforce pagination + FD streaming from W0; never return unbounded lists.
- **Trust UX friction** (browser warnings) is the usability risk; the app-layer E2E makes it safe-by-default
  and the local-CA option makes it pretty. Needs careful onboarding copy.
- **Battery/FGS limits** make this an on-demand session, not a 24/7 server — which is the right default
  anyway.
- **Scope granularity vs. simplicity**: start coarse (per-domain read/write), refine only if asked.
- **Keep verifying** the core's merged manifest stays at 0 forbidden permissions after wiring the new
  capability.

## 9. Key decisions

| Decision | Rationale |
| --- | --- |
| Network edge in a **separate addon**, data+key in the core | Bounded blast radius; core keeps 0 permissions — the thing PlainApp structurally can't do. |
| **AES‑256‑GCM + P‑256 ECDH + HKDF**, app-layer, over (optional) self-signed TLS | All native in browser `SubtleCrypto` → no WASM/JS crypto; secure even over plain HTTP; MITM-resistant via QR-bootstrapped keys. |
| **High-entropy QR pairing key**, never disclosed over the wire | Beats PlainApp's 6-char `/init`-disclosed password. |
| **Host-header + Origin allow-listing**, token-in-header (no cookies) | DNS-rebinding + CSRF defense PlainApp lacks. |
| **Same-origin SPA from the addon**, PWA | No CORS/mixed-content; sidesteps Chrome Local Network Access. |
| **Ktor CIO**, small typed JSON API (no GraphQL) | Lighter on Android; far less machinery than a KMP GraphQL engine + KSP. |
| **Curated `data` facade + per-domain scopes enforced in the core**, audited, revocable | Least privilege across the process boundary; reuses `BridgeRegistry`. |
| **Pagination + FD streaming** | Respect the 1 MB Binder limit; power capability-URL media. |
| Remote access **opt-in only** (WebRTC/Tailscale), LAN-only default | Keep the default fully local; no inbound ports. |
