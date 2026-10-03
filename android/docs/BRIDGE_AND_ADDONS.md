# Hexis — Bridge & Addons Architecture

How Hexis stays a **zero-permission, fully-offline** core while still offering privileged features
(microphone, and later: network sync, location, …) through **separate, optional, installable addon
APKs** that talk to the core over a small, hardened IPC **bridge**.

This is the reference for anyone extending the system. It covers the design principles, the bridge
protocol, the voice addon, how the core consumes it, the permission model, the data flows, a recipe for
building a new addon, build/release, testing, and the key decisions behind it all.

> See also: [`SECURITY.md`](SECURITY.md) (threat model), [`REPRODUCIBLE_BUILD.md`](REPRODUCIBLE_BUILD.md),
> [`ADDON_ROADMAP.md`](ADDON_ROADMAP.md) (future addon ideas).

---

## 1. Principles (the two invariants)

1. **The core holds zero "forbidden" permissions.** `:app` declares no `INTERNET`, no location, no
   `RECORD_AUDIO`, no broad storage/media, no foreground-service. This is verifiable from the merged
   manifest and is the product's defining promise. Any feature that *needs* a dangerous permission is
   pushed out into an addon that holds it alone.
2. **Every addon is optional and invisible when absent.** With no addon installed, the core is fully
   functional and shows **no addon UI at all**. All addon-powered affordances key off a single runtime
   gate (for voice: `AppViewModel.voiceAvailable`). Installing + connecting an addon makes the feature
   appear and behave like a native part of the app.

A corollary: **only derived data crosses the bridge.** The microphone and the raw audio never leave the
voice addon — only recognized **text** is returned. This is both the privacy story and a Binder-size win
(see §2.7).

### Module layout & dependency rule

```
:app           (core)          → depends on :bridge only
:bridge        (shared SDK)     → pure Kotlin + AIDL, no app/addon deps
:voice-addon   (addon APK)      → depends on :bridge, :whisper
:whisper       (native library) → vendored whisper.cpp, built from source
```

**`:app` must never depend on an addon module.** It discovers addons at runtime via `PackageManager`
and speaks to them only through `:bridge`. The one place the core names an addon is a loosely-coupled
intent-action *string* (the model-import deep link), guarded by addon presence.

---

## 2. The bridge (`:bridge`)

A tiny, transport-agnostic SDK shared verbatim by the core and every addon. Its job: let two
separately-installed, **same-signed** apps exchange typed, versioned messages safely.

### 2.1 The fixed transport spine (AIDL)

`IHexisBridge.aidl` — four methods, and they **never change** when a capability is added:

| Method | Purpose |
| --- | --- |
| `byte[] handshake(byte[] hello)` | Bind-time mutual introduction (`HandshakeHello` → `HandshakeResult`). |
| `byte[] invoke(byte[] request)` | One-shot request/response (`RequestEnvelope` → `ResponseEnvelope`). |
| `byte[] openStream(byte[] request, IHexisBridgeCallback cb)` | Start a streaming/long-lived session; returns a `SessionHandle`. |
| `void controlSession(byte[] control)` | STOP / CANCEL a live session (`SessionControl`). |

`IHexisBridgeCallback.aidl` — a **oneway** result channel: zero or more `onEvent(...)`, then exactly one
terminal `onResult(...)` **xor** `onError(...)`. Oneway means the provider never blocks on the consumer,
and new callback methods can be appended without breaking older peers.

Every payload is a **serialized envelope** (UTF-8 JSON bytes), never a bespoke Parcelable. That is what
keeps the spine fixed: a new capability is new *data*, not a new interface.

### 2.2 Envelopes & codec (`Envelope.kt`, `BridgeCodec.kt`)

- `EnvelopeHeader { capabilityId, method, protocolVersion, contractVersion, token?, nonce?,
  idempotencyKey?, sensitivity }` — the fixed header on every request; the capability-specific payload
  rides as a JSON string in `RequestEnvelope.payloadJson`.
- `Sensitivity { NORMAL, SENSITIVE, NO_PERSIST }` — `NO_PERSIST` tells a peer to hold data in memory
  only (used for the ephemeral vocabulary hints the core sends the voice addon).
