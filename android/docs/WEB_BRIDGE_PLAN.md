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

> **Revised after a code pressure-test (see §11).** The original draft said the addon would "proxy to the
> core over the existing bridge." Verified against the code, that quietly requires the **core to become a
> bridge _provider_** — a role it has never had (today it is consumer-only: `BridgeRegistry` +
> `BridgeConnection`, plus a `<queries>` entry to _discover_ addons, but no exported provider service). The
> design below is corrected for that, and for three other verified gaps (handler scope access, no
> `ParcelFileDescriptor` on the spine, persistent token authority location + consent direction).

### 5.0 Role inversion — the core becomes a bridge provider (the key correction)

For voice, the **addon is the provider** (holds the mic, serves `voice.stt`) and the **core is the
consumer**. For the web bridge it is the **opposite**: the core holds the data, so the **core must be the
provider** of a `data` capability, and the **web-bridge addon is the consumer** that calls it.

The bridge was built symmetric, so this reuses the existing spine with no new protocol:

- The core gains a `HexisDataProviderService : BridgeProviderService` — **exported**, gated by the
  existing `signature BIND_BRIDGE` permission, and verifying the caller's keyset via `BridgeProviderService`
  (same as the addon does today). It advertises capability `data` via the same `PROVIDER_ACTION`
  meta-data mechanism.
- Consent **direction reverses**: the **core grants the addon**. The core mints the scoped token (the user
  picks scopes in a core consent screen) and the addon presents it on each `data` call; the core verifies
  it. This is the mirror of voice's `ConsentActivity`, hosted in the core.
- **Discovery self-filter**: the core already calls `queryIntentServices(PROVIDER_ACTION)` to find addons;
  once the core _is_ a provider, it must skip its own package in that scan, and the web-bridge addon adds a
  `<queries>` entry for the core.
- **Trade-off (documented):** this adds exactly one exported, signature-gated, scope-limited, revocable
  service to the core. [`SECURITY.md`](SECURITY.md)'s "exported components" line must be updated. The core
  still holds **zero forbidden permissions** — `INTERNET` stays in the addon.

### 5.1 Modules & permission

- New `:web-bridge` addon APK, `depends on :bridge` only (never on `:app`). Holds **`INTERNET`** (+ an FGS
  type perm + `POST_NOTIFICATIONS`). A small control screen (start/stop, pairing QR, port, granted scopes,
  connected clients, stop-all) — no launcher beyond it.
- Signed with the **same Hexis keyset** → bridge trust + the `BIND_BRIDGE` signature permission apply in
  both directions.
- The core gains **no new permission**; it gains the provider **role** and a `data` capability.
- **Persistent token authority** is hoisted from the voice addon into `:bridge` (verified: it lives in
  `voice-addon` today, while the interface is already in `:bridge`). Both the addon (as provider for voice)
  and the core (as provider for `data`) then share one persistent `TokenAuthority` implementation.

### 5.2 The `data` capability (the crux)

This is the first capability that reads/writes **core data** (voice only returned derived text), so its
shape + scope enforcement is the heart of the work. It is defined once in `:bridge`; the handler lives in
`:app` (it needs `AppRepository`).

- **Generic, extensible contract** (so adding a domain is additive, never a spine change):
  `DataQuery { domain, op, cursor?, limit?, params }` → `DataPage { items: JSON[], nextCursor? }`;
  `DataMutation { domain, op, payload }` → `DataResult`. Each `op` maps to a **curated, versioned facade**
  over the repository — a hand-picked allow-list, **not** the raw 294-method surface.
- **Scopes**, default-deny, per domain + mode: `data.tasks.read/write`, `data.notes.read/write`,
  `data.calendar.*`, `data.time.*`, `data.habits.*`, … The core consent screen grants exactly the chosen
  domains, **read-only by default**.
- **Verified gap → scope-set extension.** `CapabilityHandler.invoke(request, caller)` today receives only a
  `VerifiedCaller` (packageName, uid, signatureTrusted) — **no token/scopes**. A multi-domain `data`
  capability must check a per-request scope, so the dispatcher will resolve the caller's `GrantToken` and
  pass its **scope set** (or a `scopeCheck` lambda) into the handler. Backward-compatible — the voice
  handler ignores it. This is a real, small change to `:bridge`, scoped to W0.
