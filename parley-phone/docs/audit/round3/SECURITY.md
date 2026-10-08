# Round 3, Track C: security and privacy audit of Parley 6.2.1 (HEAD a67fd3d6)

Scope: a read-only, threat-model-driven review of `parley-phone/` (app, telecom, core/common, core/data,
lists-updater, build files) against `docs/SECURITY_MODEL.md`, `docs/CONTACT_MODEL.md` and round 2
(`docs/audit/round2/SECURITY.md`, C-01…C-18). The emphasis is on what 5.4–6.2.1 added: Situations, Recall, Case files,
the family shield, Chapters, Archive, Agenda, Rescue call, native names and network names. I opened every file:line
cited below. **[uncertain]** marks claims that depend on platform or OEM behaviour I couldn't check from the repo.

Severity is exploitability × impact:
- **High**: easy to trigger, and it breaks a stated promise for the people the feature exists for.
- **Medium**: plausible to trigger, with real harm.
- **Low**: needs unusual access or gives limited gain.
- **Info**: defence in depth.

## Summary

The core is in good shape:
- **Cryptography.** The cryptography is still correct, and the round-2 fixes held:
  - the duress paths (C-01, C-02, C-04);
  - the notification approval (C-05);
  - internal actions behind `InternalEntry` (C-06);
  - the sub-second private-call sweep (C-07);
  - lock-screen notes (C-08);
  - cache sweeps (C-09);
  - extraction rules (C-11);
  - PIN-only confirmations (C-12);
  - widgets (C-14);
  - 128-bit fingerprints (C-15);
  - `SharedUris` (C-17);
  - small-order keys (C-18).
- **Emergency calls.** The emergency path is exemplary: `CallGate` asks nothing before an emergency call, and the lock
  screen's emergency keypad dials only emergency numbers.
- **New stores.** New stores mostly follow the "sealed or not written" pattern: case files, menu memory, network names,
  shield, safe words.

The weak spots are at the edges of the 6.x features, where a new path skipped an older privacy rule:

1. **Blocking notifications name private contacts.** They do so regardless of "Hide private contacts", a duress
   hiding or "Caller on the lock screen", and they have no lock-screen public version. Situations ("Night",
   "Meeting") make silenced contacts routine, so this fires often (S3-01, Medium).
2. **A stranger's vCard or QR code can plant a hidden "saved contact".** Plain imports honour `X-PARLEY-ARCHIVED`,
   ringtone and send-to-voicemail flags, and any `X-ANDROID-CUSTOM` mimetype. An archived import is invisible in the
   lists, yet caller ID shows its name as a saved name. Screening, the family shield and scam help also treat it as a
   contact (S3-02, Medium).
3. **"Caller name contains" allow rules trust the network's name, which callers can spoof.** The raw, uncleaned name
   is used for matching and for the answered call's title (S3-03, Medium).
4. **Rescue call shows a pending rescue call during a duress session.** The screen shows the rescue call waiting, its
   time and the "caller". This is exactly the person the feature protects (S3-04, Medium, safety).
5. **Smaller duress and lock gaps:**
   - case files go into backups made during a duress hiding (S3-05);
   - "Lock private contacts" is undone by the phone's credential even when a Parley PIN is set (S3-06);
   - Archive leaves the person's name in other contacts' relation rows and in Android's call log (S3-07).

Still open from round 2:
- the release key on `/tmp`, with identical store and key passwords (C-03, High, operational);
- dependency verification without signatures, and Material 3 alpha (C-13).

## Ranked findings

| # | ID | Sev | Title | Effort |
|---|---|---|---|---|
| 1 | S3-01 | **Medium** | Blocking notifications name private contacts in discreet mode, under duress and on the lock screen | S |
| 2 | S3-02 | **Medium** | Plain vCard/QR imports honour archive/private flags, control fields and arbitrary mimetypes | S |
| 3 | S3-03 | **Medium** | CNAP name is trusted by allow rules; the raw name is matched and shown | S |
| 4 | S3-04 | **Medium** (safety) | Rescue call: the pending call and last choices are visible in a duress session | S |
| 5 | S3-05 | Low–Med | Case files (notes, promises, references) go into backups made while a duress hiding is on | S |
| 6 | S3-06 | Low–Med | "Lock private contacts" ends with the phone's credential even with a Parley PIN | S |
| 7 | S3-07 | Low | Archive leaks back: relation mirrors kept, call-log names kept; the copy says "other apps" | S |
| 8 | S3-08 | Low | Plaintext at rest: PIN hashes and archive files on Keystore failure, `share.key`, private call-screen pictures | S–M |
| 9 | S3-09 | Low | Exported `TileLongPressActivity` relays an internal action from any app | S |
| 10 | S3-10 | Low | An encrypted vCard is taken as proof that Parley wrote it (notes for calls imported) | S |
| 11 | S3-11 | Low | Family shield: one member's verdict blocks; new block rules are shared without saying so | S |
| 12 | S3-12 | Low | Unbounded line reads before an import is confirmed (crash; the process hosts the in-call service) | S |
| 13 | C-03 | **High** (ops) | Release keystore still on `/tmp` with store password = key password (round 2, open) | owner |
| 14 | C-13 | Low | `verify-signatures=false`; Material 3 `1.5.0-alpha14` ships (round 2, open) | S–M |
| 15 | C-16 | Info | Keystore- and records-sealed blobs carry no AAD (round 2, open) | M |

---

## Findings

### S3-01 (Medium): blocking notifications name private contacts, ignoring discreet, duress and lock-screen rules

**Evidence**
- `core/data/.../data/CallScreener.kt:329-336`:
  - `isContact` is true for a vault hit;
  - `caller()` returns the private contact's name (`:389-397`, `privateCaller(...)` → `Caller(p.name, …)`), which
    becomes `Gathered.contactName`.
- `CallScreener.kt:509-510` keeps only a private contact's *send to voicemail* out of the log and notifications.
  Every other block or silence of a private contact still goes through. That includes:
  - a label block rule;
  - off hours;
  - a Situation that lets only favourites ring.
- `CallScreener.kt:527` passes the result to `onScreened` with `g.contactName`.
- `app/.../blocking/BlockingNotifier.kt:84` sets `who = e.contactName ?: number`. It then posts:
  - "<name> called during quiet hours" (`:97-105`);
  - "Silenced call from / Blocked call from <name>" (`:116-136`).
- No path checks `hideVault`, `Concealment` or `LockScreenCaller`, and neither builder sets `setVisibility` or
  `setPublicVersion` (contrast `PrivateNotice.kt:26-35` and `CallNotifier.kt:357-373`).
- `HiddenNotifications.clear` (`LockTransitions.kt:54-62`) only clears what existed at the duress unlock. Posts made
  during the hiding are untouched.
- `notifyBlocked` defaults to `QUIET` (`CallPolicy.kt:154`), which posts silently but visibly.