- `BridgeCodec.json` is the single `Json` config used by both sides: `ignoreUnknownKeys = true`,
  `encodeDefaults = true`, `explicitNulls = false`. This is the **append-only evolution rule**: a newer
  peer may add fields; an older peer ignores them instead of failing.

### 2.3 Capabilities & scopes (`BridgeCatalog.kt`)

A **capability** is a versioned, self-describing JSON data contract carried over the spine — identified
by a string id, never its own AIDL interface. Contracts live in `:bridge` so both sides share them.

- `Capabilities.VOICE_STT = "voice.stt"` (today the only one).
- `BridgeScopes` — OAuth-style least-privilege scopes enforced per call (e.g. `voice.stt.listen`). The
  live STT addon holds only `voice.stt.listen` and gets no read/write access to core data.

The `voice.stt` contract itself is `bridge/voice/VoiceStt.kt` (`ListenRequest`, `Hotword`, `SttPartial`,
`SttFinal`, `SttCapabilities`, `SttMode`, error types).

### 2.4 Dispatcher & auth gate (`BridgeDispatcher.kt`, `CapabilityHandler.kt`)

`BridgeDispatcher` is **pure Kotlin and fully unit-testable without Android**. The AIDL service is a thin
adapter that resolves the caller, then calls in here. Every request passes the **same gate, in one
place**, before any handler runs:

```
gate(header, caller):
  1. requireSignatureTrust && !caller.signatureTrusted  → UNAUTHENTICATED
  2. capability unknown                                  → UNSUPPORTED
  3. token fails scope for method (TokenAuthority.verify)→ UNAUTHORIZED / UNAUTHENTICATED
```

A capability is a `CapabilityHandler { capabilityId, requiredScope(method), invoke, openStream,
control }`. Adding a capability means adding a handler; the dispatcher and spine are untouched.

### 2.5 Trust & tokens (`security/`)

- `SignatureVerifier` — resolves `Binder.getCallingUid()` → owning package → the set of SHA-256 signing
  certificate digests, and checks them against a **pinned keyset** (a keyset, not a single cert, so
  release/debug/F-Droid builds of a first-party peer all qualify while an impostor is rejected). A shared
  UID is treated as untrusted.
- `BridgeTrust` — the **single source of truth**: `HEXIS_KEYSET` (the first-party signing digest) and
  `requireSignatureTrust(context)` (= release enforces; a debuggable build relaxes so a debug-signed peer
  can be exercised). Both the core and the addon read from here, so the keyset is defined once.
- `Auth.kt` — `VerifiedCaller`, `GrantToken` (value, subjectPackage, scopes, issuedAt, expiresAt),
  `TokenAuthority` (mint / verify / revoke / revokeAll). `InMemoryTokenAuthority` is the default/test
  impl; the voice addon uses a persistent one (§3.5).

Tokens are **scoped, revocable, optionally-expiring, 256-bit opaque**. The granting side (the addon)
mints one during consent; the core presents it as `header.token` on every call; the addon re-verifies it
on every call.

### 2.6 Discovery, binding, consent (client + provider)

- **Discovery** (`client/BridgeDiscovery.kt`) — `queryIntentServices` on `BridgeProtocol.PROVIDER_ACTION`
  with `GET_META_DATA`, reading `META_CAPABILITIES` (comma-separated ids) and `META_PROTOCOL_VERSION`.
  Returns `DiscoveredProvider(packageName, className, capabilities, protocolVersion, trusted)`. Callers
  present a chooser and **never auto-bind** — binding grants a satellite access.
- **Package visibility** — on Android 11+ the core must declare `<queries>` for the provider/consent/
  import/transcribe actions, or discovery is silently filtered. The core does (see its manifest).
- **Binding** (`client/BridgeConnection.kt`) — binds with
  `BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES`. The capability flag lets the **foreground core lend its
  while-in-use microphone capability** to the bound addon, so a permission-free core can let its voice
  addon capture during push-to-talk without the addon winning its own mic foreground-service race (this
  fixed the early flaky-mic/crash bugs). Binder calls run off the main thread.
- **Provider** (`provider/BridgeProviderService.kt`) — the abstract base an addon extends. It resolves &
  verifies the caller (UID → package → pinned keyset) **before** handing anything to the dispatcher. This
  is the caller verification that reference transcribers omit. Subclass supplies the `dispatcher` and the
  `pinnedCallerKeyset`.
