# Hexis Bridge & the Voice Addon — Design

This is the doc we build the addon system from. It locks the **bridge architecture** that
lets Hexis extend beyond a permission-free, offline core, and specifies the **first addon
(Voice)** in enough detail to start Phase 0. Companion to `plan-v2.md` (the core app) and
the `analysis/*.md` teardowns.

One-line thesis: **the core stays a permission-free, offline vault; capability lives in
satellite addons that each hold exactly one dangerous permission and hand the core only
derived results over a single, typed, audited bridge.**

> Status: **design, agreed.** Nothing here is built yet. We lock this doc, then build the
> bridge spine (Phase 0), then the Voice addon (Phase 1+). Decisions marked **[locked]**
> were confirmed in review; **[open]** items are resolved during the phase that needs them.

---

## 0. Confirmed decisions

- **[locked] Satellite-addon model.** Each addon is a separate, installable APK with no
  launcher surface. It holds one dangerous permission, does the privileged work in its own
  process, and returns only *derived data* to the core. The core gains capability without
  gaining permissions.
- **[locked] The core never delegates an Android permission** (there is no such OS
  mechanism, and `sharedUserId` is deprecated). Privilege is *inverted*: the addon is the
  permission holder; the core is a consumer of results.
- **[locked] The core remains sole owner and writer of the database.** The SQLCipher key
  never leaves the core process. Addons never read or write the DB. The core validates and
  commits everything; nothing an addon says is written without passing the core's rules.
- **[locked] All addon settings live in the core**, rendered generically by the Bridge
  Registry from a declared schema. Addons ship no settings UI.
- **[locked] Serialization = kotlinx.serialization** for the bridge envelope and capability
  contracts (already a core dependency; `ignoreUnknownKeys` covers version skew).
- **[locked] First addon = Voice (STT).** It is the purest privilege-inversion exemplar:
  it needs `RECORD_AUDIO` (which the core must never hold) yet needs *zero* scopes into
  core data.
- **[locked] Baked-in first-run STT model targets ~30 MB (tiny).** Fully offline on first
  run, no `INTERNET` in the addon. Larger/multilingual models are opt-in later.
- **[locked] TTS is not an addon.** The core speaks via the Android platform `TextToSpeech`
  API (no permission, no GPL entanglement); any installed engine supplies the voice.
- **[locked] F-Droid is the primary distribution channel** for addons (no launcher
  requirement); Play remains possible with disclosed, justified permissions.
- **[locked] Delivery order:** this design doc → Phase 0 (bridge spine) → Phase 1+ (Voice),
  one phase at a time.

---

## 1. Why this exists

Hexis committed to a hard invariant: the core app requests **zero forbidden permissions**
— no `INTERNET`, no location, no storage/media, no foreground service — and works fully
offline. That invariant is the product's trust story, and we will not weaken it.

But real reach (voice capture, using the app from a PC over the LAN, backups, geofenced
reminders) needs permissions the core refuses to hold. The resolution is architectural:
push each permission into a **small satellite app** that the user installs deliberately,
and connect it to the core through a narrow, audited **bridge**. The core asks the satellite
to do the privileged thing and receives only the *result*. If the satellite is uninstalled,
the core loses that capability and nothing else — no permission ever touched the core.

## 2. Invariants (non-negotiable)

1. **Core permission set is unchanged** by any addon. Addons hold their own permissions.
2. **Privilege inversion**: the permission holder is the addon; the core consumes results.
3. **Data boundary**: the SQLCipher key never leaves the core; addons never see the DB.
   Only derived, minimized data crosses the bridge, in the direction the task requires.
4. **Least privilege via scopes**: an addon is granted the narrowest data scope it needs
   (often *none*), enforced per call by the core.
5. **Confirm before mutate**: nothing an addon produces is written to the core's data
   without passing the core's validation and (by default) a user confirmation.
6. **Core-owned control**: every addon's settings, logs, rate limits, and kill switch live
   in the core.
7. **Offline by default**: an addon that can be fully offline ships that way; any network
   permission lives in the addon and is disclosed in the registry.

---

## 3. The Hexis Bridge — a Capability Bus

