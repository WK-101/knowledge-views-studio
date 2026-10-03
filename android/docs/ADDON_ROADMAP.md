# Hexis — Addon Roadmap

Where the addon ecosystem goes next. The pattern is proven by the voice addon: **the core holds zero
dangerous permissions; each addon is a separate, optional, same-signed APK that holds exactly one
privileged capability and returns only derived data over the bridge** (see
[`BRIDGE_AND_ADDONS.md`](BRIDGE_AND_ADDONS.md)). Every idea below is filtered through that lens — "which
forbidden permission does it isolate, and what minimal derived data crosses the bridge?"

> **In planning:** a **Web Bridge** addon (browser access to Hexis over LAN, PlainApp-style but with the
> network edge isolated from the data+key) has a full analysis + phased plan in
> [`WEB_BRIDGE_PLAN.md`](WEB_BRIDGE_PLAN.md).

## The design test for any new addon

1. It isolates a permission the core must never hold (network, location, calendar, storage/camera, a heavy
   runtime, …).
2. Only derived/minimal data crosses the bridge — never the raw privileged stream.
3. The core is 100% functional without it, and shows none of its UI when it's absent (one runtime gate).
4. It is discovered at runtime, consent-gated, signature-pinned, and revocable.

## Candidates

| Addon | Capability | Permission it isolates | Derived data over the bridge | Core primitives it reuses | Value | Effort |
| --- | --- | --- | --- | --- | --- | --- |
| **Sync / backup (E2EE)** | `sync.e2ee` | `INTERNET` | Opaque **ciphertext** blobs only (keys & plaintext never leave the core) | Encrypted export, portable crypto, account-free merge sync | ★★★★★ | ●●●◌ |
| **Location / geofence** | `place.geofence` | `ACCESS_FINE_LOCATION` + background location | "Arrived at / left `<place name>`" events — never coordinates | Permission-free `arrive:` place reminders, reminders engine, time-tracking | ★★★★★ | ●●◌◌ |
| **Calendar bridge** | `calendar.sync` | `READ/WRITE_CALENDAR` | Event rows (title/time) in/out | Dedicated calendar, ICS import/export, events engine | ★★★★◌ | ●●◌◌ |
| **Intelligence (on-device LLM)** | `nlu.structured` | Heavy model runtime (no network) | Structured JSON (parsed items, summaries, action items) | Quick-add grammar, notes, voice router, review sheet | ★★★★◌ | ●●●● |
| **Attachments / scan (OCR)** | `media.import` | Camera / photos | Extracted text + a stored file URI | Notes/tasks, SAF plumbing | ★★★◌◌ | ●●●◌ |
| **Health / activity** | `health.read` | Health Connect / activity recognition | Daily totals (steps/sleep/workouts) | Habits, time-tracking, analytics | ★★★◌◌ | ●●◌◌ |
| **Contacts** | `contacts.read` | `READ_CONTACTS` | A resolved display name/id for `@mention` | Tags/contexts, waiting-on, sharing | ★★◌◌◌ | ●◌◌◌ |
| **Automation bridge** | `automation` | — (intent surface) | Action invocations in/out (Tasker/MacroDroid) | Quick-add, timers, routines | ★★★◌◌ | ●●◌◌ |

## Recommendation

**Build the Sync / backup (E2EE) addon next.** Then **Location / geofence**.

### Why sync first

- **It closes the biggest gap of a local-first app.** "No account, no network" is the core's defining
  promise *and* its biggest friction: no second device, no painless backup resilience. Sync is the single
  most-requested thing such apps lack.
- **It is the purest proof of the whole thesis.** "The core physically cannot touch the network, yet you
  get multi-device sync and off-device backup" — the addon holds `INTERNET`, the core stays at zero. That
  is a genuinely differentiated, trust-building story no mainstream competitor tells.
- **The hard parts already exist.** The core already has encrypted export, portable crypto, and
  account-free **merge sync** (keep-newest). The addon is mostly *transport + scheduling*: the core hands
  it an encrypted blob and a version vector; the addon ships ciphertext to a user-chosen backend and pulls
  peers' ciphertext back; the core merges. The addon never sees the key or the plaintext → **end-to-end
  encrypted by construction.**
- **Backend-agnostic, user-owned.** WebDAV / Nextcloud / S3-compatible / a plain folder on a home server —
  and, out of the box, **LAN-only device-to-device sync** (Wi-Fi Direct / same-subnet) for people who want
  *no* server at all. The user picks; Hexis never runs one.

A sensible phasing: (1) **encrypted cloud backup** to a user-chosen remote on a cadence (smallest useful
slice, pure push); (2) **two-device pull + merge**; (3) **conflict UX + LAN peer discovery**.

### Why location is the strong runner-up

Place-based reminders ("remind me at the office", "when I get home") and auto time-tracking by place are
flagship productivity features the core *literally cannot do* (zero location permission). It cleanly
upgrades the existing permission-free `arrive:` NFC/QR reminders to real geofencing, and the privacy model
is tidy: **only "arrived at `<place>`" events cross the bridge — never coordinates.** Lower effort than
sync, and arguably more delightful; the main reason it ranks second is that sync unlocks more and is more
foundational.

## Out-of-the-box combinations (once two addons exist)

- **Scene triggers** = location + the existing NFC/QR + time: "at desk → start Deep Work + silence
  notifications; leave the office → stop all timers and close the day." The core already has routines and
  NFC deep links; location makes them automatic.
- **Presence-aware briefing** = location + the spoken briefing: it greets you with your commute's
  travel-time to the next event.
- **Second screen** = the sync addon's transport also enables a read-only web/desktop mirror of your
  encrypted data — no new core surface area.
- **Intelligence layered on sync** = once blobs sync, a *desktop* companion (not the phone) can run the
  heavy LLM over your own data, keeping the phone lean.

## Deliberately not next

- **A cloud LLM addon.** It would reintroduce network to the *intelligence* path and send content off
  device — contrary to the posture. If/when an intelligence addon is built, prefer **on-device** (and let
  a *desktop* companion carry the heavy model).
- **Anything that puts a dangerous permission back in the core.** Always a new addon.