- **Consent** (`BridgeConsent.kt`) — the handoff contract. The core launches the addon's consent activity
  by `ACTION` (pinned to the addon via `setPackage`), passing `EXTRA_CORE_PACKAGE` + `EXTRA_CAPABILITY`;
  on approval the activity returns a scoped grant token in `EXTRA_TOKEN`. The addon renders its own
  permission prompt — the core never holds the permission.

### 2.7 Large payloads

The Binder transaction buffer (~1 MB, shared per process) means raw audio or long blobs must never be
passed as call arguments. The bridge design sidesteps this: **only short text crosses it** (audio stays
in the addon). The one place a large payload moves — file transcription — uses a dup'd
`ParcelFileDescriptor`, not a byte array (§4.4).

---

## 3. The voice addon (`:voice-addon` + `:whisper`)

A separate APK, **no launcher icon**, reached only via the bridge and the core's deep links. It is the
only holder of the microphone.

### 3.1 Manifest & permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | The one dangerous permission the core must never hold. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Android-14-compliant mic capture during push-to-talk. |
| `POST_NOTIFICATIONS` | The capture foreground-service notification. |
| **no `INTERNET`** | The addon cannot phone home; models are imported locally, never downloaded. |

It **defines** a signature-level permission `com.wkhan.hexis.voice.permission.BIND_BRIDGE` and requires it
on the bridge service, so the OS blocks a mismatched-signature binder before the in-code check even runs
(the core declares the matching `<uses-permission>`). Defense in depth on top of `BridgeProviderService`'s
runtime keyset check.

### 3.2 Components

| Component | Role |
| --- | --- |
| `bridge/VoiceBridgeService` | The provider. Holds the engine; frees the model on `onTrimMemory(BACKGROUND+)` / `onDestroy`; uses `BridgeTrust`. |
| `consent/ConsentActivity` | Verifies the caller's keyset **first**, shows an explicit prompt naming the core + scope, requests `RECORD_AUDIO`, mints the scoped token. Translucent (no chrome). |
| `model/ModelImportActivity` | SAF import of a GGML `.bin` model into the addon's private storage (`filesDir/stt-model/`). Order-independent multi-part import; friendly message on unreadable storage. **No network.** |
| `capture/VoiceCaptureService` | The mic foreground service (typed `microphone`), with an ongoing notification + a working **Stop** action. Crash-proof: if the OS refuses the FGS start it degrades to the lent capability instead of crashing. |
| `ime/VoiceImeService` | The **dictation keyboard** — an on-device IME that types transcribed speech into any field in any app (§4.5). |

### 3.3 The engine (`engine/`)

- `SttEngine` / `SttListener` — the engine-agnostic interface the bridge handler talks to. Swapping the
  engine never touches the bridge, the service, or the core.
- `WhisperSttEngine` — the shipping engine: **record-then-transcribe** (not streaming).
  - Tapping the mic starts recording immediately; the model is loaded on a **parallel warm thread** so
    "open" is instant.
  - Recording runs until the core calls `stop` (manual stop; never cut off on a pause). A 60 s safety cap.
  - On stop, **VAD** (`EnergyVad`) trims silence and skips an essentially-silent clip (fewer whisper
    hallucinations), then the speech span is transcribed once.
  - **Vocabulary biasing**: the user's own list/tag/context/activity names are passed as whisper's
    `initial_prompt` so domain words ("add to Groceries", "start Deep Work") are recognized. Sent
    `NO_PERSIST`; the addon never keeps them.
  - **Model lifecycle**: kept warm 45 s after a capture, then evicted; also freed on memory pressure /
    service teardown — so the addon never sits on ~100–300 MB of native model RAM.
  - Session ids are honoured in `stop`/`cancel` so a late stop can't cut a newer utterance short.
- `EnergyVad` — a dependency-free RMS voice-activity detector (20 ms frames, noise-floor + relative
  threshold, generous padding; conservative — keeps the whole clip when energy is diffuse).
- `ModelStore` — lists every imported `.bin` and **auto-selects the better tier** when several are present
  (`base` > `small` > `medium` > else), so importing a more accurate model is picked up with no setting to
  flip. Exposes `modelName` for the core to display.
- `EchoSttEngine` — a canned stub in **test** sources only, for exercising the pipeline offline.

### 3.4 The `:whisper` module