The load-bearing idea that keeps this modular across many different addons: **do not build
a bespoke AIDL interface per addon.** Build one tiny, stable transport and layer *typed,
self-describing capabilities* on top as data contracts. Mental model: an on-device,
permissionless-core "personal MCP" — a stable envelope carrying typed capabilities that
describe themselves, negotiate versions, and are discovered at runtime.

Nine layers, each independently testable:

```
┌─ Core (permission-free, sole DB owner) ─────────────────────────────┐
│  Bridge Registry: discovery · consent · scopes/tokens · settings    │
│  (auto-rendered) · audit log · rate limits · router · kill switch   │
└───────────────▲───────────────────────────────────────────────────-┘
                │  IHexisBridge (fixed, tiny AIDL spine)
                │  invoke(env) · openStream(env, cb) · subscribe(cap, cb)
                │  + ContentProvider "mailbox" (store-and-forward)
                │  envelopes = kotlinx.serialization ByteArray
┌───────────────▼─────────────────────────────────────────────────────┐
│  Addon (holds ONE dangerous permission; no launcher; no settings UI) │
│  implements typed capability contracts from hexis-bridge-contracts    │
└───────────────────────────────────────────────────────────────────-─┘
```

1. **Transport spine (fixed, tiny, versioned).** One AIDL interface: `invoke` (request/
   response), `openStream` (+ `oneway` callbacks for streaming), `subscribe` (events), plus
   a ContentProvider **mailbox** for store-and-forward to a dead peer. Payloads are
   serialized **envelopes** with headers (`capabilityId`, `contractVersion`, `token`,
   `nonce`, `idempotencyKey`, sensitivity flags). Bulk bytes/streams travel over a
   `ParcelFileDescriptor` side-channel, never through the ~1 MB Binder buffer. **The spine
   never changes when we add an addon.**
2. **Capability contracts (the modular plugins).** Each capability is a *versioned schema*
   (request/response/event types) + required scopes + settings schema + lifecycle hints
   (needs-FGS? bg-start-allowed? streaming?), shipped in a shared `hexis-bridge-contracts`
   library both sides compile against. New addon = new contract file; nothing else changes.
3. **Scopes + capability tokens (least privilege, enforced).** OAuth-style scopes
   (`capture.write`, `tasks.read`, `export.encrypted`, …). The core issues each addon a
   scoped, expiring, revocable token bound to its verified UID; every call is checked
   against token + scope + `Binder.getCallingUid`.
4. **Reliability (store-and-forward).** Durable outbox per direction with at-least-once
   delivery, idempotency keys, ACK, retry, backpressure, circuit-breaker. Survives
   core-dead, addon-dead, **DB-locked-in-background**, OEM battery-kills, reboot. (Built
   thin now; expanded when an event-pushing addon needs it.)
5. **Registry + auto-rendered control UI.** The core renders enable/scopes/settings/logs/
   rate-limits/kill for every addon *generically* from its declared manifest. Install an
   addon → its control panel appears with zero core code changes. Global controls: panic
   "disconnect all", per-addon kill switch, read-only mode, time-boxed grants.
6. **Consent handoff.** The addon renders its own permission + consent (deep-linked from
   the registry), returns a package-scoped revocable grant. Handles not-installed (offer
   install), cancel, and scope-increase-on-update (re-prompt).
7. **Trust (mutual, keyset-based).** Mutual signing-certificate pinning against a *keyset*
   — {Play key, F-Droid key, dev key} — not a single cert. Verified Binder UID +
   package-scoped token + anti-replay nonce; signature-level permission as defense-in-depth
   only, never the sole gate.
8. **Router.** `capability → provider` resolution with a user-selected default when
   multiple addons offer the same capability, plus fallback/priority.
9. **Observability + test harness.** In-process **loopback mock addon** (unit-test the bus
   with no second APK), contract tests pinning each schema across versions, a **Bridge
   Inspector** dev screen, and a redaction-aware audit log stored in the encrypted DB.

### 3.1 Complexity control

This is build-once infrastructure reused by every addon, but we build it **thin-but-shaped**:
Phase 0 ships layers 1, 2, 3, 5 plus a minimal slice of 4 and 7. Router (8), full
reliability (4), and third-party trust are layered in when the second/third addon needs
them; the interfaces are designed so those additions do not reshape what already exists.

