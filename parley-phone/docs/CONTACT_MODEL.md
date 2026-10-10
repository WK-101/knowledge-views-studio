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

### Archived contacts

An archived contact is out of Android's address book, so out of every list, search, picker and widget and out of
other apps, and kept whole by Parley (`ArchiveStore`, core/data; rules in `Archive`, core/common). Unlike a private
contact nothing is locked: its files in `files/archive` are sealed with the small-records key, which needs no unlock,
so the call path names it while the phone is locked.

- **Archive** (contact page › ⋮ › Privacy…, and a chapter's end) reads the lossless record (`ContactRecordStore.read`
  with the full photo), keeps it, then removes the contact from the address book (`purgeForVault`), and
  re-keys what Parley keeps about the person to `parley-archived:<id>` (`ContactRef.archivedKey`), a key the key sweep
  never resolves through the address book. A temporary contact archived stops expiring. Photos are kept whole, at full
  size (AUDIT_3 D7), as backups keep them, so Unarchive brings back the photo the address book had; contacts archived
  before this kept the thumbnail they were archived with (nothing is rewritten). Make private still keeps a photo
  larger than 512 KB as its thumbnail (`readCapped`, `MAX_KEPT_PHOTO`). When the
  address book refuses to remove the contact (a read-only copy, a provider error), the archive gives its copy back
  and nothing is archived: a person is never both archived and in the address book.
- **Still named**: caller ID and the call screen (with its note for calls, "Archived contact"; its agenda items show
  on their own card and can be ticked, `AgendaStore.targetFor` finds the archived contact by number), missed-call
  notifications, Recents, a number's history, Recall ("Archived contacts"), and screening, which counts an archived
  caller as a saved contact. Hide private contacts and a duress session treat it like any saved contact.
- **Unarchive** (Contacts › ⋮ › Archived) inserts the record back into the accounts it came from; when one of them
  isn't on the phone now, the user picks an account (`Archive.target`). What Parley kept follows it to the new key.
- **Backups** carry each archived contact (card and record, photos included) in the encrypted backup, restored with
  the contacts and never twice. A restore gives an archived contact a new id, so the archive part is restored first
  and what the other parts keep under the backup's `parley-archived:<id>` (the note for calls with its agenda, relation
  links, Circle rhythm and dates, logged moments, call time, vibration and auto-answer) follows the same person to
  their key here (`Archive.restoredKeys`, matched by the key before archiving or by name and numbers), never the bare
  id, which may name someone else; an archived key never matches anyone in the address book. **Exports** include
  archived contacts: a vCard with `X-PARLEY-ARCHIVED:1` and Parley's CSV with an Archived column are both archived
  again on import; Google's CSV carries an "Archived" label.
