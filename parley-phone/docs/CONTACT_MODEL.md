# One contact, several variants

Parley has one kind of contact. Where it is kept, and whether it deletes itself, are **variants** of that contact,
not separate types. Inside Parley every contact has the same page, the same editor and the same features; the
variants differ only in what other apps can see and in the few things that exist only in Android's address book.

Code: `ContactRef`, `ContactVariants`, `ContactCapabilities` and `PrivateListing` in
`core/common/.../common/people/`; `ContactKeyedStores` in `core/common/.../common/storage/`; the conversions in
`app/.../ui/contact/ContactConversions.kt`; re-keying in `core/data/.../data/people/ContactKeys.kt`.

## The model

```
ContactRef                         (which contact)
├── Device(contactId)              in Android's address book: apps with the contacts permission can read it
└── Private(vaultId)               only in Parley's encrypted vault: invisible to every other app

ContactVariants(storage, expiresAt) (what it is)
├── storage   DEVICE | PRIVATE     where it is kept
└── expiresAt null | time          Temporary: deletes itself then (with or without its call history)
```

- **One id for navigation and lists.** `ContactRef.navId` is the address book's contact id for a device contact and
  the negated vault id for a private one (the convention the editor already used). `Routes.contact(id)` opens either;
  `Routes.Vault(id)` and `Routes.vault(id)` are kept for old links, shortcuts and notifications and open the same page.
- **One key for Parley's own data.** Everything Parley keeps about a person beside the contact (the note for calls,
  the usual app, the Circle rhythm, relation links, dates remembered yearly, logged moments and promises, the
  call-screen picture, the Do Not Disturb record) is stored under a *Parley key*: the lookup key of a device contact,
  `parley-private:<vaultId>` of a private one (`ContactRef.privateKey`). The stores are listed in `ContactKeyedStores`,
  which a unit test keeps in step with `PersistentStores`. A private key is never resolved through the address book,
  so a key sweep can't hand a private contact's notes to a namesake.
- **Temporary asks first.** With "Ask before deleting temporary contacts" (on by default, on the Temporary contacts
  screen) a temporary contact whose time is up is *due*, not deleted: the daily upkeep posts one notification without
  names (Delete / Keep 7 more days / Keep permanently, `DueTemporaries`, rules in `TemporaryDue`), Temporary contacts
  shows the same choice, and an unanswered one stays, with a reminder every 3 days. Off, expiry deletes as before.
  While due, a private one isn't named by caller ID (expired vault entries never match, F15).
- **Variants combine.** A private contact can be temporary and so can a device contact. Future variants (a work-profile
  contact, a SIM contact: read-only) fit the same shape: another storage, or another attribute.

### What stays where for a private contact

| What | Where | Readable while the vault is locked? |
|---|---|---|
| Name, numbers, number labels, job/company line, "who is this" line, note for calls, star | Vault caller-ID copy (`VaultRepository`, caller-ID key) | Yes: caller ID, lists and the lock screen need them (unchanged) |
| Label membership (group id + title per label), own ringtone, "send to voicemail", vibration pattern and auto-answer (4.4) | The same caller-ID copy, the only place they are kept (`VaultRepository.updateCallerChoices`; read through `PrivateLabelStore`) | Yes: the call path applies them while the phone is locked, and they change without unlocking |
| Every other field (emails, addresses, dates, relations, websites and profiles, notes, handles…), the usual app | Vault details, main part (auth-bound detail key) | No: the page asks to unlock, in place |
| The original address-book record it was made private with (photo included) and its carried interactions | Vault details, extra part (same key; opened only by Make visible, backups and the first seeding) | No |
| Photo | Vault photo file, sealed with the caller-ID key | Yes (the call screen shows it) |
| Circle rhythm, relation links, yearly dates, logged moments, call-screen picture | Parley's own stores under `parley-private:<id>` (moments' notes sealed as for every contact) | Parley only; the page shows them after unlock |
| The signed card it is linked to and an update waiting (4.6) | `card_links` under `parley-private:<id>`, sealed with the small-records key | Parley only; the page offers the update after unlock, Apply asks for it |
| Calls | Private call history (when "Private call history" is on) | As before |
| Own call time limit, talk-time reminder, "never limit" | Call-time settings under `parley-private:<id>`, **without a name** (lists show it from the vault) | Parley only |
| A deleted private contact | `no_backup/vault_trash`: the entry exactly as stored (details still under the detail key), its photo, private calls and Parley data, the whole file sealed with the caller-ID key, 30 days (`PrivateTrash`) | Counted without opening; listed only after the vault's unlock |
| What number memory remembers about a deleted private contact, or a number in a private contact's notes | The number-memory index (`no_backup/number_memory`): keyed hashes of the numbers and sealed hints, rebuilt from the stores above | Never shown then: the line appears only while the vault is unlocked, and never in discreet mode |