- **Verified gap → no FD on the spine.** `IHexisBridge` has only `handshake/invoke/openStream/
  controlSession`, all `byte[]`/callback — **no `ParcelFileDescriptor`** (the Open Transcribe FD is a
  _different_ AIDL, not ours). So: W0–W2 are **text-only** (paginate lists so each `ResponseEnvelope` <1 MB;
  stream big result sets as chunked `openStream` events). **Attachments/media (W3)** need binary: **append**
  a 5th method `ParcelFileDescriptor openBlob(in byte[] requestEnvelope)` to the AIDL — appending is
  backward-compatible (older peers return `UNKNOWN_TRANSACTION`, which we catch), so the "spine never
  reshapes" rule holds; it only _grows_ by one optional method, and only when attachments land.
- **Threading.** Repository calls are `suspend`; the handler runs on a binder thread, so it offloads to an
  IO dispatcher (and must not block the binder pool). Live updates (W2+) observe the repository's existing
  `Flow`s (`observeTasksByWorkspace`, `observeNotes`, … — verified present) and emit `openStream`
  `EventEnvelope`s the addon relays to the browser as SSE.
- **One contract, two transports.** The browser↔addon HTTP request is a thin shell the addon re-wraps as a
  bridge `RequestEnvelope`/`DataQuery` and forwards to the core, returning the `ResponseEnvelope`. The
  `data` contract is defined once and reused for both hops — the addon stays a **dumb, data-less proxy +
  crypto edge**.

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
| **W0 — Core becomes a provider; `data` capability** | Hoist a persistent `TokenAuthority` into `:bridge`; add the `CapabilityHandler` scope-set extension; stand up `HexisDataProviderService` (exported, signature-gated) + a core consent/mint screen (core grants the addon) + the discovery self-filter; implement a **read-only** `tasks`+`notes` facade handler with per-scope checks + audit; update `SECURITY.md`. No server/addon yet; unit-test over the dispatcher + a loopback (like the voice test). | The core can serve scoped, audited, revocable data over the bridge — verified headlessly. |
| **W1 — Minimal server + pairing** | `:web-bridge` addon (consumer): binds the core provider; Ktor CIO server, FGS + notification, QR pairing, P‑256/HKDF/AES‑GCM E2E, Host/Origin guard, a bare SPA that lists tasks + notes read-only. | A browser can securely view tasks/notes over LAN. |
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
- **Binder payload sizes.** Enforce **pagination from W0** (each `ResponseEnvelope` <1 MB) and chunk large
  result sets over `openStream`; the binary **FD channel is a W3 addition** (appended AIDL method), not W0.
  Never return unbounded lists.