- **Private contacts are archived inside the vault**, never moved here. Archive on a private contact's page sets an
  "archived" mark (the time) in its caller-ID copy (`VaultRepository.setArchived`, `CallerIdCopy.C_ARCHIVED`); nothing
  else changes: the contact stays sealed under the vault's keys and behind the private lock, its details, photo, record
  (photo whole) and private calls stay where they are, and it never touches the archive's files, the small-records key
  or the address book. It was chosen over an archived section of the archive store because every private rule then
  holds by construction (sealing, the lock, discreet mode and duress, backups, the private trash), and no schema change
  is needed. An archived private contact:
  - leaves Parley's lists: Contacts, the keypad and the header search, favourites, the Circle, label pages and counts,
    and the agenda's people (`VaultSummary.archived`); its expiry goes, as an archived address-book contact's does;
  - is still named on calls as a private contact (the vault's lookup ignores the mark), under the private rules: the
    lock-screen privacy setting, discreet mode and duress apply as to any private contact;
  - shows in Contacts › ⋮ › Archived, under "Private", and in Recall's "Archived contacts" only while private contacts
    may show (`PrivateArchive.mayShow`: not hidden, no duress unlock, not locked with "Lock private contacts");
  - Unarchive (the same list) removes the mark: it is back among the private contacts, never in the address book;
  - travels in the private-contacts part of a backup (`archivedAt`), only when private contacts are backed up, and comes
    back archived and private; an edit keeps the mark.

### Contacts on a cold start

The Contacts list draws its first screen before the address book and the private contacts have loaded, from what it
showed last time (`ListHead`, core/common; `ContactListHead`, core/data), and the real list takes over row for row:
the same ids, order and headers, drawn in the same list with My card and the favourites above (the strip is kept with
it), so nothing moves when it arrives. It keeps only what a row shows: the name, the name it sorts by, the photo's in-app
URI, the star, the first number and the section header, for 60 rows and 40 favourites, and the "Sort by" it was in.

- Private contacts' rows are kept only when they were listed and not locked with "Lock private contacts" when it was
  written. Before anything is drawn, `ListHead.shown` checks what is stored now (settings as stored, not their
  defaults; a duress unlock; "Lock private contacts"; whether private contacts exist), and fails closed: when private
  contacts will be listed but may not be drawn from the head (locked, or the head was kept without them), nothing is
  drawn and the list appears whole as before; when they are hidden (discreet mode, duress), the head is drawn without
  them, which is still the top of the list without them. Another order than the kept one waits too.
- "Lock private contacts" and a duress unlock rewrite the head without private rows at once
  (`ContactListHead.dropPrivate`), and take a head on screen down.
- The private contacts themselves load from their kept rows (`PrivateSummaryCache`, see SECURITY_MODEL.md): one Keystore
  operation for all of them, each row checked against the caller-ID copy in the database, so only a changed or new
  contact is opened itself.

### What stays where for a private contact

| What | Where | Readable while the vault is locked? |
|---|---|---|
| Name, numbers, number labels, job/company line, "who is this" line, note for calls without its agenda items, star | Vault caller-ID copy (`VaultRepository`, caller-ID key) | Yes: caller ID, lists and the lock screen need them (unchanged) |
| The note for calls whole, with its agenda items (6.2) | Vault details, main part, only: the caller-ID copy keeps the note without them (`Agenda.withoutItems`). If the detail key is ever lost, "Keep what's left" keeps the note but not the items. Copies written before 6.2 still hold the items until the contact is next saved; the call path strips them as it reads | No: items show and are ticked after unlock |
| Label membership (group id + title per label), own ringtone, "send to voicemail", vibration pattern and auto-answer (4.4) | The same caller-ID copy, the only place they are kept (`VaultRepository.updateCallerChoices`; read through `PrivateLabelStore`) | Yes: the call path applies them while the phone is locked, and they change without unlocking |
| Every other field (emails, addresses with their RFC 9554 parts, dates with the calendar they follow, relations, websites and profiles, notes, handles, custom fields, the language, phonetic middle name, second surname and generation…), the usual app | Vault details, main part (auth-bound detail key) | No: the page asks to unlock, in place |
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

Open export (5.7): Settings › Contacts › Export contacts can include private contacts, when you switch on "Include
private contacts" (off by default) and unlock them. In a vCard each is marked `X-PARLEY-PRIVATE:1`, with its note for
calls, "Who is this" line, labels (by title), photo, call notes and Circle moments; CSV carries the card without notes.
A plain file says, before it is written, that anyone who gets it can read them; the **Encrypted vCard** locks the same
file with a passphrase ([ENCRYPTED_VCARD.md](ENCRYPTED_VCARD.md)). Importing such a file (plain or encrypted) brings a
card marked private back as a private contact: it never goes through the address book. With private contacts locked,
the card is reported as not imported rather than made visible.

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

### Relations with private contacts

A relation row ("Mother: Ana") holds only a name, as every app expects; Parley remembers which contact it means in its
own data (`RelationLinks`, under the Parley key of the contact that has the relation). A private contact is linked by
its Parley key and negative id, never looked up in the address book by that key, and the relation picker in the
editor lists private contacts (with the lock badge) unless discreet mode hides them.

Between two device contacts, "Add relations to both contacts" writes the opposite row on the other contact
(`RelationMirrors`, recorded in `relation_mirrors` so only Parley's own rows are ever taken back). When one of the two
is private nothing is written, either way:

- a private contact's relation to a device contact would put the private contact's name on a contact every app with
  the contacts permission reads (and its account syncs), which is what a private contact exists to avoid. Make private
  takes such rows back for the same reason;
- a device contact's relation to a private one would have to add rows to sealed details nobody is editing (with the
  vault perhaps locked), and record the private contact's key and name in `relation_mirrors`, which keeps neither.

Instead the other contact's page shows the opposite relation from those links (`RelationsFromOthers`,
`RelationMirror.fromOthers`): "Ana" over "Child · From their contact", which opens Ana. It is computed when the page
opens, so removing the relation removes it; it needs the setting on, is never shown in discreet mode (or after a
duress unlock) and needs the private contact's details open. A name the user saves in a device contact's relation
row is theirs, as with any relation; Parley adds nothing to it. The Contact notes section of a backup leaves out a
device contact's link to a private one.