**Scenario**
1. A coerced user has opened Parley with the duress PIN, or has "Hide private contacts" on.
2. The "Night" Situation is on.
3. A private contact calls. The shade, and on many phones the lock screen, shows "Silenced call from Dr. Rahman".
4. The missed-call notification for the same call (`MissedCallNotifier`) correctly hides the name, so the two
   notifications contradict each other.
5. The blocked-calls log also keeps the private number, and "Why it rang, or not" lists it.

SECURITY_MODEL §"What a duress unlock hides" promises "private names in notifications" are hidden.

**Fix**
1. In `BlockingNotifier.post`, resolve `who` through the same helper `MissedCallNotifier` uses, so that a private caller
   becomes the number in discreet mode or while hiding. Apply `LockScreenCaller.shownName`.
2. Build both notifications with `PrivateNotice.builder` (private visibility, neutral public version, `setLocalOnly`).
3. Don't log or notify a private contact's block outside the vault at all: extend `:509-510` to
   `isContact && vault hit`.
4. Add a regression test that drives `onScreened` with a vault hit while hiding.

**Effort:** S.

### S3-02 (Medium): plain vCard and QR imports honour archive/private flags, control fields and arbitrary mimetypes

**Evidence**
- `core/common/.../vcard/CardNotes.kt:59-60`: for a file that isn't sealed, `forImport` still returns
  `CardNotes(private = private, archived = archived)`. The doc comment there argues only that *private* is harmless.
- `core/data/.../export/ContactExport.kt:287-291`: `saveNotes` archives every imported card marked
  `X-PARLEY-ARCHIVED:1` (`c.archive.archive(contactId)`).
- `core/common/.../vcard/VCardMapper.kt:946-948`: `X-PARLEY-STARRED`, `X-PARLEY-SEND-TO-VOICEMAIL` and
  `X-PARLEY-RINGTONE` (any URI string) are read into the record.
- `VCardMapper.kt:892` and `:1127-1141`: `X-ANDROID-CUSTOM` emits a data row with **any** mimetype, 14 columns and an
  optional blob. Only group rows are dropped.
- The single-card QR path strips control flags (`common/qr/ScannedCard.kt:22-51`). The file import (`VCardIO.kt:156`)
  and the QR "Import all" and "Import as is" paths (`ui/qr/QrResultSheet.kt:281-288`, `:333`, `:455`) write the raw card.
- `ui/common/ImportVcfDialog.kt:60-140` shows only an account choice and a count, with no preview of names, flags or
  archived cards.
- An archived contact is a saved contact everywhere it matters:
  - screening (`CallScreener.kt:330`, `archivedLookups`);
  - the family shield (`FamilyShield.kt:105-106`, `isContact` → `NONE`);
  - "Is this a scam?" (`ScamCheck.offered` is for unsaved callers);
  - caller ID, which shows the name as a saved name, not "From the network".

**Scenario**
1. A scammer sends "HSBC Fraud Team.vcf" in a chat, or prints a QR code. The card contains the scammer's number,
   `X-PARLEY-ARCHIVED:1` and `X-PARLEY-STARRED:1`.
2. The victim taps "Import". The card goes into the Google account and is immediately archived, so it is invisible
   in Contacts, search and widgets.
3. Later calls from that number:
   - ring through "only contacts", spam lists and the family shield;
   - show "HSBC Fraud Team" as a saved contact;
   - never offer scam help.
4. Variants:
   - a `X-PARLEY-RINGTONE` pointing at another app's `content:` URI, which Telecom (system) opens to ring
     **[uncertain]** on which apps' providers that reaches;
   - messenger-owned mimetypes (`vnd.com.whatsapp…`) injected into a Google raw contact so "Message on" actions point
     at another number **[uncertain]** on how Reach-via-apps weighs non-messenger rows.