- **The core gains an exported service.** `HexisDataProviderService` is new attack surface on the
  previously-minimal core. Mitigated: signature `BIND_BRIDGE` permission + in-code keyset verify + default-
  deny scopes + audit + kill switch; **`SECURITY.md` must be updated** to reflect it (its "exported
  components" line currently lists only launcher/widgets/receivers).
- **Trust UX friction** (browser warnings) is the usability risk; the app-layer E2E makes it safe-by-default
  and the local-CA option makes it pretty. Needs careful onboarding copy.
- **Battery/FGS limits** make this an on-demand session, not a 24/7 server — which is the right default
  anyway.
- **Scope granularity vs. simplicity**: start coarse (per-domain read/write), refine only if asked.
- **Keep verifying** the core's merged manifest stays at 0 forbidden permissions after wiring the new
  capability (the provider role adds no permission; `INTERNET` stays in the addon).

## 9. Key decisions

| Decision | Rationale |
| --- | --- |
| Network edge in a **separate addon**, data+key in the core | Bounded blast radius; core keeps 0 permissions — the thing PlainApp structurally can't do. |
| **Core becomes a bridge provider** (`data` capability), reusing the symmetric spine; consent direction reverses (core grants addon) | The data+key must stay in the core, so whatever serves data must run in the core; a cross-app bind needs an exported, signature-gated service. One new surface, fully gated + revocable. |
| **Hoist persistent `TokenAuthority` to `:bridge`** | Both providers (addon for voice, core for `data`) share one persistent, tested token store. |
| **Generic `DataQuery`/`DataPage`/`DataMutation` contract over a curated facade** | Adding a domain is additive (no spine change); the raw 294-method repository is never exposed. |
| **AES‑256‑GCM + P‑256 ECDH + HKDF**, app-layer, over (optional) self-signed TLS | All native in browser `SubtleCrypto` → no WASM/JS crypto; secure even over plain HTTP; MITM-resistant via QR-bootstrapped keys. |
| **High-entropy QR pairing key**, never disclosed over the wire | Beats PlainApp's 6-char `/init`-disclosed password. |
| **Host-header + Origin allow-listing**, token-in-header (no cookies) | DNS-rebinding + CSRF defense PlainApp lacks. |
| **Same-origin SPA from the addon**, PWA | No CORS/mixed-content; sidesteps Chrome Local Network Access. |
| **Ktor CIO**, small typed JSON API (no GraphQL) | Lighter on Android; far less machinery than a KMP GraphQL engine + KSP. |
| **Curated `data` facade + per-domain scopes enforced in the core**, audited, revocable | Least privilege across the process boundary; reuses `BridgeRegistry`. |
| **Pagination + FD streaming** | Respect the 1 MB Binder limit; power capability-URL media. |
| Remote access **opt-in only** (WebRTC/Tailscale), LAN-only default | Keep the default fully local; no inbound ports. |

---

## 10. Pressure test — verified against the codebase

Every load-bearing assumption of this plan was checked against the actual Hexis source before committing to
it. Results and the corrections they forced:

| # | Assumption under test | Verified in code | Verdict → correction |
| --- | --- | --- | --- |
| 1 | The addon can "proxy to the core over the existing bridge." | The core is **consumer-only** (`BridgeRegistry` + `BridgeConnection`; the `bridge.PROVIDER` at `app/.../AndroidManifest.xml:79` is a **`<queries>`** discovery entry, not an exported service). | **Partly false → biggest fix.** The core must become a **provider** (`HexisDataProviderService`). Added §5.0; W0 now stands this up. |
| 2 | The `data` handler can enforce per-domain scopes. | `CapabilityHandler.invoke(request, caller: VerifiedCaller)` — `VerifiedCaller` has only `packageName/uid/signatureTrusted`, **no token/scopes**. | **False → fix.** Dispatcher must resolve the `GrantToken` and pass its scope set into the handler (backward-compatible). Scoped to W0. |
| 3 | Attachments stream via a `ParcelFileDescriptor` "like Open Transcribe." | `IHexisBridge` has only `handshake/invoke/openStream/controlSession` (all `byte[]`/callback) — **no FD**. The Open Transcribe FD is a *different* AIDL. | **False → fix.** Text is paginated over `invoke`/`openStream` (W0–W2); binary needs an **appended** `openBlob` method (W3), backward-compatible via `UNKNOWN_TRANSACTION`. |
| 4 | Reuse a persistent token store; consent as for voice. | `PersistentTokenAuthority` lives in **`voice-addon`**, not `:bridge`; consent is minted by the **provider** side. | **Fix.** Hoist it to `:bridge`; since the **core** is now the provider, the **core** mints the token and hosts consent (reverse of voice). |
| 5 | Live updates are feasible from core data. | `AppRepository` exposes `observeNotes()`, `observeNotesByWorkspace()`, `observeTasksByWorkspace()`, `observeTask()` **`Flow`s**. | **Sound.** Provider observes these Flows → `openStream` events → addon → browser SSE (W2+). |
| 6 | The new surface is captured in the threat model. | `SECURITY.md` states exported components are "limited to the launcher, widgets, and the notification/boot receivers." | **Fix.** W0 must update `SECURITY.md` to add the signature-gated, scope-limited, revocable `data` provider. |

**Soundness verdict.** The thesis — _network edge isolated from data+key, core stays at zero forbidden
permissions_ — holds and is actually reinforced by the review. The architecture is sound **once the core's
new provider role is made explicit** (it was the one real omission). The remaining pieces (Ktor CIO,
same-origin PWA, WebCrypto E2E, QR pairing, Host-header/CSRF defenses, pagination) checked out against both
the code and the platform constraints. The plan is now internally consistent, modular (one generic `data`
contract, curated facade, per-domain scopes), and professional-grade; W0 is a safe, headlessly-testable
first step that adds **no new permission and no network surface**.

---

## 11. Implementation status (what shipped)

W0–W4 are built, tested (detekt + unit tests green), and the core's merged manifest was re-verified to hold
**0 forbidden permissions** at each step. The AIDL spine is **unchanged** (see the two deviations below).

| Phase | Status | Notes |
| --- | --- | --- |
| **W0 — Core as `data` provider** | ✅ Shipped | `HexisDataProviderService` (exported, signature-gated by `permission.BIND_DATA_BRIDGE`), core-hosted consent/mint, discovery self-filter, persistent `TokenAuthority` hoisted to `:bridge`, scope-set extension on `CapabilityHandler`/dispatcher. **Security fix:** the dispatcher binds a token's scopes to the caller package (`scopesOf`), so one trusted peer can't present another's token. |
| **W1 — Server + pairing** | ✅ Shipped | Ktor CIO server, `dataSync` FGS + notification, QR pairing, HKDF-SHA256 + AES-256-GCM E2E (WebCrypto-native), Host/Origin allow-list, replay guard, vanilla-JS same-origin SPA (no build toolchain), read-only tasks/notes. |
| **W2 — Editing + live refresh** | ✅ Shipped | Task create/edit/complete/delete (delete = reversible move-to-Trash; vaulted notes protected), note edit; the `changes` stream (repository Flows) fanned out to browsers via a **version-based long-poll over the same encrypted channel** (not a separate SSE socket). Provider base links consumer-callback death → `CANCEL` so a dead consumer can't leak a collector. |
| **W3 — Breadth** | ✅ Shipped | Read companions for calendar / time / habits (projected DTOs, active-workspace scoped); bulk task ops (complete/star/delete); note attachments (metadata + inline small images). |
| **W4 — Trust UX & sharing** | ✅ Shipped | Per-device keys (`WebClient`/`ClientStore`): add, revoke, last-seen; read-only **share links** (read-only + expiry); server matches each request against the live client set so revoke/expiry is instant; `hello` lets the browser render a read-only UI. |
| **W5 — Remote (opt-in)** | ⏸ Deferred | WebRTC/Tailscale off-LAN access. Large, standalone, and explicitly opt-in; it also changes the threat model (signaling/relay). Left for an explicit go-ahead; LAN-only is the shipped default. |

### Two deliberate deviations from the original plan (both tighten the architecture)

1. **Attachments do not grow the AIDL spine (revises §5.2 / pressure-test row 3).** The plan proposed an
   appended `ParcelFileDescriptor openBlob(...)` for binary. Building it would pull `android.os` types into
   the deliberately Android-free `CapabilityHandler`/`BridgeDispatcher` (breaking their pure, headlessly
   testable property) and risks oneway-buffer overflow if chunked over `openStream`. Instead, **small images
   are inlined as Base64 over the existing `invoke` path under a 512 KB cap** (safely under the Binder limit);
   larger/non-image attachments return metadata with an "open on phone" affordance. The fixed spine stays
   fixed and the dispatcher stays pure. Full-size binary streaming via an FD side-channel remains a clean
   future option if it's ever needed.
2. **HTTPS is required, not optional (corrects the initial W4 call).** W4 first shipped plain HTTP on the
   assumption that the app-layer E2E made TLS mere padlock polish. On-device testing proved that wrong:
   browsers expose WebCrypto's `crypto.subtle` **only in a secure context**, and `http://<lan-ip>:port` is
   not one — so the app-layer AES-GCM (which the whole model depends on) could not even run in the browser
   over plain HTTP, and pairing failed with a misleading "malformed key". Fix: the server now serves
   **self-signed HTTPS** (engine switched from CIO, which has no server TLS, to **Netty**; cert via
   `ktor-network-tls-certificates`, persisted by `WebTls`). The browser shows a one-time self-signed-cert
   warning (standard for self-hosted LAN tools; the control screen explains it), after which the origin is a
   secure context and the crypto works. The per-device QR key remains the authorization/MITM trust anchor;
   TLS now sits under it as transport encryption. A name-constrained **local-CA** option (to remove the
   warning entirely) remains the only deferred sub-item. "Per-client management" and "share-a-view read-only
   links" shipped in full.

   A **copy-link** button on each device dialog and a **core-matching visual theme** (indigo brand, white
   cards on soft grey, rounded shapes — mirroring `app/.../ui/theme`) shipped alongside these fixes so the
   web companion reads as the same product as the core app.