Picking a private contact in the relation picker of a device contact would put the private contact's name in that
contact's Relation row, which its account syncs and every app with the contacts permission reads. So the editor asks
first, saying exactly that, and offers **Keep it in Parley only**: the relation is then stored as a Parley-only
relation (`ParleyRelations`, the `parleyRelations` column of `contact_meta`, keyed by the device contact's lookup key,
sealed at rest like the pinned note, re-keyed and merged with the rest of the row). It is linked by name like any
relation (`RelationLinks`), shown under About with "Kept in Parley only", counts for the relationship status line and
for the private contact's "From their contact" row, and is carried by the Contact notes section of Parley's
encrypted backups. It is never written to the address book, never mirrored, and hidden (neither shown nor saved over)
in discreet mode. **Save on this contact** writes the name into the Relation row as before.

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
| Save and share the photo, call-screen picture and QR codes (5.3.1) | Yes | — | Yes, after the private contacts' unlock; never in discreet mode; a shared copy is deleted when the share returns (EDITOR_DESIGN.md "Save and share") |
| Labels: page, filters, editor chips, Add to label, label ringtone, SIM, rhythm, rules, limits | Yes | No | Yes, membership kept sealed by Parley (see "Labels of a private contact") |
| Ringtone, Send to voicemail | Yes (Android) | No | Yes, applied by Parley's call screening and ringer |
| Custom fields, language, second surname and generation, address parts, dates by another calendar (5.3) | Yes (custom fields as Google's own kind in a Google account, Parley's rows elsewhere; see EDITOR_DESIGN.md "More fields") | — | Yes (sealed in the details; carried both ways by the conversions) |
| Name in their language, languages, citizenship (6.2) | Yes (a labelled nickname row, Parley's language and citizenship rows; EDITOR_DESIGN.md "More fields") | — | Yes (sealed in the details; the name in their language also on the caller card, for the call screen) |
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
| Contacts list, search | Yes | Separate "Private" view | **In the one list** with a lock badge; the "Private" chip is a filter; selectable. Search and filters reach every field while its details are open (kept in memory only, `PrivateSearch`), name and number otherwise |
| Keypad results, T9 | Yes | Separate rows with an emoji lock | Same rows as contacts with the lock badge |
| Caller ID, missed calls, lock screen | Yes | Yes | **Unchanged** (caller-ID copy; discreet mode shows only the number) |
| Other apps (ContactDirectory, private-name lookup for approved apps) | Address book | Approved-app lookup only | **Unchanged** |

### Still different, and why (`ContactCapability.deviceOnlyReason`)

| Only device contacts | Why |
|---|---|
| Share as a vCard file; in multi-select also Export, Copy as text and Merge | The file (or the clipboard) is handed to other apps, which could keep it; merging makes an address-book contact (QR codes are offered instead). Settings › Contacts › Export contacts is the deliberate way out: private contacts go in only when asked, with a warning, or encrypted |
| Version history; a copy of each edit in History & undo | Snapshots and edit copies are plain copies of the address book. A private contact's edits keep no copy: only a deleted one is kept, sealed ("Deleted private contacts") |
| Accounts, linked copies, "other fields" | Accounts, linking and rows written by other apps exist only in the address book |
| Copy to SIM | A SIM card is readable by any phone it is put in |
| Shared labels (a family phonebook) | The label's contacts go to other people's phones; a private contact in a shared label stays on this phone, and adding one from the Contacts selection is refused ([SHARED_LABELS.md](SHARED_LABELS.md)) |
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

On Android 16, when the user's default account for new contacts is a cloud account, Android refuses new phone-only
contacts: Make visible then puts the contact in that account rather than failing (`NewContactAccount`). Its question
says so before anything moves ("On this phone, Android puts new contacts in …"), and afterwards it names the account
the contact was really written to (`MadeVisible.Done.redirectedTo`), which is the cloud account only for copies that
were on the phone. Every other insert path reports a redirect the same way (`SaveResult.redirectedTo`,
`InsertResult.redirectedTo`): the import report names the account, and saves, restores and undo show "Saved in …". A private contact keeps its whole work row (company, title, department, office and
job description) and carries it back.

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

- The vault's locking is unchanged: `VaultCrypto.LockedException` shows "Unlock to see all details" in the page. The
  editor, and every other way of saving a private contact (keypad, a number's page, QR codes, add several, a chat,
  import), asks for the unlock instead and then saves with the edits kept; a cancelled unlock saves nothing and says
  nothing (6.2).
- **Lock private contacts** (6.2): the Contacts top bar's open lock, a row on an unlocked private contact's page, and
  hiding them from the Quick Settings tile lock every private contact's details again at once
  (`VaultRepository.lockAll`): opened details are forgotten and `VaultCrypto.lockedByPerson` refuses to open or seal
  any until the next unlock in Parley, even inside the key's own 5-minute window. Names and numbers stay listed (the
  caller-ID copy needs no unlock); "Hide private contacts" is what takes them out of sight. Kept in
  `no_backup/vault_locked`, so Parley being closed or stopped by Android doesn't undo it; the next successful unlock in
  Parley removes it. It is not the app lock.