### Opening a private contact

Its page shows at once what the caller-ID copy holds (name, numbers and their types, job, the "who is this" line, the
note for calls, pronouns, star, labels, photo; `VaultRepository.callerCopy`), then the sealed details fill in, opened
once (`VaultRepository.open`, which also tells a lost key). The details are sealed in two parts
(`VaultCrypto.sealDetailParts`, a two-part blob in the same column): the main part is what the page and the editor
show (a few kB); the extra part holds the original record with its photo and the carried interactions, read only by
Make visible, backups and the first seeding. An edit keeps the extra part sealed as it is. Entries sealed as one blob
before 4.5 are split the next time they are opened, and by a one-time migration after the vault's unlock
(`splitDetails`); key upgrades re-seal both parts, and "Recently deleted" keeps the blob as it is. Opened details stay
in memory for a minute while the phone is unlocked (never while it is locked) and are forgotten whenever Parley's app
lock engages: "Lock now", "Lock immediately" on leaving the app, the lock timeout on return, and, with the app lock
on, the screen going off (`AppLock.engage`, `AppLock.onScreenOff`). A kept original photo shows in
the header from a sealed 1024 px copy (`OriginalPhotos`, made the first time), never by opening the whole original.
Measurements: docs/PERFORMANCE_BENCHMARKS.md ("4.5: opening a private contact").

**Downgrades.** A Parley older than 4.5 doesn't know the two-part blob: it reads its generation as 0, reports the
details as lost and, on its next key upgrade, could delete the key that still opens them. The format is not changed to
hide this (old builds would misread any new marker the same way), so a downgrade must start from a data wipe. Android
refuses to install an older version over a newer one without one (only `adb install -d` or an OEM rollback can), and
the release notes say so (docs/RELEASING.md). Make a Parley backup with private contacts before any rollback.

Backups: private contacts' Parley data is written only in the private-contacts section of a backup (which needs the
vault unlocked), never in the Contact notes, Circle or Call time sections every backup has; a restore puts it back under
the restored contact's new key. Their labels travel there by title (group ids mean nothing on another phone) and are
found again by title. "Recently deleted" copies are never backed up.

### Labels of a private contact

A label stays the address book's group: its name, id and account are the group's, and Parley's label screens (create,
rename, merge, delete) work on groups as before. Only *who is in it* is Parley's for a private contact
(`PrivateLabels`, `PrivateLabelStore`): nothing is written to the address book, so no other app can tell that a
private contact is in a label, or exists.

- A membership keeps the group's id and title. The id follows renames made anywhere; the title finds the label again
  after a restore or when a label is made again. A label that can't be found is hidden, never dropped by an edit.
  Labels are one per title across accounts, as the Contacts tab and every rule name them.
- Renames, merges and deletes made in Parley move or remove private memberships first.
- Everything that reads labels reads them too: label pages (members, Message all, Email all after the vault's unlock,
  remove from label), the Contacts filters (any, all, Unlabelled) and counts, the editor's Labels chips, the selection
  bar's "Add to label", label ringtones, label rules and off hours (`CallScreener`), the label's SIM and Circle rhythm
  (`ExtrasStore`) and label call-time limits (`CallTimePlanner`).
- Make private turns the contact's group rows into memberships; Make visible turns the memberships back into group
  rows (in a group of that label its account can hold) and removes the rows of labels it left while private.

### Ringtone and "Send to voicemail" of a private contact

Android's ringer and Telecom read a contact's ringtone and "send to voicemail" from the address book before any app
sees the call, and they never see a private contact. Parley applies both itself, the way it already plays a rule's or
a label's ringtone: its call screening (`CallScreener`, from the screening service and the in-call service) finds the
caller in the vault, then

- **ringtone**: Parley's own ringer (`CallRinger`) plays it instead of Telecom's, with Telecom's vibration, only in
  normal ringer mode with Do Not Disturb off, like every Parley tone ("Why did my phone ring?" says the contact's
  tone). A rule's tone still wins, as for everyone.
- **send to voicemail**: the call is declined (a plain decline, not "unwanted", so the network sends it to voicemail),
  with no ring, no missed-call notification, and nothing in the blocked-calls log. Telecom writes any call a screening
  service disallows to the call log as *blocked* (`setSkipCallLog` only drops it), so when Parley is the phone app its
  screening service lets the call through silenced and the in-call service declines it at once from the remembered
  verdict: the call log shows it as declined. With only the call-screening role nobody else would decline it, so the
  screening service rejects it, and Android logs it as blocked.

Entries saved before the caller-ID copy kept these (and the star and labels) had them only in their sealed details and
the address-book record they were moved in with. They are copied into the caller-ID copy once, when the vault is next
unlocked (`VaultRepository.migrateCallerChoices`, and for one entry whenever its details are opened); until then nothing
is overwritten with the empty defaults (Make visible keeps what the record put back).

