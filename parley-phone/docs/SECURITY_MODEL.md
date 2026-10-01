# Security model

What Parley protects, with which keys, and against whom. It is written for people reviewing the code and for users who
want to know what "encrypted" means here. The code references are the source of truth; this page explains them.

## Threats considered

| Threat | In scope |
|---|---|
| A malicious app on the same phone | Yes: exported components, shared files, overlays, content URIs |
| A malicious file, QR code, vCard, link or list pack | Yes: bounded readers, capped KDF costs, signature checks |
| A lost or stolen **locked** phone | Yes: Keystore keys, file-based encryption, lock-screen content |
| Someone holding the **unlocked** phone for a moment | Partly: the app lock and the vault's authentication-bound key |
| Someone with a copy of a backup, or write access to the backup or sync folder | Yes: encryption, signatures, KDF cost, passphrase strength |
| A rooted phone, a compromised OS or Keystore, forensic hardware attacks | No |

## Where data lives and what protects it

Android's file-based encryption protects everything in Parley's private storage while the phone is off or locked
after a restart. On top of that, Parley seals personal data with keys held in the **Android Keystore**: the key
material never enters Parley's memory, and on most phones it lives in the TEE or a dedicated secure chip (StrongBox).
What the Keystore buys is that a copy of Parley's files alone (a backup of app data, a forensic image of the storage
taken from a running phone) is not enough to read them: the matching key stays in the phone's secure hardware.
It does **not** protect data from code running as Parley, and a key without user authentication can be used by
Parley whenever the phone is on, including while it is locked.

| Data | Key | Authentication | Why |
|---|---|---|---|
| Private contacts: name, numbers, caller card | Vault caller-ID key (`VaultCrypto`) | None | Incoming calls must show who is calling on the lock screen |
| Private contacts: every other detail | Vault detail key | Biometric or screen lock within 5 minutes, phone unlocked, StrongBox where available | Only shown to the person holding the unlocked phone |
| Number fingerprints for private contacts | Vault HMAC key | None | Caller ID without decrypting |
| Call-history archive, trashed calls | Archive key (`HistoryCrypto`): software AES key wrapped by a Keystore key | None | Kept current while locked |
| Pinned notes, call notes, screened callers' names, the undo journal, time-machine snapshots | Small-records key (`RecordCrypto`): same envelope as the archive | None | Written by background work and the call screen |
| Interaction notes (Circle) | Vault caller-ID key | None | Reminders run while locked |
| Number memory index (what Parley remembers about numbers that aren't contacts) | Numbers: their own HMAC key (`KeystoreMemoryKeys`); hints: the small-records key, each sealed on its own | None | Read while a call rings on a locked phone; the call screen shows only "Parley knows this number" until the phone is unlocked |
| Settings, rules, speed dial | File-based encryption only | — | Not personal content |

### The vault's detail key

- **Generations.** Detail blobs name the key generation that sealed them. A new generation can be introduced and
  every blob re-sealed in one database transaction; older keys are deleted only when no blob refers to them.
- **Upgrade after authentication.** On a phone without a secure lock screen the detail key can't require
  authentication. Parley checks the key with `KeyInfo` and, right after the next successful biometric or screen-lock
  prompt on a phone that now has one, moves every private contact to a new authentication-bound key that also
  requires an unlocked phone (`setUnlockedDeviceRequired`) and prefers StrongBox.
- **What 5 minutes means.** Any unlock of the phone counts as authentication for the next five minutes. Against
  someone holding a phone you just unlocked, the Keystore adds nothing: the app lock is what protects you then.
- **Lost keys are never "repaired" silently.** Removing or resetting the screen lock invalidates the detail key. Only
  that (or a key provably missing) counts as lost; any other Keystore error is treated as temporary and the data is
  left untouched. When the key is lost, the screen shows what survives in the caller-ID copy and asks; "Keep what's
  left" re-saves those fields and keeps the unreadable blob in a file beside the database.

### Small records

Short personal texts (pinned notes, call notes, screened callers' names in the blocked-call log, the 30-day undo
journal and the six-month time-machine snapshots) are sealed with AES-GCM under a random key that is itself wrapped by
a Keystore key, like the call-history archive. Rows written by older versions are re-sealed once, in the background;
until then they stay readable. Backups contain the decrypted text inside the already encrypted backup.

## Backups

- **Envelope.** STREAM construction over AES-256-GCM, 64 KiB segments, the header as associated data of every segment
  (`BackupCrypto`).
- **Who can open.** A random data key is wrapped to the backup key bundle's RSA-3072 public key (so scheduled backups
  need no secret on the phone). The bundle's private key is sealed under the passphrase and under the recovery key.
- **Passphrase cost.** New bundles and passphrase-wrapped files use **scrypt** (N = 2^15, r = 8, p = 1: 32 MB per
  attempt), older ones PBKDF2-HMAC-SHA256 with 600,000 rounds; both remain readable. Changing the passphrase moves
  an older bundle to scrypt. New backup passphrases must reach "Strong" on the built-in estimator: every backup
  carries the wrapped key, so one stolen file allows unlimited offline guessing.
- **Capped costs on read.** A file chooses its own KDF parameters, so readers refuse PBKDF2 above 2,000,000 rounds and
  scrypt above N = 2^16, 64 MB or p = 4. QR codes accept only the one setting their sender uses.
- **Signatures.** Each backup is signed (ECDSA P-256) by a key in this phone's Keystore. The backup key bundle, which
  only the passphrase or recovery key can unlock, vouches for that key. A restore shows "Made on this phone" or "Made
  on another phone of yours", and warns when the backup is unsigned, signed by an unknown phone, or altered. Without
  this, anyone who saw one backup could write a new one that "opens with your passphrase".
- **Safety settings are never restored silently.** App lock, discreet mode, "Hide screen content", supervised call
  time and the apps allowed to show private names wait until the user confirms with the app lock.

## Sync between your phones

New set-ups encrypt each synced file with the backup envelope under a passphrase shared by your phones; file names
carry no names or numbers. Plain vCard files (readable by any app or person with access to the folder) need an
explicit choice. Files from the folder are read with size caps.

## Input from outside

Every file, link and code from outside is read through `Bounded` (core/common): caps on bytes, entries, line length
and decompression ratio for vCard and CSV imports, QR payloads, simple-mode files, list packs and backups. Shares
from other apps are accepted only as `content://` URIs that don't belong to Parley itself. Links in notes open
through the same look-alike and shortener checks as scanned codes.

## Exported components and the app lock

Every activity another app or the system can start either shows the app lock and applies "Hide screen content"
through `LockedActivity`, or is listed with its reason in `ExportedComponentsTest`, which parses the merged manifest.
Sensitive screens hide non-system overlays (Android 12+) and ignore touches through overlays on older versions.

## Build and release

Gradle verifies every dependency against `gradle/verification-metadata.xml` (SHA-256). Releases are signed outside
the repository: see [RELEASING.md](RELEASING.md). Spam-list packs from the companion app are pinned to the key that
first signed them; the companion's own packs must carry its pinned key.