- Parley's lists wait for the private listing (`VaultRepository.listing`, null until opened, caller-ID copies opened
  by a few workers at once) before their first showing, so private contacts never pop in after the others (6.2).
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
  The open export includes them only when "Include private contacts" is switched on, after their unlock.
- A deleted private contact's copy is sealed (details still under the detail key, which the vault keeps while a copy
  needs it), stored where no backup reaches, listed only after the vault's unlock, and gone after 30 days, with
  "Clear history & undo" (its own row, "Deleted private contacts", counted apart and hidden in discreet mode, so
  clearing contact changes never takes them), or with "Delete all Parley data". Temporary private contacts that expire
  keep no copy.

## Who owns a number: one answer

Every feature that names a number or asks whether it is saved goes through `NumberOwners` (core/data, `c.numberOwners`):
a contact, then a private contact, then an archived contact, then the name the network sent (only while "Remember names
from the network" is on, and only for a number known not to be a private contact's), then nobody. The number is read
with the region of the call's SIM (`PhoneEnv.countryIso(context, accountId)`). `owner(number, accountId, use)` applies
the privacy rule (`PrivacyView`, docs/SECURITY_MODEL.md "One privacy rule"): a hidden private contact reads exactly like
a number nobody saved, with no network name either. The `use` (screen, notification, lock screen, call path) decides
whether "Caller on the lock screen" holds back a network name; `notificationName` also shortens a saved name while the
phone is locked. `find` gives the raw answer, with each lookup's failure, for callers that must fail open (screening)
or closed (notifications).

One ringing call asks many times (screening, the call screen, "never calls you", the agenda, number memory, call
limits, safe words, ring facts). What was found is kept in memory for 60 s per number and SIM region, found once when
several ask at the same moment, and dropped whenever contacts, private contacts or archived contacts change; the
network's name is read fresh each time (it is written as the call ends). The vault's number fingerprints
(`VaultCrypto.hmac`) are also remembered, a few hundred at most, in memory, and dropped with the key. A ring therefore
costs one private-contact lookup instead of seven to nine. Archived contacts now name the To call reminder and count as
saved for expected-call hints, call limits, the number sheet and "Save all…".

## Phone numbers: one identity path

Every "is this the same line?" and "what is this number stored under?" goes through `PhoneIdentity` (core/common). Its international form is `NumberText.toE164`: libphonenumber's reading of the number with the SIM's country as the hint, accepted only when the number is complete on its own, with the older hand-written heuristic as the fallback for numbers libphonenumber can't read. `PhoneNumbers`, which holds the matching machinery and that heuristic, is internal to core/common, and the detekt rule `PhoneNumbersOutsideIdentity` keeps everything else on `PhoneIdentity`.

Unchanged: short codes ("3631", "116000"), emergency numbers (112, 911, 999, 110) and service codes (`*100#`, `*#06#`) have no international form and match by every digit; numbers without a known country only convert when they carry their own country code (+ or a dialling prefix); Mexico's legacy mobile forms fold into one line.

Intentional differences from the old heuristic (`IdentityCorpusTest` checks every example number libphonenumber ships, about 4,100 forms, and fails on any other difference):

| Case | Before | Now |
|---|---|---|
| Argentine mobile written nationally, "011 15 2345 6789" | +54 11 15 2345 6789 (never matched the call) | +54 9 11 2345 6789, as calls show it |
| Brazilian carrier code, "0 21 11 91234 5678" | the carrier code kept as part of the number | +55 11 91234 5678 |
| Countries where the old table kept or dropped a trunk 0 wrongly (Benin, Côte d'Ivoire, Congo, Gabon, Belarus, Slovakia, Monaco, Uruguay, and toll-free numbers in Botswana, Fiji, New Caledonia, Niger, Eswatini, Tonga) | a wrong international form | the right one |
| Countries missing from the old table (Åland, Saint Martin, Sint Maarten, the Pacific islands and others) | no international form (matched by the last digits) | an international form |
| Sender names with no digits ("VODAFONE", "BANK") | the letters dialled as digits and made up a number | no number at all; never a line |
| A complete international number shorter than 8 characters ("+98 9601") | none | its international form |
| A number "possible only locally" (a US number without its area code) | none | still none (libphonenumber's local-only answer isn't used) |

Rows stored by earlier versions under the old international form stay readable: `PhoneIdentity.lookupKeys` and `KeySet` also try the old form when it differs, and private-contact caller ID (`VaultNumberKeys.lookup`) does too.