**Fix**
1. For unsealed sources, honour neither `archived` nor `private` silently. Show them in an import preview ("2 cards ask
   to be archived, 1 private"), unticked by default.
2. Apply `ScannedCard`'s flag rule to every plain import. Starred, send-to-voicemail and ringtone are dropped unless
   ticked, and a ringtone is never taken from a file.
3. Allowlist `X-ANDROID-CUSTOM` mimetypes: Android's `CommonDataKinds`, Parley's own kinds and Google's custom field.
   Drop the rest, and report them as skipped.

**Effort:** S.

### S3-03 (Medium): the network's caller name (CNAP) is trusted by allow rules; the raw name is matched and shown

**Evidence**
- `core/common/.../CallPolicy.kt:559-563`:
  - `ALLOW` rules of any non-label type match through `factMatches`;
  - `:731` `CALLER_NAME` is a plain `contains` on `f.callerName`.
  - Allow rules come before block rules, spam lists and the shield (`:586+`).
- `ui/blocking/RuleEditorScreen.kt:153` offers Block or Always allow for every type, `CALLER_NAME` included. The help
  text (`strings_blocking.xml:574`) doesn't warn that the name is caller-controlled.
- `ParleyCallScreeningService.kt:59` and `CallManager.kt:285` pass the **raw** `callerDisplayName` to screening. The
  6.2.1 cleaning (`NetworkName.clean`, which strips bidi and format characters, placeholders and length) runs only for
  storage.
- `CallUiMapper.kt:45`: the call's `name` is `found?.name ?: contactDisplayName ?: d.callerDisplayName` (raw).
  "Not in your contacts" shows only while it rings (`CallerHeader.kt:257`). Once answered, and in the
  `CallNotifier` title, the CNAP name looks like a saved name, with no "From the network" tag.

**Scenario**
- A user adds "Always allow: caller name contains *Hospital*" (or installs a shared rule template that does).
- Anyone who can set a CNAM or display name (VoIP origination, some international routes) and puts "Hospital" in it
  rings through every block rule, spam list and the family shield.
- Separately, a zero-width character inside "Su​rvey" defeats a block rule for "survey", because matching uses
  the uncleaned name.

**Fix**
1. Allow rules: refuse `CALLER_NAME` for `ALLOW` (the editor offers Block only), or only when STIR/SHAKEN verification
   `PASSED`.
2. Matching and display: match against `NetworkName.clean(...)` output. Build `CallUi.name` from the cleaned name.
3. Tag a CNAP-only name with the same "From the network" line Recents uses, on the answered call screen and in the
   call notification.
4. Shared templates carrying an allow-by-name rule show a warning in the preview.

**Effort:** S.

### S3-04 (Medium, safety): Rescue call's pending call is visible during a duress session

**Evidence**
- `app/.../ui/situations/RescueCallScreen.kt:83` collects `RescueCalls.pending`, and `:106-108` shows
  "pendingText": who and when (`:246-250`).
- The screen also pre-fills the last name, number, time and sound (`RescueCalls.kt:159-169`).
- There is no `Concealment` reference in `RescueCallScreen.kt`, `RescueCalls.kt` or `RescueCall.kt` (grep).
- The screen is reachable from Tools, a launcher shortcut (`IntentRoutes.ACTION_RESCUE_CALL`) and the Situation tile's
  long press.
- The prefs (`rescue_call`, `RescueCalls.kt:53-64`) hold the name and number in plain text, outside the sealed-store
  pattern. Severity there is low: it needs a file copy.

**Scenario:** someone at risk sets a rescue call for 21:30 "from Mum". Their partner makes them unlock Parley (duress PIN)
and browses Tools. The partner sees "Rescue call at 21:30 · Mum" and can cancel it. When the call rings, the partner
knows it is fake. The feature's target user is exactly the duress user.

**Fix**
- While `Concealment.hiding`:
  - the screen shows no call waiting and no last choices;
  - "Cancel" is absent;
  - the waiting call still rings (the hiding is about what shows).
- Seal `rescue_call` with the records key (refuse to store plain), or keep only the id, time and a vault/contact
  reference.
- Add `Concealed.RESCUE` to the duress test matrix.

**Effort:** S.

### S3-05 (Low–Med): case files go into backups made during a duress hiding

**Evidence**
- `core/data/.../cases/CaseFileStore.kt:182-189`: `backupExtras.export()` reads `load()` (the stored state), leaves out
  only private contacts' cases, and opens every reference number. It doesn't check `Concealment`.
- The on-screen view does hide everything during a duress unlock (`CaseFileStore.kt:84-96`, `Concealed.NOTES`).
- `BackupRepository.kt:193-194` calls every extra's `export()` whatever the hiding.
- Compare `AgendaStore.kt:53`, which checks `Concealment.hides(Concealed.NOTES)`.

**Impact:** SECURITY_MODEL says "A backup made after a duress unlock has no … hidden notes". Case files hold notes,
promises and **reference numbers** (claim and account references, opened in clear inside the archive). The backup is
still encrypted to the owner's bundle, so the harm needs the backup and the passphrase. A coercer who can compel a
restore elsewhere, or who later gets the passphrase, gets data the session promised was hidden.

**Fix:** in `export()`, when `Concealment.hides(Concealed.NOTES)`, export cases without notes, promises or references,
or export nothing (the case files then look empty, matching the screens). Add the store to the "backup under duress"
test.

**Effort:** S.

### S3-06 (Low–Med): "Lock private contacts" ends with the phone's credential even when a Parley PIN is set

**Evidence**
- `app/.../security/AppLock.kt:309-325`: `authenticateForVault` (every "Unlock private contacts", 23 call sites) runs
  `authenticate()`, which is the fingerprint or screen lock. On success it calls `vault.unlockedByPerson()`
  (`VaultLock.kt:54-56`), which clears `VaultCrypto.lockedByPerson`.
- `confirm()` (`AppLock.kt:296-302`) uses `PinConfirm` when a PIN is set. The vault unlock doesn't.

**Scenario:** the owner taps "Lock private contacts" and hands the phone, with Parley open, to a child or partner who
knows the phone's code (common in families). One tap on a private contact, then the phone PIN, and every private
detail opens. The owner's explicit lock was meant to need *their* unlock. SECURITY_MODEL says that with a Parley PIN,
nothing in Parley should accept "a sleeping partner's finger".

**Fix:** when a Parley PIN is set **and** `lockedByPerson` is on, ask `PinConfirm` first, then the device prompt (the
Keystore key still needs it). Without `lockedByPerson`, keep today's behaviour: Parley is already PIN-unlocked.

**Effort:** S.

### S3-07 (Low): Archive leaks back into the address book and the call log

**Evidence**
- Make private takes back the relation rows Parley wrote on other contacts (`ui/contact/ContactConversions.kt:55-58`,
  `relationMirrors.takeBack`). Archive doesn't:
  - `data/archive/ArchiveStore.kt:103-131` re-keys and purges, with no `takeBack`;
  - neither does `ui/people/archive/ArchiveActions.kt`.
  "Spouse: Ana" stays on Bob's contact, synced to Google and readable by every app with contacts access.
- Past rows in Android's call log keep `CACHED_NAME` for the archived person. Nothing rewrites them, and other dialers,
  Android Auto's phone UI and backup apps read them.
- The UI promises "They leave your contact lists, search, widgets and other apps" (`strings_archive.xml:6`).

**Fix**
- Call `relationMirrors.takeBack` in `ArchiveStore.archive`, and mirror again on `unarchive`.
- Either clear `CACHED_NAME` on the archived person's past call-log rows (Parley holds `WRITE_CALL_LOG`), or say so in
  the dialog: "Old calls in Android's call log may still show the name."

**Effort:** S.

### S3-08 (Low): plaintext at rest where the model says "sealed"

**Evidence**
- **Parley PIN hashes.** `RecordCrypto.sealBytes` returns the **plain** bytes when the Keystore fails
  (`security/RecordCrypto.kt:99-105`). `AppPinStore.write` stores that (`AppPinStore.kt:125-131`; the comment at `:25-28`
  admits it).
  - A 4–12-digit PIN under scrypt N = 2^14 is minutes of offline work once the file is copied, and a file copy is
    exactly the threat the sealing is for.
  - Every PIN attempt rewrites the file, which narrows the window but doesn't close it.
- **Archived contacts.** Whole records, photos included, are written through the same fallback
  (`ArchiveStore.kt:224-227`). `ArchiveStore` isn't a `RecordSealing.Resealable` (`RecordSealing.kt:19-21`; grep shows
  five implementers), so a plain file stays plain until the contact is unarchived.
- **Rule-pack signing key.** `blocking/share.key` is the Ed25519 secret, written raw (`data/SpamListStore.kt:391-399`).
  The My card key is sealed and "never stored plain" (`SECURITY_MODEL.md:144-146`). This one signs the rule packs and
  templates family members pin.
- **Call-screen pictures.** These are plain JPEGs in `files/call_backgrounds` (`people/CallBackgrounds.kt:28,34`),
  private contacts' included (keyed `parley-private:<id>`). The private contact's own photo is sealed with the
  caller-ID key.

**Fix**
- `AppPinStore` and `ArchiveStore`: never write plain. Keep the record in memory and retry, as `MenuMemoryStore` and
  `CaseFileStore` do (`seal(...)?.takeIf { isSealed }`).
- Seal `share.key` with the records key and migrate it once.
- Seal private contacts' backgrounds with the caller-ID key, as `vault_photos` are.

**Effort:** S–M.

### S3-09 (Low): exported `TileLongPressActivity` relays an internal action

**Evidence**
- The manifest exports `.situations.TileLongPressActivity` with no permission (it needs the `QS_TILE_PREFERENCES`
  filter).