Vendored **whisper.cpp v1.7.5, built from source** (CMake + NDK, `arm64-v8a`, CPU-only; an fp16-optimized
library is loaded when the CPU supports it). GGML-quantized models (`q5_1`): ~31 MB `tiny.en`, ~57 MB
`base.en`.

- `WhisperContext` — thin Kotlin wrapper over the JNI; one context is used from a single-thread
  dispatcher (whisper.cpp requirement). `createContextFromFile`, `transcribeData(data, prompt)` /
  `transcribeBlocking`, `release` / `releaseBlocking`.
- `WhisperCpuConfig` — picks the thread count from the device's performance-core count.
- The JNI (`jni.c`) forces English + no-timestamps and applies the `initial_prompt` bias.

### 3.5 Token persistence

`PersistentTokenAuthority` stores grants in the addon's private `SharedPreferences`, so a token minted at
consent survives the addon process being reclaimed before the first call (an in-memory store lost it,
which surfaced as "invalid or missing token"). `VoiceAddon.tokenAuthority(ctx)` is the process-wide
singleton.

---

## 4. How the core consumes it (`:app`)

The core speaks only `:bridge`. Everything addon-related lives behind a single gate.

### 4.1 The single gate

```kotlin
val voiceAvailable: StateFlow<Boolean> =
    bridgeState.map { it.enabled && it.grantedVoicePackage != null }  // bridge on + a granted addon
```

Every addon-powered affordance keys off this: the mic FAB, note dictation, the widget popup, the QS tile,
the Settings actions. With no addon, `voiceAvailable` is false and none of them render — the core shows no
voice UI.

### 4.2 Core-side classes

| Class (`com.wkhan.hexis…`) | Role |
| --- | --- |
| `addon/BridgeRegistry` | Discovery; grant state (key-value rows in the shared `settings` table — owns no schema); redaction-aware audit log; kill switch; enabled flag. Uses `BridgeTrust`. |
| `addon/VoiceCaptureController` | Drives a live `voice.stt` `openStream` session: binds the granted addon, opens the stream with `NO_PERSIST` hotwords, surfaces partial/final/error. |
| `addon/VoiceCapabilitiesClient` | One-shot `getCapabilities` read (needs no token) → engine id/version + active model name, shown in Settings. |
| `addon/OpenTranscribeClient` | The **second** IPC path (§4.4): file transcription over the vendor-neutral `org.opentranscribe.api` AIDL. |
| `domain/voice/VoiceCommandAnalyzer` | Pure **router**: transcript → `VoiceIntent` + stripped `payloadText`. No parsing (the review sheet parses once). |
| `domain/voice/SpokenAnswers` | Pure composer of the daily briefing + spoken answers from already-computed counts. No LLM. |
| `voice/TtsSpeaker` | Platform Text-To-Speech wrapper for spoken output (no permission). |

### 4.3 The capture state machine (in `AppViewModel`)

`voiceUi : StateFlow<VoiceCaptureUi>` with `VoiceStatus { IDLE, LISTENING, TRANSCRIBING, REVIEW, ERROR }`.

- `startVoiceCapture()` — COMMAND mode → the intent-router review sheet.
- `startVoiceDictation(onText)` — DICTATE mode → delivers the transcript straight to a sink (note editor),
  skipping the review.
- On final transcript (COMMAND): `VoiceCommandAnalyzer.analyze` → REVIEW with an **editable transcript**
  + the routed actions, best guess first. Nothing is written until the user picks one.
- Router actions: `commitVoiceCapture` (task), `commitVoiceNote`, `commitVoiceStartTimer`,
  `commitVoiceStopTimer`, `commitVoiceSearch` / `commitVoiceCommand` (→ `voiceNav` for the host),
  `commitVoiceQuery` (→ `SpokenAnswers` + TTS). `requestDailyBriefing()` reads the day aloud;
  `voiceAnswer` shows it on screen too.

### 4.4 The second IPC path — Open Transcribe (file transcription)

File transcription does **not** use the Hexis bridge. It uses the vendor-neutral
`org.opentranscribe.api.ITranscriptionService` AIDL so the core can also drive third-party transcribers
(e.g. Scrib). Trade-off, **documented deliberately**: this path uses `BIND_AUTO_CREATE` with **no
signature pin and no token** (it is user-initiated through a chooser, and audio crosses only as a dup'd
`ParcelFileDescriptor`). It is a weaker trust model than the bridge; a future improvement is to warn when
the chosen transcriber isn't signature-trusted.

