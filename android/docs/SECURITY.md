# Kairo — Security & Privacy Threat Model

Kairo (`com.wkhan.kairo`) is a fully-offline personal task/habit/time manager. Its defining property is
that **it has no way to talk to a network** — the design goal is that your data physically cannot leave
the device except through a file *you* explicitly export and hand to another app. This document is the
threat model behind that claim: what is protected, how, and what is explicitly out of scope.

## 1. Attack surface

| Surface | Status |
| --- | --- |
| Network I/O | **None.** The merged manifest declares no `INTERNET` permission, so the process cannot open a socket. There is no HTTP client, no analytics SDK, no crash-reporting-to-cloud, no ads, no telemetry. |
| Location | **None.** No `ACCESS_*_LOCATION`. The "arrive here" reminder is a local, permission-gated geofence with no Play Services and no coordinates sent anywhere. |
| Broad storage | **None.** No `READ/WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, no media permissions. File attachment, backup and import all go through the Storage Access Framework (system pickers), so the app only ever sees the specific URIs the user picks. |
| Inter-process | Exported components are limited to the launcher activity, widgets, and the notification/boot receivers. No exported content provider over app data; `FileProvider` authorities are scoped and grant per-URI, time-boxed read access only when the user shares an export. |

### Declared permissions (all local-device only)

`POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`,
`USE_FULL_SCREEN_INTENT`, `ACCESS_NOTIFICATION_POLICY`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

Every one exists to make **on-device reminders** fire reliably (exact alarms, re-arm after reboot, ring
through Do-Not-Disturb when the user opts in). None can move data off the device. `POST_NOTIFICATIONS` is
requested lazily — only when the user first turns on a feature that posts a notification.

**Verify it yourself:**
```bash
aapt dump permissions app-release.apk        # or:
aapt dump xmltree app-release.apk AndroidManifest.xml | grep uses-permission
```
There should be no `INTERNET`, no `*_LOCATION`, no `*_EXTERNAL_STORAGE`. A CI step and a build gate assert
the same invariant on every release.

## 2. Data at rest

- **Database.** All app data lives in a single Room/SQLite database encrypted with **SQLCipher (AES-256)**.
  The passphrase is generated on first run and wrapped by a hardware-backed key held in the **Android
  KeyStore** (`SecureDb`); the plaintext key is never written to disk. On devices with a secure element
  the wrapping key is non-exportable.
- **Migration.** Turning encryption on/off performs a guarded plaintext↔ciphertext migration; the
  round-trip is exercised by an instrumented migration test across the full (~59-version) schema chain.
- **Backups & sync files** written to a user-chosen folder are encrypted at rest; the "Sealed Courier"
  transfer format carries only the metadata needed to merge and uses post-quantum-oriented crypto for the
  sealed payload.
- **Crash log.** A crash writes a local `last_crash.txt` for the user to inspect or attach — it is never
  transmitted.

## 3. Data in motion (there is none, by construction)

The only way data leaves the device is a **user-initiated export**:

- **Full JSON backup** — lossless, intended for the user's own storage/restore. Complete by design.
- **Shareable exports** — Markdown / CSV / iCalendar, produced for handing a plan to another person or
  app. These support **per-field redaction**: with "Redact notes from shared exports" on, free-text notes
  are omitted (note quote-lines dropped from Markdown, the Note column blanked in CSV, `DESCRIPTION`
  dropped from ICS `VEVENT`s) while titles, dates and tags still export. The full JSON backup is
  intentionally *not* redacted, so a personal backup stays complete. (Guarded by `ExportRedactionTest`.)

Sharing is always an explicit, foreground action through the system share sheet — the user chooses the
target app, and `FileProvider` grants that app read access to that one file only.

## 4. What is in scope vs. out of scope

**In scope (mitigated):**
- Passive data exfiltration by the app itself — impossible without `INTERNET`.
- Data readable by another app or by pulling the app's data directory off an unrooted device — mitigated
  by SQLCipher + KeyStore-wrapped key.
- Casual shoulder-surfing / device sharing — optional biometric / device-credential **app lock**.
- Leaking private notes when sharing a plan — per-field export redaction.

**Out of scope (documented, not defended against):**
- A **rooted or compromised OS**, or malware with root — it can read KeyStore-unwrapped memory and any
  app's data; no app-level measure defends against a hostile kernel.
- **Physical access to an unlocked device** with the app lock disabled — this is equivalent to the user
  being present.
- **Backup/export files after they leave the app** — once the user exports a plaintext JSON backup and
  places it somewhere, its security is the destination's responsibility. (Redaction and at-rest encryption
  reduce this exposure.)
- **Screen-capture / accessibility-service snooping** by other apps the user has granted those powers to.

## 5. Supply chain

- Obfuscation is deliberately **off** so release stack traces stay readable; R8 **code + resource
  shrinking** is on, with keep rules covering the audited reflective surface (kotlinx.serialization,
  SQLCipher JNI, Room, ZXing) and nothing else.
- Dependencies are pinned; no dependency pulls in a networking transitive that the manifest would then
  have to grant. The absence of `INTERNET` is the backstop: even a compromised dependency has no egress.

_Last reviewed: R106. This document describes intent and current implementation; see `AndroidManifest.xml`,
`data/SecureDb`, and `domain/port/Export.kt` for the ground truth._