The call path runs screening whenever a private contact has one of these or a label (`VaultCallChoices`). This needs
Parley as the phone app (or the call-screening role), like every screening feature.

## Features by storage

"Before" is 4.2 (a separate, reduced page for private contacts); "Now" is this change.

| Feature | Device | Private before | Private now |
|---|---|---|---|
| Contact page | ContactDetailScreen | VaultDetailScreen (reduced) | **Same page**; header chip "Private · hidden from other apps" |
| At-a-glance line (last talked, next date, promises) | Yes | No | Yes |
| Contact info: numbers, emails, addresses with map links | Yes | Numbers, emails, addresses (no map links) | Yes |
| Message or call on… per number, usual app | Yes | Sheet only | Yes (apps found by number; private contacts have no apps' own rows, which live in the address book) |
| About: dates, websites, relations (tap opens them), notes | Yes | Websites, note | Yes |
| Note for calls | Yes | Read-only | Yes (kept sealed; still shown on the call screen while locked) |
| Timeline, full timeline screen | Yes | Call list only | Yes (private call history + moments + call notes + dates) |
| Call insights | Yes | No | Yes (from the private call history; "Keep forever" belongs to the phone's history only) |
| Circle: add, rhythm, Stay in touch, promises, Log interaction | Yes | No | Yes |
| Circle list | Yes | No | Yes (with the lock badge). Reminder notifications still never name private contacts |
| Call-screen picture | Yes | No | Yes (page and editor) |
| Labels: page, filters, editor chips, Add to label, label ringtone, SIM, rhythm, rules, limits | Yes | No | Yes, membership kept sealed by Parley (see "Labels of a private contact") |
| Ringtone, Send to voicemail | Yes (Android) | No | Yes, applied by Parley's call screening and ringer |
| Vibration pattern (haptic caller ID), auto-answer, pronouns (4.4) | Yes (Parley, by lookup key; pronouns in a Parley data row) | No | Yes (caller-ID copy; pronouns sealed in the details and on the caller card), moved both ways by the conversions |
| Call time limit, talk-time reminder | Yes | No | Yes (by its Parley key; the limit keeps no name outside the vault) |
| Signed card updates ("Ana sent an updated card", 4.6) | Yes | No | Yes (the link by its Parley key; Apply edits the sealed details; never named in discreet mode) |
| Date chips on the page ("Add birthday") | Yes | No | Yes (into the sealed details) |
| Multi-select in Contacts | Yes | No (long-press didn't select) | Yes: star, Add to label, Message all, Introduce, Delete automatically…, Make visible, Delete. Share, Export, Copy as text and Merge act on the device contacts and say how many private ones they left out |
| Favourites (star) | Address-book star | No | Parley's own star (other apps never see it); in the Favourites tab with the lock badge |
| Default number/email | Yes | No | Yes (kept in the sealed details) |
| QR code | Yes | Secure QR only | Yes: plain QR after a privacy confirmation, secure QR as before |
| Block numbers, "allow by name" | Yes | No | Yes |
| Temporary: make temporary, change date, keep permanently | Yes | Expiry menu | Yes, same rows |
| Delete | Yes (History & undo) | Yes | Yes, with "Deleted private contacts" in History & undo: a sealed copy for 30 days (with the photo as picked and the relations other contacts link to it), listed after the vault's unlock, restored whole. When the copy can't be kept nothing is deleted, and "Delete without a copy" is offered |
| Editor | Full | Full fields, no call-screen picture | Full, plus call-screen picture and relation links |
| Contacts list, search | Yes | Separate "Private" view | **In the one list** with a lock badge; the "Private" chip is a filter; selectable |
| Keypad results, T9 | Yes | Separate rows with an emoji lock | Same rows as contacts with the lock badge |
| Caller ID, missed calls, lock screen | Yes | Yes | **Unchanged** (caller-ID copy; discreet mode shows only the number) |
| Other apps (ContactDirectory, private-name lookup for approved apps) | Address book | Approved-app lookup only | **Unchanged** |

### Still different, and why (`ContactCapability.deviceOnlyReason`)

| Only device contacts | Why |
|---|---|
| Share as a vCard file; in multi-select also Export, Copy as text and Merge | The file (or the clipboard) is handed to other apps, which could keep it; merging makes an address-book contact (QR codes are offered instead) |
| Version history; a copy of each edit in History & undo | Snapshots and edit copies are plain copies of the address book. A private contact's edits keep no copy: only a deleted one is kept, sealed ("Deleted private contacts") |
| Accounts, linked copies, "other fields" | Accounts, linking and rows written by other apps exist only in the address book |
| Copy to SIM | A SIM card is readable by any phone it is put in |
| Home-screen shortcut | The launcher (another app) would store the name and number |
| A label's "Allow through Do Not Disturb" | Android decides who rings through Do Not Disturb from starred address-book contacts; starring a private contact would put it in the address book. The label page says how many of its members can't ring through |
| Ringtone and "Send to voicemail" without Parley as the phone app | Android's ringer and Telecom never see a private contact; only Parley's call screening applies them |

## Conversions

From the page (**Settings for this contact** and ⋮) and, for the expiry, from the editor:

| From → to | How | Lossless |
|---|---|---|
| Device → Private ("Make private") | `ContactConversions.makePrivate`: the address-book record (every row, the photo, labels) is sealed into the vault (`VaultMoves.moveIn`); its labels, ringtone, "send to voicemail" and star become the entry's own; Parley's data is re-keyed to `parley-private:<id>` (`ContactKeys.rekey`, call-time entries included, their name dropped); a temporary date moves to the vault entry; relations Parley wrote for it on other contacts ("Child: Sam" on Ana) are taken back where still unchanged, and `relation_mirrors` forgets it (`RelationMirrors.takeBack`, also run when a device contact is deleted); no journal entry or snapshot stays | Yes; synced copies disappear from other apps after the account's next sync |
| Private → Device ("Make visible to other apps") | `ContactConversions.makeVisible`: the stored record goes back (accounts kept when still writable), edits made while private on top (`VaultMoves.moveOut`); the private call history goes back to the phone's call history **before** the entry (their only copy) is deleted, and if it can't (no permission, a call that can't be opened now) the inserted contact is taken back and nothing changes; the star, ringtone, "send to voicemail" and labels as they are now become the address book's; Parley's data is re-keyed to the new lookup key, read a few times while Android joins the contact, and when it still can't be read everything waits under the private key until the next key sweep (`ContactKeys.rekeyLater`), never forgotten (a call-time limit gets the name back); the date becomes the address book's temporary flag, recording only the raw contacts this move inserted (Android may join them with the user's own copy or a messenger's, which expiry must never delete) | Yes |
| Permanent → Temporary, date changes, Temporary → Permanent | The vault entry's expiry, or `TemporaryContactStore` | Yes |

Both conversions ask first and say what other apps will or won't see; when Make visible changes nothing, the page says
why. They run in the app's scope, so leaving the page
never leaves half a conversion. Undo is the opposite conversion (History & undo can't hold a private contact, by
design). The Robolectric test `ContactConversionsTest` runs device → private → device and checks the note for calls,
Circle membership, logged moments, the call-screen picture, labels (changed while private too), the ringtone, "send to
voicemail" and a temporary date, and that nothing is left under the old key. It also covers an entry made private before the
caller-ID copy kept the star, labels and ringtone, a temporary contact joined by Android with the user's own copy (only
the restored copy expires), a lookup key that can't be read yet (nothing is forgotten), private calls that can't go
back (nothing changes), a delete whose copy can't be kept, and relations Parley wrote for a contact made private. `PrivateLabelStoreTest` covers the label
membership (sealed, never in the address book, following renames, merges and deletes, kept through edits), the call
path's ringtone, voicemail and label tones, and "Recently deleted"; `BulkContactActionsTest` the mixed selection.

## Security (unchanged)

- The vault's locking is unchanged: `VaultCrypto.LockedException` shows "Unlock to see all details" in the page, and the
  editor leaves as before when locked.
- Caller ID, the private call history, missed-call notifications and the lock screen use the same caller-ID copy as
  before; discreet mode ("Hide private contacts") still hides private contacts everywhere, including the merged lists.
- After a duress unlock (5.0, [SECURITY_MODEL.md](SECURITY_MODEL.md#duress-unlock)) discreet mode is forced on, a
  private contact's page finds no contact, its own ringtone isn't played and, by default, its sealed details refuse to
  open, until the real Parley PIN. Nothing is changed in the vault.
- Private contacts are merged only into Parley's own lists (`AppViewModel.everyone`), never into `ContactDirectory`,
  the widgets, the private-name provider or anything another app can query.
- Labels, the ringtone, "send to voicemail" and the star are sealed with the caller-ID key like the name; nothing of
  them is written to the address book, logs or notifications. A private caller sent to voicemail is never logged as a
  blocked call when Parley is the phone app (see "Ringtone and Send to voicemail"); a label's "Allow through Do Not
  Disturb" never stars a private contact.
- Multi-select never hands a private contact to another app: share, export, copy as text and merge skip them and say so.
- A deleted private contact's copy is sealed (details still under the detail key, which the vault keeps while a copy
  needs it), stored where no backup reaches, listed only after the vault's unlock, and gone after 30 days, with
  "Clear history & undo" (its own row, "Deleted private contacts", counted apart and hidden in discreet mode, so
  clearing contact changes never takes them), or with "Delete all Parley data". Temporary private contacts that expire
  keep no copy.