### 4.5 UI / capture entry points

| Surface | Gated on addon? | Notes |
| --- | --- | --- |
| Mic FAB (any tab) | yes (`voiceAvailable`) | Global push-to-talk → review sheet. |
| `VoiceCaptureSheet` / `VoiceCapturePanel` | yes | The push-to-talk surface; reused by the widget popup. |
| `widget/QuickVoiceActivity` | yes (shows install prompt if not) | Translucent widget popup → task/note/timer land locally; search/command hand off to the app. |
| Note editor "Dictate (voice)" | reactive | Uses the addon when connected, else the **platform** `RecognizerIntent` fallback. |
| `tiles/VoiceTileService` (QS tile) | via popup | One-tap capture from the shade / lock screen. |
| "Dictate" launcher shortcut (`hexis://voice`) | yes (else → Settings) | Long-press app icon. |
| Settings → Addon bridges | — | Connect/consent, install model, try capture, transcribe a file, engine/model info, daily briefing, kill switch, audit log. |
| `capture/QuickReplyNotification` + `CaptureReplyReceiver` | **no addon, no mic perm** | Ongoing notification; dictate/type with the **system keyboard**; added via `repository.quickCaptureTask`. Opt-in in Settings. |
| `ime/VoiceImeService` (dictation keyboard) | addon-owned | System-wide "dictate into any field"; enabled in system keyboard settings. |

---

## 5. Permission matrix

| | Core (`:app`) | Voice addon |
| --- | --- | --- |
| `INTERNET` | ❌ never | ❌ never |
| `RECORD_AUDIO` | ❌ | ✅ (the whole point) |
| `FOREGROUND_SERVICE(_MICROPHONE)` | ❌ | ✅ |
| Location / storage / media | ❌ | ❌ |
| `POST_NOTIFICATIONS`, exact alarms, DND, boot, vibrate, biometric, full-screen-intent | ✅ (local reminders) | `POST_NOTIFICATIONS` only |
| Custom `…voice.permission.BIND_BRIDGE` | declares `<uses-permission>` | defines it (signature) |

Verify the core:
```bash
aapt dump permissions app-release.apk | grep uses-permission   # no INTERNET/RECORD_AUDIO/LOCATION/STORAGE/FGS
```

---

## 6. Data flows

**Consent / grant (one-time):**
```
Core Settings → startActivityForResult(ACTION=…CONSENT, setPackage=addon, EXTRA_CORE_PACKAGE)
  → addon ConsentActivity: verify caller keyset → prompt → request RECORD_AUDIO → mint scoped token
  → RESULT_OK(EXTRA_TOKEN) → core BridgeRegistry.grant() stores pkg+token in settings table
```

**Push-to-talk capture:**
```
mic FAB → AppViewModel.startVoiceCapture()
  → VoiceCaptureController.start(provider, token, hotwords[NO_PERSIST], COMMAND)
  → BridgeConnection.connect(BIND_INCLUDE_CAPABILITIES) → handshake
  → openStream(ListenRequest) → addon VoiceSttHandler → WhisperSttEngine
       record (mic FGS) → stop → VAD trim → whisper transcribe(initial_prompt)
  → onResult(SttFinal.text) → VoiceCommandAnalyzer.analyze → REVIEW sheet → user picks an action
```

**Quick-reply notification (no addon, no mic perm):**
```
notification "Add a task" → system keyboard inline reply (its own dictation)
  → CaptureReplyReceiver (RemoteInput) → repository.quickCaptureTask(text) → re-post notification
```

---

## 7. Recipe — add a new addon / capability

1. **Contract in `:bridge`**: add an id to `Capabilities`, scope(s) to `BridgeScopes`, and a versioned
   data contract (`…/<name>/<Name>.kt`, `@Serializable` request/response/event types).
2. **Addon module**: new APK depending on `:bridge` (+ any native lib). Implement a `CapabilityHandler`;
   extend `BridgeProviderService` supplying a `BridgeDispatcher(handlers, tokenAuthority,
   BridgeTrust.requireSignatureTrust(ctx))` and `pinnedCallerKeyset = BridgeTrust.HEXIS_KEYSET`.
