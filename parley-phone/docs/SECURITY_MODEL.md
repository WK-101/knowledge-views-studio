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
| Someone **making you** open Parley in front of them | Partly: the duress PIN (see [Duress unlock](#duress-unlock)) |
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
| The Parley PIN and the duress PIN | scrypt hashes (`PinRecord`), the file sealed with the small-records key | None | Checked on the lock screen; never in backups |
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
- **Safety settings are never restored silently.** App lock and its delay, discreet mode, "Hide screen content",
  private call history, "Caller on the lock screen", supervised call time and the apps allowed to show private names
  wait until the user confirms it's them: with the Parley PIN when one is set, never the phone's fingerprint then. A
  backup's settings are not ticked by default, whoever signed it. A restore while a duress unlock hides things goes
  through the same rule as the settings screens: the safety switches change only what shows until the next lock, and
  private-name approvals are not restored at all.

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

## Duress unlock

For people at risk: a coercive partner, a check at a border, anyone who makes you unlock your phone and open Parley
while they watch. Code: `DuressMachine`, `DuressPolicy`, `PinHasher`, `PinBackoff` (core/common, `security/`);
`Concealment`, `AppPinStore`, `LockTransitions` (core/data); `AppLock`, `PinUnlock` and Settings › Privacy & security
› App lock › Unlock with (app).

### What it protects against, and what it can't

| Situation | Protected? |
|---|---|
| Someone makes you open Parley and looks through it: lists, Recents, contact pages, Circle, Call insights, My card, Settings and Settings search | **Yes.** You type the duress PIN; Parley opens as usual, with the things below out of sight and nothing on screen that says so |
| They keep the phone after Parley locks, restart it, or a private contact calls while they hold it | **Yes.** The hiding lasts until the next unlock with the real Parley PIN, across locks and restarts; private callers ring as unknown numbers |
| They make you change the PIN, turn off the app lock or discreet mode, or turn the PIN off | **Yes, for the session.** The screens show the change; the stored settings are untouched, a "new PIN" becomes the new duress PIN, and the session's changes are forgotten at the next lock. "Change PIN" answers the same whatever is typed, so it can't be used to test PINs (see below) |
| They make you use the fingerprint or the screen lock instead | **Yes.** With a Parley PIN set, only a PIN opens Parley, whether or not a duress PIN is set, so the lock screen looks the same either way. That includes the Quick Settings tile: turning discreet mode off while Parley is locked opens Parley's own lock screen |
| They make a backup, or Parley backs up on its schedule | **Yes.** A backup made after a duress unlock has no private contacts and no hidden notes, and says nothing about leaving them out; scheduled backups and the Markdown export wait until the real PIN |
| Someone who knows Parley has a duress PIN (or reads this page) and suspects you used it | **Partly.** The screens of a session look exactly like those of a Parley with a PIN and no duress PIN: the duress PIN shows "Off" (and can even be "set" there, for the session), Settings search finds the same rows, the lock screen is the same. Nothing can prove there is no second PIN, and the app can't hide that the feature exists; see "What still differs" below |
| A forensic copy of the phone's storage, a rooted phone, a compromised OS | **No.** The data is all there, encrypted as usual; see [Threats considered](#threats-considered) |
| Android's own screens and other apps: the system call log, Android's Settings › Apps (storage size), Google Contacts, messaging apps, other apps' notifications | **No.** Parley can't change them. "Private call history" keeps private contacts' calls out of the system call log, and private contacts are never in the address book; everything else outside Parley stays as it is. Parley's own notifications are cleared at the duress unlock (missed calls, reminders, expected-call hints); a call in progress stays |
| A guess at the PIN | Five tries are free, then each wrong PIN waits 30 s, doubling to an hour (`PinBackoff`, counted in elapsed time so a clock change doesn't help; a restart starts the wait over). Each try is counted and stored before the PIN is checked; when the count can't be stored, it is kept in memory and Parley fails closed: the first such try isn't checked at all and every wrong one waits, so restarting Parley buys nothing. PIN changes count the same way (five free until the next unlock with the Parley PIN) |

### What a duress unlock hides

Everything discreet mode ("Hide private contacts") hides, with discreet mode forced on whatever its switch says:
private contacts in every list and search, the private call history, number memory's lines from private sources,
To call items about private contacts, deleted private contacts in History & undo, private names in notifications and
on the call screen. And, beyond discreet mode (`Concealed`):

- **Circle notes and promises**, for everyone (promises are lines of a note). Logged moments stay, without notes.
- **Notes for calls and call notes**, for everyone: they read as none on the contact page and on the call screen.
- **Number memory's lines that quote a note** (pinned notes, moments, promises, call notes), on the keypad and the
  call screen: the index was built before the unlock and keeps its excerpts, so they are dropped where the index is
  read (`NumberMemory.concealNotes`), not only where the notes are.
- **Family safe words**: none shows, not even which labels have one. One set during the hiding shows as set; one set
  for a label that already has one shows instead of it, in memory, and never replaces it (see below).
- **My card › Shared with**: every entry, not only private contacts' (who you gave your number to can matter as much).
- **Private contacts' own ringtones**: a private caller rings with the ringtone for everyone else. Their "send to
  voicemail", labels and the screening rules still apply, so nobody who was kept out rings through.
- **Private contacts' details**, with "Keep private details locked" (on by default): the vault's detail key refuses to
  open (`VaultCrypto.detailLocked`) even inside the phone's own 5-minute window, so no path the hiding missed (an old
  link, a widget) can open one. Off, they are only out of sight. Backups leave private contacts out either way.
- **The Privacy dashboard's counts** of private contacts and calls read 0; a link to a private contact's page finds no
  contact.
- **The duress PIN itself**: Settings › … › Unlock with shows it "Off", as on a phone where none was ever set, and
  Settings search finds the same rows as always.

Nothing is deleted or rewritten. Stores show the hidden item as absent and, when a screen writes back that absence
(saving a contact's settings, editing a logged moment), keep what is stored (`SealedMetaDao`, `InteractionStore`).
What is written during the hiding is kept and shows (`Concealment.markWritten`): a note added to a contact that had
none, a call note, a moment's note, a safe word for a label without one. A note or safe word typed over a hidden one
shows instead of it until the real PIN, in memory only, so nothing hidden is ever replaced unseen; it is gone after
the real PIN (or a restart), like the session's other changes.

### Design choices

- **A PIN of Parley's own.** The app lock used only the phone's credential through `BiometricPrompt`, which can't
  tell two credentials apart. The optional **Parley PIN** (4–12 digits) replaces it: once it is set, only a PIN opens
  Parley.
- **Hashes only.** Both PINs are scrypt hashes (N = 2^14, r = 8, p = 1: 16 MB an attempt) with one shared random salt,
  so an attempt costs one derivation whichever PIN it is and both comparisons always run in constant time: the time a
  try takes says nothing about which PIN matched. The record (with the wrong-try count) is sealed with the
  small-records key, so a copy of Parley's files gives nothing to guess against offline without the phone's Keystore;
  it never goes into backups (a restored phone uses the screen lock until a PIN is set there).
- **The two PINs look the same.** Same field, same wait, same screen after; a right PIN of either kind clears the
  wrong-try count. Settings › … › Unlock with shows the Parley PIN as on and the duress PIN "Off" during a duress
  session (`AppPinStore.shown`); a duress PIN "set" there shows as on for the session's screens, is never stored, and
  is gone at the next lock. The switches on the Privacy page show what you left them at (`AppSettings.duress`), not
  the forced discreet mode.
- **A configured-but-hidden duress PIN looks exactly like none.** This was the design rule for every screen a session
  can reach. The lock screen is the hard case: offering "Use fingerprint or screen lock" only when no duress PIN is
  set would tell anyone who has seen Parley before which phone has one. So the rule is uniform: **with a Parley PIN,
  only a PIN opens Parley**, duress PIN or not; the fingerprint is not offered. The cost is that a Parley PIN can't be
  skipped with a fingerprint; the gain is a lock screen that says nothing.
- **Only a PIN while a Parley PIN is set.** Otherwise "use your fingerprint" would undo a duress PIN. Parley therefore
  can't recover a forgotten Parley PIN: the only way back is clearing Parley's storage in Android's settings (private
  contacts not in a backup are lost). Choosing a PIN and setting a duress PIN say so first.
- **"Change PIN" is no oracle.** In a session, a "new PIN" becomes the duress PIN, with one scrypt run whatever is
  typed; a new PIN that happens to be the real one leaves the duress PIN as it was and answers the same (the person
  watching then knows the real PIN anyway). "Can't be your Parley PIN" compares with the duress PIN, the one the
  person watching knows, never with the real one. Changes count like wrong tries (`PinBackoff.changeWait`) and only
  the real PIN resets that count, so "set a guess, lock, unlock with it" can't go on without limit either.
- **Hiding until the real PIN, a session until the next lock.** The duress *session* (the unlocked screens and the
  in-memory settings changes) ends at the next lock; the *hiding* is stored in `no_backup/app_lock_state` and ends
  only with the real PIN, so a lock, a timeout or a restart while someone else holds the phone reveals nothing, and a
  call that wakes Parley finds it hidden. If that file can't be written, the hiding holds in memory and the write is
  tried again until it succeeds; a file that is there but can't be read counts as hiding (fail closed).
- **Failing closed.** A PIN record that can't be opened for a moment keeps the PIN field (the screen lock never opens
  Parley because of a Keystore hiccup). The private-name providers, which an approved app can start in a cold
  process, read the stored settings and the hiding themselves, and answer "hidden" when that takes more than 1.5 s
  (`SettingsRepository.hidesPrivateNames`). While hiding, nothing writes the stored safety switches, inside a session or
  not (the Quick Settings tile included). A manual backup made while hiding never rotates older backups out.
- **Changes made during a session don't stick.** Turning off the app lock, discreet mode or private call history only
  changes what the screens show until the next lock (`DuressPolicy.split`); other settings are stored as usual. "Private
  call history" stays as stored either way, so private calls never reach the system call log because of a session.
- **"Delete all data" never destroys what a duress unlock hides.** During the hiding it sees only the concealed view: no
  "Unlock private contacts" prompt, no "Delete without backing up private contacts" (a backup leaves them out
  silently, as every backup made then does). After "Deleting…" it ends with "Parley couldn't delete its data just
  now, so nothing was deleted", the same message a wipe that can't stop Parley's background work shows, and nothing is
  deleted. Android's own "Clear storage" can still destroy everything; Parley can't prevent that.
- **No "wipe on duress".** An option to delete private contacts on a duress PIN is deliberately not built: a mistyped
  PIN, a curious child or a stressed moment would destroy data for good, the person watching might notice a wipe (a
  longer pause, a changed count elsewhere) and punish it, and a deletion can be undone by nobody, while hiding can be
  undone by you. Everything here hides; nothing destroys.

### What still differs

Kept here so nobody has to find it out the hard way: changes made in a session that the screens show (the PIN off,
the app lock off, a duress PIN "set") are forgotten at the next lock, and a note typed over a hidden one is gone after
the real PIN. Someone who changes something, locks Parley and looks again can notice that.
- **Emergency calls are never in the way.** The lock screen keeps its Emergency call button with the PIN field;
  incoming calls are never locked (the call screen is a separate activity), only shown with less.

## Exported components and the app lock

Every activity another app or the system can start either shows the app lock and applies "Hide screen content"
through `LockedActivity`, or is listed with its reason in `ExportedComponentsTest`, which parses the merged manifest.
Sensitive screens hide non-system overlays (Android 12+) and ignore touches through overlays on older versions.

- **Internal actions.** Parley's own notifications, widgets, shortcuts and tiles open the main screen through a
  non-exported alias (`InternalEntry`). The actions they carry (missed calls seen, a caller's page, the post-call
  Block and Report, Back up, the private-name approval…) count only when they come through it
  (`IntentRoutes.INTERNAL_ACTIONS`); sent by any other app to the exported MainActivity, they open nothing. Missed
  calls count as seen only once Recents shows unlocked.
- **Contact links from other apps** (View, Edit, Quick Contact) are looked up only when they point at Android's
  contacts provider and pass `SharedUris`.
- **"Confirm it's you"** inside Parley (turning the app lock or supervised call time off, applying restored safety
  settings, deleting everything, showing a safe word, leaving simple mode, emptying History & undo, PIN changes)
  asks for the Parley PIN when one is set (`PinConfirm`), never the phone's fingerprint or screen lock: someone who
  knows the phone's code, or a sleeping partner's finger, mustn't get past a confirmation the Parley PIN guards.
  The duress PIN typed there starts a duress session, as on the lock screen.

## Private names in other apps

- **Approving an app.** The request notification's "Allow…" opens a sheet inside Parley, behind its lock, that names
  the app by its package name and the SHA-256 of its signing certificate (as Android reports it), never by its
  label, which the app chooses itself. "Allow" then asks for the Parley PIN (or, without one, the phone's unlock)
  once more. "Don't allow" acts from the notification (after the phone's unlock): it grants nothing. Allowing from
  Privacy › Private names goes through the same sheet. The approval stays bound to that certificate.
- **During a duress session** the switch, the Directory switch and the approvals are safety switches: the screens
  show a change, nothing is stored, and the next lock forgets it (`PrivateNameAccess.endSession`). The providers
  read only what is stored, and answer "hidden" while hiding anyway.

## Private calls and the system call log

Telecom always writes a connected call into Android's call log; Parley can only take it out again. When a call with
a private contact ends (and "Private call history" is on), the in-call service marks it in a small file, watches the
call log for the insert and moves the row into the private history at once, with checks at 0, 0.3, 1, 2.5, 5, 10 and
20 seconds in case the change notice is late (`PrivateCallLogSweep`, `PrivateCallSweepPlan`). If the process ends
first, the next start sweeps before anything else; the daily upkeep catches anything older.

**What remains:** the moment between Telecom's insert and Parley's delete. The delete runs on the change notice, so
the expected window is well under a second (one call-log query and one delete; debug builds log the measured time
under the `PrivateCallLog` tag, see TESTING.md §35.1), against 2.5–7.5 s before. An app holding `READ_CALL_LOG` that
watches the call log itself can still see the row in that moment, and an OEM call-log backup that runs in it can copy
it. If Parley's process is killed during the call and isn't started again, the row stays until Parley next runs.

## Lock screen and home screen

- **Call screen.** "Caller on the lock screen" is *Name* by default: the name shows while the phone is locked, but the
  pinned note for calls, "Who is this?" and the last call wait until it is unlocked (anyone can ring a locked phone).
  *Name and notes* shows them as before; *Initials* and *Just "Incoming call"* show less.
- **Widgets.** With the app lock on, the Circle and Favourites widgets show counts instead of names while the phone
  **or Parley** is locked (Parley locked, or away longer than its lock delay), not only while the phone is locked. A
  tap then opens Parley to unlock it.
- **Shared files.** Files handed to other apps from the cache (contact cards, a scanned Secure QR's card, voicemail
  audio, rule exports, transfer files) are deleted at the next start and after an hour, like exports.
- **Device transfer.** Cloud backup and device-to-device transfer exclude every app-data domain, the device-protected
  ones (spam lists) included, in both apps.

## Keys and signatures (details)

- Key fingerprints shown for comparison (My card, shared-label members, rule packs) are the first 128 bits of the
  key's SHA-256. Pins and links always compare the whole key.
- The pure Ed25519 verifier (and the platform path, to agree with it) refuses small-order public keys and R values,
  so no signature can pass for every message.

## Build and release

Gradle verifies every dependency against `gradle/verification-metadata.xml` (SHA-256). Releases are signed outside
the repository: see [RELEASING.md](RELEASING.md). Spam-list packs from the companion app are pinned to the key that
first signed them; the companion's own packs must carry its pinned key.
