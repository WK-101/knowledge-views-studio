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
- **Variants combine.** A private contact can be temporary and so can a device contact. Future variants (a work-profile
  contact, a SIM contact: read-only) fit the same shape: another storage, or another attribute.

### What stays where for a private contact

| What | Where | Readable while the vault is locked? |
|---|---|---|
| Name, numbers, number labels, job/company line, "who is this" line, note for calls, star | Vault caller-ID copy (`VaultRepository`, caller-ID key) | Yes: caller ID, lists and the lock screen need them (unchanged) |
| Every other field (emails, addresses, dates, relations, websites, notes, handles…), the usual app, the original address-book record | Vault details (auth-bound detail key) | No: the page asks to unlock, in place |
| Photo | Vault photo file, sealed with the caller-ID key | Yes (the call screen shows it) |
| Circle rhythm, relation links, yearly dates, logged moments, call-screen picture | Parley's own stores under `parley-private:<id>` (moments' notes sealed as for every contact) | Parley only; the page shows them after unlock |
| Calls | Private call history (when "Private call history" is on) | As before |

Backups: private contacts' Parley data is written only in the private-contacts section of a backup (which needs the
vault unlocked), never in the Contact notes or Circle sections every backup has; a restore puts it back under the
restored contact's new key.

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
| Favourites (star) | Address-book star | No | Parley's own star (other apps never see it); in the Favourites tab with the lock badge |
| Default number/email | Yes | No | Yes (kept in the sealed details) |
| QR code | Yes | Secure QR only | Yes: plain QR after a privacy confirmation, secure QR as before |
| Block numbers, "allow by name" | Yes | No | Yes |
| Temporary: make temporary, change date, keep permanently | Yes | Expiry menu | Yes, same rows |
| Delete | Yes (History & undo) | Yes | Yes (no undo copy: the vault never leaves one outside it) |
| Editor | Full | Full fields, no call-screen picture | Full, plus call-screen picture and relation links |
| Contacts list, search | Yes | Separate "Private" view | **In the one list** with a lock badge; the "Private" chip is a filter |
| Keypad results, T9 | Yes | Separate rows with an emoji lock | Same rows as contacts with the lock badge |
| Caller ID, missed calls, lock screen | Yes | Yes | **Unchanged** (caller-ID copy; discreet mode shows only the number) |
| Other apps (ContactDirectory, private-name lookup for approved apps) | Address book | Approved-app lookup only | **Unchanged** |

### Still different, and why (`ContactCapability.deviceOnlyReason`)

| Only device contacts | Why |
|---|---|
| Share as a vCard file | The file is handed to another app, which could keep it (QR codes are offered instead) |
| Version history, History & undo copies | Snapshots are copies of the address book; private contacts are never copied out of the vault |
| Accounts, linked copies, "other fields" | Accounts, linking and rows written by other apps exist only in the address book |
| Copy to SIM | A SIM card is readable by any phone it is put in |
| Home-screen shortcut | The launcher (another app) would store the name and number |
| Labels | Android's labels are address-book groups that other apps can read. Parley labels for private contacts need a label store of Parley's own and are the next step; a private contact made visible gets its labels back |
| Ringtone, send to voicemail | Android's own ringer and call handling read them from the address book before Parley sees the call |
| Call time limits | They follow address-book contacts |
| Date chips on the page | They write straight into the address book; the editor adds dates to private contacts |

## Conversions

From the page (**Settings for this contact** and ⋮) and, for the expiry, from the editor:

| From → to | How | Lossless |
|---|---|---|
| Device → Private ("Make private") | `ContactConversions.makePrivate`: the address-book record (every row, the photo, labels) is sealed into the vault (`VaultMoves.moveIn`); Parley's data is re-keyed to `parley-private:<id>` (`ContactKeys.rekey`); a temporary date moves to the vault entry; no journal entry or snapshot stays | Yes; synced copies disappear from other apps after the account's next sync |
| Private → Device ("Make visible to other apps") | `ContactConversions.makeVisible`: the stored record goes back (accounts kept when still writable), edits made while private on top (`VaultMoves.moveOut`); Parley's data is re-keyed to the new lookup key; the date becomes the address book's temporary flag; the private call history goes back to the phone's call history | Yes |
| Permanent → Temporary, date changes, Temporary → Permanent | The vault entry's expiry, or `TemporaryContactStore` | Yes |

Both conversions ask first and say what other apps will or won't see. They run in the app's scope, so leaving the page
never leaves half a conversion. Undo is the opposite conversion (History & undo can't hold a private contact, by
design). The Robolectric test `ContactConversionsTest` runs device → private → device and checks the note for calls,
Circle membership, logged moments, the call-screen picture, labels and a temporary date, and that nothing is left
under the old key.

## Security (unchanged)

- The vault's locking is unchanged: `VaultCrypto.LockedException` shows "Unlock to see all details" in the page, and the
  editor leaves as before when locked.
- Caller ID, the private call history, missed-call notifications and the lock screen use the same caller-ID copy as
  before; discreet mode ("Hide private contacts") still hides private contacts everywhere, including the merged lists.
- Private contacts are merged only into Parley's own lists (`AppViewModel.everyone`), never into `ContactDirectory`,
  the widgets, the private-name provider or anything another app can query.