---

## 4. Security model

- **Mutual authentication.** On bind, the core verifies the addon's signature against the
  keyset and its `Binder.getCallingUid`; the addon likewise verifies the core. A session
  nonce blocks replay.
- **Scoped tokens.** The core issues a scoped, expiring, revocable token per grant; the
  addon presents it on every call; the core re-checks token + scope + UID each time.
- **We fix the reference's biggest weakness.** Scrib's `org.opentranscribe.api` service is
  exported with **no permission guard and no caller verification** — any installed app can
  bind it and stream audio through. It pushes all security onto client discipline. Our
  addon (which *holds the mic*) verifies the caller before the mic ever opens.
- **Data minimization on the wire.** Only derived data crosses, tagged with sensitivity;
  no-persist flags instruct the addon to hold data in memory only. For Voice, *audio never
  crosses at all* — only text.

---

## 5. The first addon — Hexis Voice (STT)

### 5.1 Dual-path design

Two complementary paths, each on the protocol that fits it:

- **Path A — Live "listen" (Hexis Bridge, our addon only).** The addon holds `RECORD_AUDIO`,
  *captures the mic itself*, runs sherpa-onnx, and returns only text. This is the "listen"
  verb that no existing contract offers, and the home of our two differentiators (below).
  The core never touches the mic.
- **Path B — File transcription (core as an Open Transcribe client).** To transcribe an
  existing audio note/import, the core discovers any installed `org.opentranscribe.api`
  provider (Scrib, ours, others), shows a chooser, passes an audio `ParcelFileDescriptor`,
  and receives text — **with no mic permission in the core** and free ecosystem interop.
  whisper.cpp's higher batch accuracy suits this offline-file case.

Our Voice addon **also implements the Open Transcribe contract** for its file capability, so
it is a good ecosystem citizen, while exposing the bridge-native `voice.stt.listen` verb the
core drives for live capture.

### 5.2 Two differentiators

1. **Dynamic vocabulary biasing.** Before each utterance the core pushes the addon an
   *ephemeral, in-memory* hint list — the user's own project/tag/context names — as
   sherpa **per-stream hotwords** (`modified_beam_search` + `:score` boost). "Add to
   *Groceries*" is recognized because the core told the engine *Groceries* exists this
   session. Big accuracy win on the user's own words; privacy-clean (transient, opt-in, only
   labels the user speaks anyway; the addon persists none of it). **Scrib/whisper.cpp cannot
   do this** — whisper's `initial_prompt` is weak and unreliable.
2. **Analysis lives in the core** (see §7): the addon returns text; the core turns it into a
   structured, editable, commit-gated proposal using the existing `QuickAddParser`.

### 5.3 Engine choice

| Engine | Streaming | Biasing | Model size | License | Role |
|---|---|---|---|---|---|
| **sherpa-onnx** | yes | **yes** (runtime hotwords + KWS) | ~30 MB tiny → ~70 MB small | Apache-2.0 (verify per-model) | **Path A — live commands/dictation** |
| whisper.cpp | no (batch/windowed) | no | ~32 MB tiny-q5 → ~190 MB small | MIT | Path B — accurate file transcription (via Scrib or ours) |
| Vosk | yes | grammar restriction only (hard OOV cliff) | ~40 MB small | Apache-2.0 | fallback if sherpa can't hit the size/accuracy target |

**[locked]** sherpa-onnx for Path A (only embeddable engine that is Apache-2.0 *and* has a
real runtime biasing API). **[open]** exact baked model: smallest viable streaming sherpa
model within the ~30 MB budget, license verified in Phase 1; if no acceptable sherpa model
fits ~30 MB, fall back to Vosk small (~40 MB) or accept a slightly larger sherpa model — a
Phase 1 measurement, not a guess.

### 5.4 Microphone lifecycle (Android 14 compliant)

Push-to-talk, **user-initiated**: user taps the mic in a visible core surface → the addon
starts a `microphone` foreground service and calls `startForeground()` immediately, then
opens `AudioRecord` and streams partials → stop on release/silence. Because the trigger is
a visible user action, this satisfies Android 14's rule that a `microphone` FGS cannot be
*started from the background*. Always-on wake-word (via the ~3 MB keyword-spotter) is a
later, explicit opt-in with a privacy/battery warning — never the default.

