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
| My card's signing key, "Shared with", contacts' card links | Small-records key (`RecordCrypto`), each store one sealed document | None | Signing a card you share; the list of who has it; updates arriving while locked |
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

## Signed cards (My card)

- **What is signed.** Every My card Parley shares (its QR code, the card file, "Send my card" in Changed my number, the
  QR swap) is a plain vCard 3.0 with two extra properties, `X-PARLEY-CARD:2;<card id>;<version>;<public key>;<parts>`
  and `X-PARLEY-SIG:<signature>` (`SignedCards`). Other apps and camera scanners ignore or keep them like any `X-`
  property; older Parley versions import the card as before. The Ed25519 signature covers a fixed header, the card id,
  version, public key, the parts the share includes (name, numbers, work…), and each field the card shares, one per
  line with line breaks and tabs escaped, so a field can't be stretched into another. The private note is never in it.
  "Send my details" and Introduce myself send plain text, which carries no signature (it would be noise in every chat
  app); they only add a "Shared with" entry. Every share signs the same card (My card with the phone's profile filled
  in), once the profile has loaded.
- **What counts as signed.** A received card shows as signed only when it is exactly what Parley writes: each of N,
  FN, ORG, TITLE and ADR at most once, only the properties My card uses (no notes, extra rows, `X-ANDROID-CUSTOM`…),
  only the TYPE parameter, only the name and street parts, canonical escaping, a display name that says what the name
  says, nothing outside the signed parts, and within the size caps (never cut short). Anything else is reported as
  changed after signing, so what an importer saves from a card shown as signed is what the signature covers. A file
  with unsigned cards beside a signed one says so.
- **The key.** One random card id (128 bits) and one Ed25519 key per user (`MyCardIdentity`), made on first use.
  The Android Keystore can't hold Ed25519 keys on most phones, and a Keystore key could never reach your next phone,
  where your contacts would then see a different signer. So the 32-byte secret is sealed with the small-records key
  (AES-GCM, wrapped by a Keystore key) and is never stored plain (while sealing fails, nothing is signed); it travels
  only inside the encrypted backup. Showing the QR code doesn't count as sharing; a card file sent or a QR swap does.
  A restore takes the backup's key at once on a phone whose own card never left it; on a phone that already shared its
  own, My card asks which key to keep. Signing uses the platform's constant-time Ed25519 on Android 13+, the small pure
  implementation (`Ed25519`, as for rule packs) below that.
- **Trust: on first explicit link, the key pinned after.** A received card is never linked to a contact by itself.
  The user links it: "This card says it's Ana. Link it to Ana so their future updates show here?", on the scan,
  file or paste result, or on Ana's page for a card received earlier (`CardInbox`, `CardIntake`, `CardLinkBook`). From
  then on, Ana's contact accepts updates only from that card id **and** key (`CardLinkStore`, by the contact's Parley
  key, sealed). Any other card that claims to be Ana (another card id with her number, or her card id signed by
  another key) is shown as "a different key signed it", offers nothing, and never replaces her link or a card held for
  her; the only way to switch is the explicit **Trust the new card**, which shows both keys' fingerprints (also the way
  to accept a genuinely new key, e.g. after a lost phone). A card received for nobody is held (90 days, apart by card
  id and key) only so the contact's page can ask; a restored link never replaces one a contact has.
- **Versions.** The version grows when anything the full card says changes (never on a mere share or view) and never
  repeats, also across phones and restores (at least the current time in seconds). A receiver offers a card only when
  it is newer (or shares parts not seen yet) **and** has the linked key. A part a share leaves out is unknown, never a
  removal. Nothing is ever applied without the user's review: the contact's page lists each change, with removals,
  the name and replacements of a value the user wrote themselves unticked; addresses are matched by the card's
  previous address, never "the first one". Apply goes through the editor's save (History & undo keeps the previous
  version of a device contact).
- **Private contacts.** Their card links, and "Shared with" entries for them (which keep no name or number: both are
  read from the vault when shown, and hidden in discreet mode), are sealed with the vault's key in documents of their
  own and travel only in the private-contacts part of a backup.
- **What a signature does not say.** It proves the card came from whoever held that key when the user linked the
  contact, not who that person is: the first card is trusted as much as the user's choice to link it. The key's
  fingerprint is on My card › Shared with, in the link question and in the update dialog, for people who want to
  compare.
- **Received cards are bounded.** At most 50 cards, 400 lines and 2,000 characters a value are read from one text
  (a card over these is not trusted, never cut short); opened files are checked only up to 2 MB.

## Sync between your phones

New set-ups encrypt each synced file with the backup envelope under a passphrase shared by your phones; file names
carry no names or numbers. Plain vCard files (readable by any app or person with access to the folder) need an
explicit choice. Files from the folder are read with size caps.

## Shared labels

A label shared with other people's phones (docs/SHARED_LABELS.md) has a folder and a key of its own: AES-256-GCM per
file under a key from the label's passphrase (scrypt, the backup's caps on read), every file bound to the label and its
name. Each member signs what they write with their My card key (Ed25519, its own signed header, so no signature passes
for another kind); members are worked out from signed invitation tickets starting at the anchor named in the
invitation, and files signed by anyone else are ignored. Contact files carry versions that only grow, so a copy put
back is ignored by a phone that saw a newer one; only a signed tombstone deletes, never a missing file, and a listing
that is incomplete changes nothing. Invitations travel sealed (a one-time passcode for the QR code, the label's
passphrase for a file). Removing a member changes the key; what the removed member already had stays theirs. Each
phone keeps the key and its bookkeeping sealed with the small-records key, outside backups. Private contacts are never
shared.

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