- `IntentRoutes.tileLongPress` (`IntentRoutes.kt:132-140`) trusts `EXTRA_COMPONENT_NAME` from the intent
  (`:189-193`). When it names the Situation tile, it starts `own(context).setAction(ACTION_RESCUE_CALL)` through the
  non-exported `InternalEntry`.
- Any app can therefore pass the C-06 guard for this one action. It only opens the Rescue screen, behind the app lock.

**Fix:** use the caller-independent fact. Android sends `QS_TILE_PREFERENCES` from System UI. Check `referrer` or
`callingPackage` is System UI, or simply route a long press to `SituationRoutes.RescueCall` via the *exported* path
(no internal action). Add a case to `ExportedComponentsTest`.

**Effort:** S.

### S3-10 (Low): an encrypted vCard is taken as proof that Parley wrote it

**Evidence**
- `CardNotes.kt:53-60` keeps notes for calls, call notes, moments and keep-in-touch "only [from] a file locked with a
  passphrase … known to come from Parley".
- `VCardIO.kt:156` sets `fromSealed = passphrase != null`.
- The format is public (`docs/ENCRYPTED_VCARD.md`, the backup envelope with one passphrase wrap). Anyone can make one
  and send it with its passphrase.

**Impact:** an imported "note for calls" ("Verified: OK to give card details") shows on the call screen as the user's
own note. This is social engineering with a small reach.

**Fix:** treat sealed and plain the same for third-party content. Keep notes only when the file is an export from
*this* user: an `X-PARLEY-EXPORT` id signed with My card's key, checked on import. Otherwise list the notes in the
preview, unticked.

**Effort:** S.

### S3-11 (Low): family shield, single-member poisoning and silent sharing of new blocks

**Evidence**
- `FamilyShield.merge`/`outcome` (`FamilyShield.kt:88-110`) acts on one member's verdict. In a label set to **Block**,
  any one member, or anyone who held the label key before a rotation, can make other phones reject a number. Saved
  contacts and emergency numbers are excepted.
- `FamilyShieldStore.refreshed` (`FamilyShieldStore.kt:121-127`) adds every number blocked one by one as a shared
  verdict. The opt-in text discloses this (`strings_family_shield.xml:11`), but the Block question at the moment of
  blocking doesn't. Members can recover numbers by enumeration, as the doc admits.
  - Example: blocking an abusive ex tells the family label which number it is.

**Fix**
- Block mode acts only when at least 2 members agree, or when the verdict comes from the label's anchor. Warn mode
  stays at 1.
- The one Block question adds "Also shared with <label>" with a per-block opt-out. No new setting is needed; it is part
  of the question.

**Effort:** S.

### S3-12 (Low): unbounded reads before an import is confirmed

**Evidence**
- `ImportVcfDialog.kt:83` runs `estimateCount` as soon as a `VIEW` or `SEND` vCard is resolved.
- `VCardIO.kt:295-330` (`estimateCount`, `countCards`, `holdsPrivate`) use `VCardStream.reader(...).buffered().useLines`.
  `VCardStream.reader` (`VCardStream.kt:54-68`) has no cap, unlike `VCardStream.read`, which uses
  `Bounded.LineReader` (`:77-102`).

**Impact:** an app in the foreground hands Parley a `content:` URI that streams one endless line. `readLine` grows until
the process dies with an out-of-memory error. The process also hosts `ParleyInCallService`. Telecom rebinds, but the
call UI drops for a moment **[uncertain]** on how each OEM handles it.

**Fix:** pre-scan with `Bounded.LineReader` and the same `IMPORT_ENTRIES` and byte caps. Stop counting at the cap.

**Effort:** S.

### Still open from round 2

- **C-03 (High, operational).** `keystore.properties` still points at
  `/tmp/claude-0/…/scratchpad/signing/parley-release.jks`, with both passwords 24 characters and **identical**
  (checked by comparison; not printed). It is git-ignored and the file is 0600. The fix is unchanged: move the key
  offline, use separate passwords, rotate with a v3 lineage, and delete the properties file from the tree.
- **C-13 (Low).**
  - `gradle/verification-metadata.xml:5` has `verify-signatures=false`, and 852 components are trusted on first use
    ("Generated by Gradle").
  - `material3 = "1.5.0-alpha14"` (`libs.versions.toml:18`) ships in the release.
- **C-16 (Info).** `KeystoreSeal.seal`, `HistoryCrypto.seal` and `Aead.seal` callers for vault and records still pass no
  associated data, so blobs can be swapped between rows by someone with database write access (root, out of scope).
  Bind `table|row id|kind|generation` in the next blob version.

---

## Data-at-rest inventory

The keys used below:
- **FBE**: Android file-based encryption only.
- **Records key**: `RecordCrypto`, AES-GCM under a software key wrapped by a Keystore key, no authentication.
- **History key**: `HistoryCrypto`, the same envelope with its own alias, plus an HMAC key.
- **Caller-ID key**: `VaultCrypto` Keystore AES-GCM, no authentication.
- **Detail key**: Keystore, needs authentication within 300 s, `setUnlockedDeviceRequired`, StrongBox where the phone
  has it.
- **Vault HMAC**: a Keystore HMAC key.

"Backed up" means inside the encrypted backup (STREAM AES-256-GCM, RSA-3072 OAEP wrap, ECDSA-signed). Cloud backup and
device transfer are disabled for every domain (`data_extraction_rules.xml`, `allowBackup=false`). "Delete all data"
(`DataWipe.kt:39-63`) removes every registered store, every Keystore alias and the cache, which crypto-shreds anything
left over.