### 5.5 Model & permission strategy (borrowed from Marmalade)

- **Bake one tiny model in-APK** for zero-download first run; the addon needs **no
  `INTERNET`** in its default form.
- Larger/multilingual models come via **SAF file-import** (no storage permission) or an
  **opt-in `INTERNET`** fetch with **SHA-256 verification + atomic install** into the
  addon's `filesDir`. Any such network permission is addon-only and disclosed in the
  registry.
- Engine hygiene: mmap'd model tables + reusable direct `ByteBuffer`s; arm64-v8a +
  armeabi-v7a with the ARMv7 mmap-SIGBUS `readBytes` workaround; 16 KB page alignment;
  reproducible-build flags.
- **Verify each bundled model's license individually** (sherpa model licenses vary).

### 5.6 Footprint & permissions (addon only)

- Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`. No
  `INTERNET` in the baked-model build. **Core permission set unchanged.**
- Size: ~30 MB tiny model + ~19 MB sherpa/onnxruntime native ≈ ~50 MB addon (grows if the
  user opts into larger models).

---

## 6. The `voice.stt` bridge contract (v1)

Discovered via the spine's provider action + `<meta-data>` capabilities (enumerated with a
`<queries>` entry). Shape is informed by Open Transcribe's good design (FD-not-path,
capability negotiation, cumulative-not-delta progress, exactly-one-terminal-callback,
`oneway` callbacks + binder-death cleanup, append-only evolution) plus our additions
(caller verification, the listen verb, hotword input).

- `getCapabilities() → Capabilities{ engineId, engineVersion, supportedLanguages[],
  streaming, biasing, modelReady }`
- **Listen (Path A, bridge-native):** `startListening(ListenRequest, callback) → Session`
  - `ListenRequest{ languageHint?, mode: command|dictation, hotwords: [{text, score}],
    endpointing }` — the addon captures the mic; **only text crosses back**.
  - `callback.onPartial(cumulativeText)` · `onFinal(text, confidence, tokenConfidences?)` ·
    `onError(type)` — exactly one of onFinal/onError terminates.
  - `Session.stop()` (endpoint now) · `cancel()`.
- **Transcribe file (Path B, Open Transcribe interop):** the addon also implements
  `org.opentranscribe.api` (`transcribe(pfd, request, callback)` / `openStream(...)`); the
  core is a *client* of whichever provider the user picks.
- **Evolution:** append-only fields; new callback methods are safe because callbacks are
  `oneway`; new service methods must be capability-gated.
- **Scopes:** `voice.stt.listen` grants *invocation + receipt of ephemeral hotwords* only —
  **no data read/write scope.** Revocable and kill-switchable.

---

## 7. Analysis & plan loop (the core-side brain)

The addon is a dumb, privileged ear. All analysis happens in the core, where the data and
the key live.

1. **Transcript → intent + slots.** A keyword/grammar intent layer (Dicio/Rhasspy pattern)
   classifies add-task / add-event / start-timer / note / **query**, strips the trigger, and
   feeds the remainder to the existing `QuickAddParser` (dates/times/recurrence/priority).
   Project/tag tokens resolve against the user's own lists via fuzzy match — backstopped by
   the STT biasing that already made those names recognizable.
2. **Contextual resolution.** "Move *it* to tomorrow" resolves "it" against on-screen/
   last-created context the core holds; the addon only heard the words.
3. **The plan card = analysis made visible, commit gated.** e.g. *Create task · "Call mom"
   · tomorrow 17:00 · Personal* — each parsed field editable, low-confidence slots flagged
   (using token confidence), **nothing written until confirmed** (core is sole writer),
   with undo. Control (core setting): confirm-always / confirm-when-unsure /
   auto-commit-with-undo.
4. **Closed-loop spoken analytics.** A voice *query* is answered from the Tier 1–2
   aggregation accessors ("what's due today?") and read back via the platform
   `TextToSpeech` API — fully offline, no permission, no bundled voice.
5. **Proper logs.** Every capture writes a redaction-aware audit entry (intent + fields
   touched, verbosity-controlled) to the encrypted DB.

---

## 8. Prior art & what we borrow

- **Scrib** (`org.scrib.transcriber`, GPL-3.0, whisper.cpp) —
  <https://github.com/23rd/Scrib> (see `CONTRACT.md`). Borrow: the vendor-neutral
  `org.opentranscribe.api` contract shape (FD-not-path, chooser, capability gating,
  cumulative progress, append-only evolution), off-heap direct-`ByteBuffer` PCM, runtime
  CPU-feature `.so` selection, energy-VAD segmentation with prompt carry-over. **Fix:** its
  unguarded, unverified exported service.
- **Marmalade** (`app.marmalade.tts`, MIT source / GPL APK, ONNX Runtime) —
  <https://github.com/maxwhipw/marmalade-tts-android>. Borrow: bake-a-small-model +
  SHA-256-verified atomic opt-in downloads, mmap tables, ABI/alignment/reproducible-build
  hygiene. Confirms: **TTS = platform `TextToSpeechService`**, don't bundle a GPL voice.
- **sherpa-onnx** (Apache-2.0) — <https://github.com/k2-fsa/sherpa-onnx>; hotwords
  <https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html>; KWS
  <https://k2-fsa.github.io/sherpa/onnx/kws/index.html>. The engine + the biasing mechanism.
- **Dicio** (<https://github.com/DicioTeam/dicio-android>) and **Rhasspy**
  (<https://rhasspy.readthedocs.io/>) — offline, no-LLM intent template + slot-filling
  patterns for the core's analysis layer.
- **Android 14 FGS** —
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
  (mic FGS cannot start from background; user-initiated push-to-talk is the compliant path).

---

## 9. Roadmap (tracks the task list)

- **Phase 0 — Bridge SDK spine (thin slice).** `hexis-bridge-contracts` module (envelope +
  scope vocabulary + `voice.stt` types), `IHexisBridge` AIDL spine, keyset pinning + UID
  verify + scoped token, core-side Bridge Registry (auto-rendered settings + audit log +
  kill switch). No new core permissions.
- **Phase 1 — Hexis Voice addon (live listen).** sherpa-onnx + baked tiny model; user-
  initiated mic FGS; `voice.stt.listen` + Open Transcribe; consent activity; no settings UI;
  caller verification; per-model license verified.
- **Phase 2 — Core push-to-talk UI + analysis/plan loop.** Mic action, live partials, intent
  + `QuickAddParser` + fuzzy resolve, the plan card, commit + undo.
- **Phase 3 — File transcription via Open Transcribe client.** Discovery + chooser + FD;
  interop with Scrib and ours; no core mic permission.
- **Phase 4 — Spoken analytics + platform TTS out.** Voice queries → aggregation → TTS;
  biasing refinement; opt-in multilingual downloads; optional privacy-warned wake-word.
- **Phase 5 — Hardening + release.** Contract tests, loopback mock addon, Bridge Inspector,
  rate limits, revocation, signed core + addon APKs.

---

## 10. Open questions & risks

- **[open]** Exact baked STT model within the ~30 MB budget (§5.3) — a Phase 1 measurement.
- **[open]** Store-and-forward reliability depth for Voice is minimal (push-to-talk needs
  little); the durable outbox matters more for a future event-pushing addon (geofence).
- **Risk:** OEM battery managers killing the addon mid-capture — mitigated because capture
  is short and user-initiated; document the "disable battery optimization" path if needed.
- **Risk:** background writes to the encrypted DB when the key is unavailable — not a Voice
  concern (capture is foreground), but a first-class constraint for later background addons.
- **Distribution:** addon signed with the Hexis keyset; core pins the keyset (Play +
  F-Droid + dev keys), never a single cert.

---

## 11. Decision log

| Date | Decision |
|---|---|
| 2026-09-27 | First addon = Voice (STT); TTS via platform API, not an addon. |
| 2026-09-27 | Baked-in first-run model = tiny (~30 MB target); larger models opt-in. |
| 2026-09-27 | Serialization = kotlinx.serialization for envelope + contracts. |
| 2026-09-27 | Dual-path: live-listen (our addon) + file via Open Transcribe client. |
| 2026-09-27 | Build order: this doc → Phase 0 spine → Phase 1+ one at a time. |