3. **Consent + permission**: a `ConsentActivity` that verifies the caller (`SignatureVerifier` +
   `BridgeTrust`) then mints the scope; declare the provider service `exported=true` with a
   `signature` permission + the `PROVIDER` intent filter + `META_CAPABILITIES`/`META_PROTOCOL_VERSION`;
   persist tokens (`PersistentTokenAuthority`). **Hold the dangerous permission here, never in the core.**
4. **Core**: add the scope; add the action(s) to the core's `<queries>`; drive the capability from a
   controller (mirror `VoiceCaptureController`); store the grant (extend `BridgeRegistry`'s
   `KNOWN_CAPABILITIES`); gate every new UI affordance on "grant present".
5. **Sign both with the same keyset** (§8) and keep the core at **zero forbidden permissions**.

---

## 8. Build, signing, release

- Modules are registered in `settings.gradle.kts`. Build:
  `./gradlew :app:assembleRelease :voice-addon:assembleRelease`.
- **Both APKs must be signed with the same keyset.** The bridge trust check *and* the `BIND_BRIDGE`
  signature permission both require it; they also must match for side-by-side install.
- Signing is driven by `android/keystore.properties` → the keystore. **`keystore.properties` and the
  keystore are gitignored and never committed.** Credentials live in the build environment only.
- `detekt` gates `:app` (config under `app/config/`). The addon's native build needs the NDK + CMake.
- Install order is independent (core-first or addon-first); connect the addon in Settings → Addon bridges.
- Ship both APKs; verify the core still declares no forbidden permission (§5).

---

## 9. Testing

| Where | Covers |
| --- | --- |
| `bridge/src/test` | `BridgeCodecTest`, `BridgeDispatcherTest` (auth gate, routing), `TokenAuthorityTest`, `LoopbackVoiceHandler`. |
| `voice-addon/src/test` | `VoiceSttHandlerTest` wired to `EchoSttEngine` through the dispatcher — offline, no Android, no second APK. |
| `app/src/test` | `VoiceCommandAnalyzerTest` (routing + trigger stripping), `SpokenAnswersTest` (briefing/answers). |

The dispatcher being pure Kotlin is deliberate: the entire authorization path is tested without a device.

---

## 10. Key decisions (why it is the way it is)

| Decision | Rationale |
| --- | --- |
| **Text-only bridge; audio stays in the addon.** | Privacy (the core never touches audio) + dodges the 1 MB Binder limit. |
| **Fixed AIDL spine + versioned JSON capability contracts.** | New capabilities are data, not new interfaces — the spine never churns; append-only fields keep old peers working. |
| **One central auth gate in a pure-Kotlin dispatcher.** | Authorization lives in one testable place for all capabilities. |
| **Signing *keyset* pin, not a single cert.** | Release/debug/F-Droid builds all qualify; impostors don't. |
| **Scoped, revocable tokens + per-call re-verify.** | Least privilege; the core can revoke instantly (kill switch). |
| **Signature `BIND_BRIDGE` permission *and* in-code check.** | Defense in depth — the OS blocks mismatched binders before our code runs. |
| **`BIND_INCLUDE_CAPABILITIES`.** | Lets the permission-free foreground core lend its mic capability so the addon captures reliably. |
| **Record-then-transcribe + VAD (not live streaming).** | Batch is ~5× faster than re-running whisper on a growing buffer; VAD trims silence and curbs hallucinations. |
| **whisper.cpp from source + GGML `q5_1`.** | Small footprint (31–57 MB) at near-lossless accuracy; no third-party native dependency; fully offline. |
| **Warm-then-evict model lifecycle.** | Instant re-use without permanently holding ~100–300 MB of native RAM. |
| **Two IPC paths (bridge vs Open Transcribe).** | The bridge is tightly pinned for live mic; file transcription stays vendor-neutral to interop with other transcribers (weaker, user-initiated trust — documented). |
| **Self-owned capture surfaces (tile, shortcut, notification, IME).** | Google Assistant / App Actions are being wound down; self-owned surfaces are future-proof and keep the no-Google posture. |
| **On-device rule-based intelligence now; LLM deferred.** | Covers the real use cases offline with a tiny footprint; a heavy LLM runtime warrants its own decision and its own addon. |