| Store | What it holds | At rest | Backup | Deleted by |
|---|---|---|---|---|
| `parley.db` `contact_meta` | Note for calls (with agenda items), Parley relations, usual app | Note and relations sealed with the records key; other columns FBE | Yes (contact notes) | Contact delete or re-key; wipe |
| `call_notes` | Notes written on calls | Text sealed with the records key | Yes | User; wipe |
| `interactions` | Circle moments and notes | Notes sealed with the caller-ID key (`InteractionStore.kt:41-44`) | Yes (Circle) | User; wipe |
| `blocked_calls` | Screened log: number, CNAP name, verdict | Name sealed with the records key; **number plain** (FBE) | Yes (blocking) | Log limit; wipe |
| `block_rules`, `speed_dial`, `number_sim`, `temporary_contacts`, `call_usage`, `call_rings` | Rules, numbers, expiry, counters | FBE | Yes, except `call_rings` and `call_usage` | User, 60-day prune (rings); wipe |
| `journal`, `journal_photos` | 30-day undo of contact edits | Payload and photos sealed with the records key (plain fallback, resealed by `RecordSealing`) | No | 30 days; wipe |
| `vault_contacts` | Private contacts | Caller copy: caller-ID key. Details: detail key (two parts) | With private contacts, only while unlocked | User, expiry, `PrivateTrash` 30 days |
| `vault_numbers` | Number fingerprints | Vault HMAC | No (rebuilt) | With entry |
| `private_calls` | Private call history | Private-calls key (wrapped by Keystore) | With private contacts | Never pruned automatically (by design) |
| `parley-history.db` `archived_calls`, `keep_forever`, `call_trash` | Parley's call archive | History key per row, plus HMAC | Archive yes; trash no | 5-year default retention; trash 30 days |
| DataStores `settings`, `people`, `history` | Preferences, label policies | FBE | Yes; safety keys wait for a PIN confirm (`SettingsRepository.kt:379`) | Wipe |
| `case_files` | Organisations, calls, hold times, menu keys, references | Document sealed with the records key; each reference sealed again; never plain | Yes, private contacts' cases excluded (**S3-05**) | Stop case file; 100-case cap; wipe |
| `menu_memory`, `to_call`, `card_links`, `card_sharing` | Keys sent, reminders, card links | Records key, never plain | Shortcuts and opt-outs; links | User; wipe |
| `my_card_identity` | Ed25519 secret, card id | Records key | Yes (on purpose) | Wipe |
| `family_safety`, `messaging` | Safe words, helpers, windows; messaged numbers | Caller-ID key, never plain | Safe words yes (not hidden ones); messaging no | User; wipe |
| `parley_network_names`, `parley_ring_facts`, `parley_call_quality`, `parley_reputation`, `parley_number_advice` | Third parties' CNAP names; ring and quality facts | History key (`SealedLineStore`), keyed by HMAC, never plain | No | 400 days (names), 60 days (facts); forgotten with the number's last call or when it becomes private |
| `private_names` | Directory switch, approved apps and their certificate hashes | FBE | Yes; duress-scoped | User; wipe |
| `parley_situations`, `parley_drive_profile`, `parley_roaming`, `parley_calling`, `parley_call_extras`, `parley_extras`, `parley_circle`, `me_card` | Settings-like documents; per-contact call time (no names) | FBE | Yes (cars excluded) | Wipe |
| `rescue_call` | Pending rescue call and last choices (name, number, clip URI) | **FBE, plain** (S3-04) | No | Cleared when it rings or is cancelled; last choices stay |
| `crash_capture`, `diagnostics` | Crash class and frames, no messages, numbers masked | FBE | No | Opt-in; user |
| `files/archive` | Archived contacts: card and whole record with photos | Records key (**plain fallback, never resealed**, S3-08) | Yes (archive section) | Unarchive; wipe |
| `files/timemachine` | 180-day contact snapshots | Records key | No | 180 days |
| `files/vault_photos`, `vault_photo_originals` | Private contacts' photos | Caller-ID key; originals sealed | Photos with private contacts | With entry |
| `files/call_backgrounds`, `contact_photos` | Call-screen pictures, kept originals | **Plain JPEG** (private contacts' too, S3-08) | Yes | Contact delete; wipe |
| `files/tunes` | Ringtones made from a name (hashed file names) | Plain WAV; read grants to Telecom and System UI only | Yes | Pruned when unused |
| `files/blocking/share.key` | Rule-pack signing secret | **Plain** (S3-08) | Never (`Secret`) | Wipe |
| `no_backup/*.keys` | Wrapped software keys | Keystore-wrapped | Never | Wipe; set-aside `records.keys.*` and `memory.keys.*` are not listed in `DataWipe` (useless once the aliases are gone) |
| `no_backup/app_pin` | scrypt hashes and the try count | Records key (**plain fallback**, S3-08) | Never | PIN off; wipe |
| `no_backup/app_lock_state`, `vault_locked` | Hiding and "Lock private contacts" flags | FBE | Never | Real PIN; unlock |
| `no_backup/shared_labels` | Label keys, bookkeeping, shield own and in | Records key | Never (rejoin by invitation) | Leave label; wipe |
| `no_backup/vault_trash`, `vault-unreadable`, `number_memory`, `contact_list_head` | Undo of private deletes; lost-key blob; number index; first screen of Contacts (no private contacts) | Caller-ID key, detail key, HMAC plus records key, records key | Never | 30 days; rebuilt; wipe |
| `device_protected/lists` | Spam packs, readable before the first unlock | FBE (device-protected) | Yes | User; wipe |
| Cache: `share/`, `transfer/`, `label_updates/` | vCards, voicemail audio, PDFs, rule exports, updates | Plain | — | 1-hour sweep at start and in upkeep (`ExportFiles.kt:126-143`) |
| Cache: `image_share/`, `qr/`, `contact_camera/`, `share/scanned-contacts.vcf` | Shared pictures, camera captures, a decrypted Secure QR card | Plain | — | On return, read or import; private copies by timer |
| Exports chosen by the user | vCard, CSV, notes text, PDFs (call history, case file) | Plain, or encrypted vCard (scrypt with the same envelope) | — | The user |
| Backups and sync folder | Everything above marked "Yes" | Encrypted, signed | — | Rotation; the user |
| Logs | — | R8 strips every `Log` level in release (`proguard-rules.pro:23-31`) | — | — |
| Android's call log and contacts | Telecom's rows, address book | Outside Parley | — | Private calls swept within about 1 s; archived names stay (S3-07) |

---

## Exposure points

### Exported components and FileProvider

**Exported activities**
- `MainActivity`: internal actions gated by `InternalEntry` (C-06 fixed). The relay in S3-09 is a residual.
- `PickerActivity`: `LockedActivity`; returns provider URIs only, never private or archived contacts.
- `NumberActionActivity`: asks for the unlock.
- `TileLongPressActivity`: S3-09.
- Two widget config activities: `LockedActivity`.
- `ExportedComponentsTest` enforces `LockedActivity` but not "doesn't relay into `InternalEntry`" (S3-09).

**Exported services**
- Every service is guarded by a platform-held bind permission: the five tiles, the in-call service and the screening
  service.

**Exported receivers**
- `MissedCallReceiver` needs `MODIFY_PHONE_STATE`.
- Every other receiver is non-exported.

**Exported providers**
- `PrivateDirectoryProvider` is disabled until it is turned on, needs `READ_CONTACTS`, answers one exact number per
  query, approves an app by its certificate hash, applies a rate limit, and fails closed (`hidesPrivateNames`).
  - The caller package is taken from a parameter only when the calling package is the contacts provider (sound).
- `VaultPhotoProvider` is not exported.

**FileProvider (`file_paths.xml`)**
- It covers only cache sub-folders and `files/tunes`.
- Tunes get grants for `com.android.server.telecom` and `com.android.systemui` only.
- Path traversal on restore is prevented (`CallerTune.isTuneFile`, `CarriedSections.kt:126-138`).

### Notifications (6.x)

| Feature | Lock screen | Verdict |
|---|---|---|
| Missed calls, with the network's name (6.2.1) | `PrivateNotice`-style public version; network name only when `LockScreenCaller.showsName` (`NetworkName.kt:181`) | OK |
| Incoming and ongoing calls, rescue call | Masked through `forLockScreen`; public version | OK |
| Chapter end, To call, Circle, jobs, backup, sync | `PrivateNotice` (private, neutral public title, local only) | OK |
| **Blocked, silenced and quiet-hours calls** | No visibility and no public version; private names shown | **S3-01** |
| Silenced-call notification (`CallNotifier.kt:386-398`) | No visibility set, but the call passed in is already masked while locked (`:87-91`) | OK, but set `callVisibility()` for consistency |

Call notifications aren't `setLocalOnly`, so they reach a paired watch with the full name whatever the lock-screen
mode **[uncertain]** on Wear bridging of CallStyle (nit).

### Widgets, tiles, shortcuts and Recents

- **Widgets.** They follow Parley's lock as well as the keyguard (C-14 fixed, `CircleWidget.kt:126-131`). They never
  read the vault.
- **Tiles.**
  - The Situation, Expecting a call and Private contacts tiles call `unlockAndRun` before changing anything.
  - The Situation tile's subtitle names the active Situation on the lock screen (nit).
  - The End call tile ends an emergency call from the lock screen with one tap. A person in the room can already press
    End on the call screen, so this isn't a new capability, but it is an accidental-tap risk (nit).
- **Recents.** `LockedActivity` uses `setRecentsScreenshotEnabled(!appLock)` on Android 13+ and FLAG_SECURE before.
  `InCallActivity` uses FLAG_SECURE only with "Hide screen content", and removes its task when calls end.
- **Launcher shortcuts.** Favourites from the address book only. A pinned shortcut calls without Parley's lock, by
  design; `ShortcutActivity` is not exported.

### Clipboard, TalkBack and Android Auto

- **Clipboard.** `Clipboard.copy` is sensitive by default. Case references are copied sensitive
  (`CaseScreen.kt:232`). Only My card fields and public profile URLs opt out. OK.
- **TalkBack.** Masked calls replace the title before semantics are built (`InCallActivity.kt:124`), and the agenda
  shows a count on the lock screen. No leak found.
- **Android Auto.** Parley has no Car app. Auto's phone UI reads Android's call log and contacts:
  - private contacts never appear;
  - archived contacts' past names do (S3-07).
  - The drive profile's "say who's calling" speaks through the car and respects discreet mode and the duress hiding.

### Share intents and the contacts provider

- **Incoming shares.** `SEND`/`VIEW` URIs from other apps go through `SharedUris`. Contact links are limited to the
  contacts authority (C-17 fixed, `IntentRoutes.kt:214-217`).
- **Outgoing shares.** Shared copies of private contacts' images are swept by timer (`ImageExport.kt`).
- **Leaks back into the provider.** Make private takes back relation mirrors, the journal and snapshots
  (`ContactConversions.kt:55-62`). Archive doesn't (S3-07).
- **Imports into the provider.** Arbitrary mimetypes (S3-02).

---

## Private contacts and the vault: lock model end to end

- **5-minute window.** The detail key needs authentication within 300 s (`VaultCrypto.kt:53,240-250`), and any phone
  unlock counts. With the app lock off, anyone holding a just-unlocked phone opens private details. This is documented
  and accepted. "Keep private details locked" in a duress session refuses the key even inside the window
  (`detailLocked`). Good.
- **`lockAll` persistence.**
  - `vault_locked` lives in `no_backup` and survives a restart (`VaultCrypto.kt:138-160`).
  - `VaultLock.noteUnlocked` ignores opens while it holds.
  - If `createNewFile` fails, the lock holds in memory only and is gone after a restart. This is minor: report it, or
    retry the write as `app_lock_state` does.
  - The unlock path ignores the Parley PIN (S3-06).
- **Duress and discreet consistency across 6.x.**
  - **Consistent:**
    - Recall (`RecallSources.kt`, four `Concealment` checks);
    - Agenda (`AgendaStore.kt:53,70`; call-screen `Agenda.shown`);
    - case files on screen and in PDF (`CaseData.kt`);
    - network names (never written for private numbers; vault lookups aren't concealed, so `keep()` still says
      FORGET while hiding, `AppTelecomDependencies.kt:640-648`);
    - Archive (treated as a saved contact);
    - safe words;
    - the shield list;
    - exports (`ContactExport.kt:153`);
    - Delete all data;
    - restore.
  - **Inconsistent:** blocking notifications (S3-01), Rescue call (S3-04), case files in backups (S3-05).
- **Fail-open vs fail-closed.**
  - **Fail closed:**
    - the PIN record unreadable → the PIN field stays (`AppPinStore.kt:142-150`);
    - the try count unwritable (`:174-184`);
    - an unreadable hiding file counts as hiding;
    - `hidesPrivateNames` 1.5 s timeout;
    - `detailNeedsUnlock` (`VaultCrypto.kt:465-480`);
    - network names `private == null` → FORGET;
    - case and agenda emergency checks default to `true`.
  - **Fail open, by design:**
    - screening (3 s timeout → allow; spam lists unreadable → allow);
    - `authenticate()` with no secure lock screen → allowed. With a PIN, `DuressMachine.otherUnlock` still refuses
      (`AppLock.kt:221-232`).
  - **Fail open, not by design:** the plain fallbacks in S3-08.

---

## Crypto review

- **AEAD.**
  - One wrapper (`common/crypto/Aead.kt`): AES-256-GCM, 96-bit random nonces from `SecureRandom`, 128-bit tags.
  - Keystore-sealed blobs use the Keystore's own IV (`KeystoreSeal.kt`).
  - Nonce reuse risk is negligible: random 96-bit nonces under per-store keys, well below 2^32 messages each.
- **STREAM backup envelope.**
  - The nonce is a 7-byte prefix, a 32-bit counter and a last flag, with the header as AAD (`BackupCrypto.kt:37-53,490`).
  - Truncation, reordering and header tampering are covered.
  - The counter wraps at 2^32 segments (256 TiB), so there is no practical concern.
- **KDFs.**
  - scrypt 2^15/8/1 for new backups, label passphrases and encrypted vCards.
  - PBKDF2 at 600k rounds stays readable. Read caps are 2M rounds, scrypt ≤ 2^16, 64 MB and p ≤ 4.
  - At most 2 bundle derivations per open (`:161,322-330`).
  - QR codes accept exactly one setting.
  - PINs use scrypt 2^14 with a shared salt and constant-time comparison of both hashes.
- **Asymmetric.**
  - RSA-3072 OAEP-SHA256 with MGF1-SHA256 (`:447-452`).
  - ECDSA P-256 device signatures, endorsed by the bundle (RSA-PSS).
  - Ed25519 for cards, labels and packs. Small-order points are refused (`Ed25519.kt:147,174,184`). Fingerprints are
    128 bits (`:196`).
- **Key rotation.**
  - Vault detail generations, re-sealed in one transaction.
  - Records-key replacement only on provable loss, with old keys kept for reads (`RecordCrypto.kt:135-173`).
  - Label key rotation on member removal.
  - There is no scheduled rotation of the history or records keys. That is acceptable: they are random and wrapped.
- **Encrypted vCard.** The same envelope with one passphrase wrap. The "Strong" passphrase gate applies. The trust
  conclusion drawn from it is wrong (S3-10).
- **Family shield HMAC.**
  - HKDF-SHA256 of the label key with info `parley/v1/family-shield` and salt = label id, then HMAC-SHA256 over E.164,
    cut to 128 bits (`FamilyShield.kt:53-62`).
  - It is correct for confidentiality against outsiders. Members can enumerate, as documented.
  - The integrity of verdicts rests on members' journal signatures. The policy issue is S3-11.
- **Sealed stores.**
  - Correct shape (fail closed; never overwrite unreadable values) in `MenuMemoryStore`, `CaseFileStore`,
    `SealedLineStore`, `FamilySafetyStore` and `FolderSync` (`:233-235` refuses a plain key).
  - The exceptions are in S3-08.
- **Keystore failure handling.**
  - `classify` separates locked, lost and unavailable (`VaultCrypto.kt:450-457`).
  - `HistoryCrypto.isPermanent` and `isProvable` keep data on ambiguous errors.
  - Lost blobs are set aside, never overwritten.
  - This is a strong design. Keep it.

---

## Untrusted input

| Input | Bounds | Parser | Notes |
|---|---|---|---|
| vCard file or share | `Bounded.LineReader`, card and entry caps (`VCardStream.kt:77-102`) | ezvcard 0.12.1, with jsoup, freemarker and jackson excluded | Pre-scan unbounded (S3-12); flags and mimetypes (S3-02) |
| CSV, contacts and calls | Same readers; call CSV capped (`CallHistory.kt:810-816`) | Own parser | The calls import writes Android's call log; user-initiated |
| Encrypted vCard | Envelope header caps, KDF caps | Envelope then vCard | S3-10 |
| `.parleyupdate`, `.parleyinvite` | 24 MB sealed, 48 MB expanded, 5,000 files, per-file cap (`SharedLabelUpdates.kt:63-73,180-199`) | Signed JSON | Strong: label, epoch and key bound; signature before files; monotonic per sender |
| QR (image) | Decoded at bounded sizes (`QrScanner.kt:32-55`) | zxing 3.5.3 | `parley://` links: KDF exact; look-alike URL checks |
| Shared or selected text | `NumberActionActivity`; asks before acting | Own | OK |
| Rescue audio | `OpenDocument`, persistable read grant | MediaPlayer (media.extractor sandbox) | OK; nothing is recorded |
| Rule templates | 200 rules, 500 ranges, signature required (self-signed) | JSON | Allow-by-name rules need a warning (S3-03) |
| Backups | Header, wrap and KDF caps; signature origin; settings off by default | Envelope | Blocking part restored by default even for unsigned backups: a forged backup can add allow rules (nit, C-10 residual) |

Injection into the provider: arbitrary `X-ANDROID-CUSTOM` mimetypes and ringtone URIs (S3-02). Paths: tune and label
file names are allowlisted; the case PDF file name is filtered (`CasePdf.kt:96-99`).

---

## Telecom

- **CNAP trust model.** 6.2.1 stores only cleaned names, never for saved or private numbers, tagged "From the network"
  in lists, and gated on the lock screen. Good. The live call path still uses the raw value (S3-03).
  - "This number never calls you" (`CallUiMapper.kt:131-133`) is a good spoofing defence for saved organisations.
  - STIR/SHAKEN `FAILED` feeds screening.
- **Screening bypasses.**
  - Allow-by-name (S3-03).
  - Planted archived contacts (S3-02).
  - Shield poisoning in Block mode (S3-11).
  - The 3 s timeout fails open by design.
  - Repeat callers and "Expecting a call" are intended exceptions, not bugs.
- **Emergency safety.** There is no path found that blocks or delays an emergency call:
  - `CallGate.check` returns null for emergency numbers before any question; `place` ignores remembered and label SIMs
    (`CallGate.kt:23-24,59-64`).
  - `ShortcutActivity` routes emergency numbers through `CallGate` (`:59-63`).
  - The lock screen's emergency keypad (`AppLock.kt:480-526`) dials only platform-confirmed numbers and falls back to
    the system dialer.
  - Screening, hand-off, silence and limits are bypassed for emergency calls and their call-back window
    (`EmergencyPolicy.kt:55-62`; `CONFIRM_BEFORE_CALL` and `SIM_CHOICE` are declared but unused, because `CallGate`
    short-circuits earlier; consider deleting them).
  - Agenda, case files and menu memory treat a failed emergency check as emergency (fail safe).
- **Rescue call interactions.**
  - It never starts while a real call exists, and yields within at most 1 s, or at once via `CallManager`
    (`RescueCall.kt:110-121,203-208,228-242`).
  - No Telecom call exists, so it never reaches the call log, archive or backups.
  - `MODE_IN_COMMUNICATION` is restored only if still set.
  - Lock-screen masking applies to it.
  - The issue is the duress view (S3-04).

---

## Privacy posture

**Minimisation, good:**
- number-memory hashes;
- shield hashes;
- menu-memory PIN guard;
- references sealed twice;
- no network;
- crash reports opt-in and scrubbed;
- logs stripped.

**Minimisation, weaker:**
- Case files are on by default for organisations (`CaseMode.AUTO`) and keep hold times and menu keys of every call.
  This is disclosed, but opt-in per organisation would be more conservative.
- Network names keep third parties' CNAP names for 400 days, with no switch. The owner's pending network-name toggle
  (the 148th setting) is the right place: the toggle, plus retention tied to the call-history trim.

**Retention defaults:**

| Data | Retention |
|---|---|
| Call archive | 5 years |
| Undo journal, call trash, vault trash | 30 days |
| Snapshots | 180 days |
| Ring and quality facts | 60 days |
| Network names | 400 days |
| Case files | 100 cases × 300 calls |
| Private calls | Never pruned (documented) |

**Family member or thief with the unlocked phone:**
- **App lock off:** everything, including private details for 5 minutes after the phone unlock. This is the
  documented trade-off.
- **App lock on:** widgets show counts and the vault is protected. What still shows:
  - the call screen (notes, agenda) during a call;
  - blocking notifications (S3-01);
  - Situation tile names;
  - launcher shortcut names;
  - the vault, to anyone knowing the phone's code after "Lock private contacts" (S3-06).

**User rights:**
- **Export:** vCard, CSV, notes text, encrypted vCard, call-history CSV/PDF, case PDF, full encrypted backup.
- **Erasure:** "Delete all data", plus per-item deletes.

Gaps:
- No export of network names, number memory or the screened log in an open format. This is minor; the screens show
  them.
- "Delete all data" doesn't mention user-folder backups and exports. One line in the dialog would cover it.

---

## Dependencies

| Library | Version | Needed? | Notes |
|---|---|---|---|
| ezvcard | 0.12.1 | Yes | Optional HTML and jCard dependencies excluded (`core/common/build.gradle.kts:21-26`); no known CVEs |
| zxing core | 3.5.3 | Yes (offline QR) | Latest; no known CVEs |
| libphonenumber, geocoder | 9.0.40, 3.40 | Yes | Current |
| kotlinx.serialization | 1.9.0 | Yes | JSON only; no polymorphic deserialisation of untrusted input found |
| AndroidX (Room 2.8.4, WorkManager 2.11.2, DataStore 1.1.7, Navigation 2.9.6, Core 1.17.0) | Current stable | Yes | — |
| Compose Material 3 | **1.5.0-alpha14** | For Expressive | Alpha in a release; pin to beta or stable when Expressive is public (C-13) |
| profileinstaller, splashscreen, exifinterface | Stable | Yes | — |

No network, analytics or crash SDKs. The lists-updater companion holds `INTERNET` (`lists-updater/AndroidManifest.xml:7`)
and is a separate, signature-permission-guarded app. Its packs are verified and pinned. Supply-chain verification is
still SHA-256 trust-on-first-use (C-13).

---

## Verified fixed since round 2 (don't break these)

| Round 2 | Now |
|---|---|
| C-01 | `PrivateNameAccess` duress-scoped (`endSession`); approvals refused while hiding |
| C-02 | `DeleteAllData` uses the concealed view and refuses quietly |
| C-04, C-10 | Restore through `DuressPolicy`; `SECURITY_KEYS` covers privacy switches; settings unticked by default |
| C-05 | Approval sheet inside Parley, package plus certificate SHA-256, PIN |
| C-06 | `InternalEntry` alias plus `INTERNAL_ACTIONS` (residual: S3-09) |
| C-07 | Sweep on call-log change, sub-second |
| C-08 | `withoutNotes()` under the Name mode (`CallModels.kt:237-241`) |
| C-09 | `ExportFiles.cleanup` sweeps `share`, `transfer` and `label_updates` hourly |
| C-11 | `device_*` and `external` domains excluded |
| C-12 | `AppLock.confirm` → `PinConfirm` |
| C-14 | Widgets follow `AppLock.lockedFor` |
| C-15, C-18 | 128-bit fingerprints; small-order rejection |
| C-17 | `contactLink` limited to the contacts authority |
| — | Lookup provider removed (5.6); ExportedComponentsTest in place |

**Strengths to keep:**
- fail-closed sealed stores;
- `CallGate` emergency short-circuit;
- `NetworkName.keep`, where null counts as private;
- the label-update format;
- `Bounded`;
- `KdfPolicy` caps;
- PIN backoff counted before checking;
- `PersistentStores` as the single registry (it made this inventory checkable).

## Nits

- `CallNotifier.buildSilenced` (`:386-398`): set `callVisibility()` and a public version like its siblings.
- `CaseReferenceRow`: the comment says "phone and Parley unlocked", but only the keyguard is checked
  (`CaseFileUi.kt:59-60,72-76`).
- Agenda items and notes on the call screen follow the keyguard only, not Parley's app lock (design choice; document it).
- Situation tile subtitle on the lock screen; End call tile on emergency calls (ask, or ignore while `isEmergency`).
- `VaultCrypto.lockedByPerson` setter swallows a failed file write (`:143-147`).
- `DataWipe` doesn't remove `records.keys.lost-*`, `memory.keys.lost-*` or `vault_calls.keys.lost-*` (crypto-shredded,
  but tidy them).
- `EmergencyPolicy.Safeguard.CONFIRM_BEFORE_CALL` and `SIM_CHOICE` are unused.
- Restore: the Blocking part is ticked by default for unsigned or unknown-signer backups. Untick it as Settings is, since
  it can add allow rules.
- Call notifications aren't `setLocalOnly` (watch mirroring).

---

## Prioritized hardening plan

All items keep the house rules: no new permission, no new setting (the shield opt-out lives in the Block question), no
network, English only.

| Order | Release | Items | Effort |
|---|---|---|---|
| 0 | Now (owner) | **C-03**: move the release key offline, use separate passwords, rotate with a v3 lineage, delete `keystore.properties` | owner |
| 1 | 6.2.2 "Quiet names" | **S3-01** (blocking notifications through the private-name helper and `PrivateNotice`; no private block logged outside the vault); **S3-04** (Rescue screen concealed while hiding; seal `rescue_call`); **S3-05** (case-file backup honours `Concealed.NOTES`); one duress-matrix test covering notifications, Rescue and backup extras | S+S+S |
| 2 | 6.2.2 | **S3-02** (import preview with archived and private flags unticked; `ScannedCard` flag rule for all plain imports; mimetype allowlist); **S3-03** (no allow-by-name, or only when STIR `PASSED`; match and show the cleaned name; "From the network" on the answered screen) | S+S |
| 3 | 6.3 | **S3-06** (PIN before ending "Lock private contacts"); **S3-07** (`takeBack` on archive, clear `CACHED_NAME` or say so); **S3-09** (System UI check on the tile relay plus a test) | S each |
| 4 | 6.3 | **S3-08** (no plain fallback for `app_pin` and archive; seal `share.key` and private backgrounds; add archive to `RecordSealing`); **S3-12** (bounded pre-scan); **S3-10** (sealed ≠ trusted; signed export marker) | S–M |
| 5 | 6.3 | **S3-11** (two-member threshold for Block; "also shared with" in the Block question); network-name retention tied to the trim, plus the owner's toggle (148th setting) | S |
| 6 | 6.4 | **C-13** (PGP `trusted-keys` for Google, JetBrains and Square; regenerate hashes on a clean machine; leave Material 3 alpha when Expressive is stable); **C-16** (AAD `table|id|kind|gen` in the next blob version with a migration test) | M |
| 7 | Any | Nits | S |

**Tests to add with the fixes:**
- `BlockingNotifierPrivacyTest`: a vault hit while discreet, while hiding, and in each lock mode.
- `ImportFlagsTest`: plain vs sealed cards for archived, private, ringtone, voicemail and custom mimetypes.
- `CallerNameAllowTest`: allow-by-name refused, or only when verified; zero-width evasion of a block rule.
- `DuressBackupTest`: every `BackupExtras` exported while hiding contains no `Concealed.NOTES` content.
- `ExportedComponentsTest`: no exported activity forwards into `InternalEntry` based on caller-supplied extras.
