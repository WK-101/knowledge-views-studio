# Shared labels (a family phonebook)

One label ("Family", "Doctors & school") kept the same on several people's phones, either through **update files**
that members send each other by any app (a message, an e-mail), or through a folder those phones already share
(Syncthing, Nextcloud, a USB stick). There is no server and Parley has no internet permission: the person or the folder
app moves the files, Parley only reads and writes them. Both carry exactly the same signed files, so a label can use
either, or both at once. This page is the design; the code references are the source of truth.

Code: `core/common/.../common/sync/shared/` (formats, signatures, merge decisions, membership; all unit-tested) and
`core/data/.../data/sync/shared/` (the folder, the address book and the sync run); screens in
`app/.../ui/sync/shared/`.

## What it is, in the user's words

- On a label's page, ⋮ › **Share this label…**: choose how changes travel (**Update files**, the default, or **A shared
  folder** in your Syncthing or Nextcloud folder), set a passphrase for this label, and your name as the others will
  see it. Then **Invite** people, one at a time, with a QR code (in the same room) or a file (sent any way you like,
  opened with the label's passphrase).
- With update files: **Send an update** after you change something, and **Open an update** when someone sends you one
  (or tap the file in the app it arrived in). See [Sharing by file](#sharing-by-file).
- The people you invite add the same folder on their phone (their sync app already shares it) and open the
  invitation. They see who is in the label, with each person's key fingerprint, before joining.
- From then on, the label's contacts stay the same on every member's phone: an edit, a new contact or a delete on one
  phone reaches the others the next time Parley syncs (shortly after a change, and every hour).
- The label's page shows who changed what: "Ana changed Dr Lee's number · 2 days ago".
- When two people changed the same thing of the same contact, the page asks which to keep ("Changed elsewhere").
- Only the contacts in that label are shared, and only contacts in the address book. **Private contacts are never
  shared.**

## The folder

One folder per shared label, chosen with the Storage Access Framework (Parley gets access to that folder only). It
must not be the folder of "Sync between your phones" (each sync keeps to its own files, but one folder per purpose
is easier to reason about; the screen refuses the same folder).

| File | What | Written by |
|---|---|---|
| `.parley-label` | Header: the label's id, the key's epoch, the KDF settings and salt, and a check value that tells a wrong passphrase or key apart from damage | Whoever created the label, and whoever last changed its key |
| `.parley-label-sig-<n>` | The signed note of the key change away from epoch `<n>`: the new epoch and the new header's hash, signed by the member who changed the key, sealed with epoch `<n>`'s key | Whoever changed the key |
| `c-<sid>.plabel` | One contact: its shared fields, its version, who wrote it, signed | Any member |
| `j-<member>.plabel` | One member's journal: their key, their name, the invitation that let them in, their recent changes, and their [spam shield](#family-spam-shield) verdicts (hashed) when it is on, signed | That member only |

`<sid>` is a random 128-bit id given to a contact when it is first shared; `<member>` is the first 128 bits of the
SHA-256 of the member's public key. File names say nothing about anyone.

### Encryption

- **The label key.** 32 bytes derived from the label's passphrase with scrypt (N = 2^15, r = 8, p = 1, the backup's
  setting; readers refuse costs outside the backup's caps), with a random salt kept in the header. It is separate
  from the "Sync between your phones" passphrase: a person can be in your family label without being able to read
  your own phones' sync folder, and the other way round.
- **Every file** is AES-256-GCM under the label key with a random nonce; the associated data binds it to the label
  id and its file name (`PARLEYL1|<label id>|<name>`), so a folder writer can't swap two contacts' files or move a
  file between labels.
- **Each phone keeps the key sealed** with the small-records key (`RecordCrypto`, wrapped by a Keystore key), like
  the folder sync's key. Syncing needs no passphrase afterwards. The passphrase itself is never stored.

### Signatures (who wrote it)

Every member signs with the Ed25519 key of their **My card** (the 4.6 identity, `MyCardIdentity`): the same key
their signed cards carry, so the fingerprint on the members list is the one My card › Shared with shows. A shared
label signature can never pass for a card signature or the other way round: each signed text starts with its own
fixed header.

- A **contact file** is signed over `PARLEY-LABEL-CARD-1`, the label id, the file name and its whole body: the sid,
  the version, the time, the author's key, whether it is a deletion, and the contact's fields.
- A **journal** is signed over `PARLEY-LABEL-JOURNAL-1`, the label id, its file name and its whole content.
- An **invitation ticket** is signed by the member who invites: `PARLEY-LABEL-TICKET-1`, the label id, the key's
  epoch, the invitation's random id and when it expires.
- A **key change** is signed by the member who made it: `PARLEY-LABEL-HEADER-1`, the label id, the new epoch and the
  SHA-256 of the new header.

### Who is a member

Members are worked out from the folder on every run, never taken from a list someone could simply edit:

1. The **anchor** is the person who created the label (or who last changed its key). Its key comes from the
   invitation, so every member knows it without trusting the folder.
2. A journal is a member's when it is signed by its key **and** carries a ticket signed by someone who is already a
   member, for this label and this key epoch. This repeats until nothing new is found.
3. The anchor's journal may list **carried members** after a key change: the people who stay. They are members of the
   new epoch without a new ticket.
4. A journal marked **left** is not a member any more.
5. An invitation is a bearer token, so it is **single-use and expires**: a journal this phone hasn't counted before
   needs a ticket that expired at most a week ago (the invitation itself works for 7 days; the extra week covers a
   phone that was off), and that no other journal shows. When two newcomers show the same invitation, neither is let
   in; a member already counted keeps their place (`SharedLabelRoster`).

A contact file signed by someone who isn't a member (a forged one, or one written by a removed member) is never
applied. When it sits where a contact this phone syncs should be, it is left alone for a grace period (an hour for a
damaged file, which may still be arriving; a day for one signed by an unknown key, whose journal may still be on its
way), then this phone writes its own copy over it with a higher version (`SharedLabelRules.unreadable`): anyone who
can write to the folder could otherwise freeze or erase a contact for good. The label page says so quietly meanwhile
("Some files in the folder couldn't be read…").

When a member **leaves** (or their key changes), the files they wrote last would no longer be signed by a member. Each
remaining phone signs again the very files it accepted from them (same version, same signed content, checked against
the hash it kept when it accepted them), so later members and providers without modified times can still read them.
Anything else they wrote is not vouched for.

An invitation lets in whoever opens it: it is a key, not a name. That is why it is wrapped (see below), and why the
label page lists every member with their fingerprint, so anyone can check who is in.

## Joining

An invitation holds: the label's id and title, the **folder hint** (the folder's name on the inviter's phone, for
example "Syncthing › Family", since a folder can't be handed from one phone to another), the label key and its epoch,
the anchor's public key, the inviter's name and public key (shown as its **fingerprint**), and the ticket.

- **QR code** (`parley://label?v=1&d=…`): sealed with a one-time passcode shown under the code: 16 letters from a
  30-letter alphabet (about 78 bits), under the backup's scrypt setting (a scanned code must use exactly it). The QR
  code holds the label's key and lives on as a photo or a screenshot (Parley has no camera permission), often backed
  up somewhere, so guessing its passcode offline must stay out of reach. For people in the same room. Parley reads it
  from a photo or a screenshot with Tools › Scan QR code.
- **File** (`.parleyinvite`): sealed with the **label's passphrase** (scrypt, the backup envelope). The inviter types
  the passphrase again (checked against the header) so a file is useless without it: send the file by any app and
  tell the passphrase in person or on a call.

Opening an invitation shows the label, who invited you and their fingerprint, and the folder hint. Then: choose that
folder on your phone (Parley checks its header against the key: a wrong folder says so), see the members found in it
with their fingerprints, type your name, choose **Join**. An invitation works for 7 days.

The label always joins as **a new label** on your phone (in your default account): "Family", or "Family (shared)" when
you already have a "Family". It never lands in a label of yours because the names match, which would share all of that
label's contacts with people who never saw them. **Use a label you already have** is an explicit choice on the same
screen, confirmed after being told how many of its contacts will be shared. Renaming a shared label onto another
label's name (or another label onto a shared one's) is refused, since renaming onto an existing name merges the two.

A contact that arrives is added to the label as a new contact. It is joined to one you have only when that one is
already in this label and not shared yet (same number or e-mail), never to a contact elsewhere in your address book:
a member could otherwise pull any contact of yours into the label, and learn from the published copy what you have
and under what name. A duplicate that results can be merged with **Find & merge duplicates**, like any other.

## Sharing by file

For households where not everyone runs a sync app (most). Code: `SharedLabelUpdates` (core/common: the format, the
checks and which file wins), `SharedLabelEngine.updateFile` / `openUpdate` and `LocalLabelFolder` (core/data), and
`ui/sync/shared/LabelUpdateFiles.kt` (app: sending, opening and telling the files apart).

- **Where the files live.** A label shared by file has no folder: each phone keeps the label's files (the same header,
  contact files and journals described above, sealed and signed the same way) in its own storage, outside backups
  (`noBackupFilesDir/shared_labels/files-<label id>`), as if it were that phone's copy of a shared folder.
- **Send an update.** One run writes this phone's changes into those files, then they all go into one **update file**
  (`<label>-<date>.parleyupdate`, type `application/vnd.parley.label-update`) handed to the share sheet. It carries
  the label's files as this phone holds them, other members' included, so an update relays changes to people the
  sender never exchanges with directly. Files that don't open with the current key (junk) stay out.
- **Open an update.** From the label's page or Members & invitations, or by opening the file from the app it arrived
  in (Parley accepts it as `application/vnd.parley.label-update`, and by its name, `.parleyupdate`, when an app gives
  it a type it doesn't know and the link shows the name: Parley never offers to open every unknown file). The screen
  checks what the file really is and says so when it isn't one. An invitation file opened that way goes on to Join,
  an encrypted vCard (`.vcf.parley`) to the import; a sealed file without a name isn't guessed at. The merge, once
  started, finishes and saves even if the screen is left or turned, which then shows its outcome without opening the
  file again. Sent update files leave the cache within a day (an hour old at the daily sweep).
- **Format.** `PARLEYU1 | label id | key epoch | nonce | AES-256-GCM(label key, gzip(signed))`, associated data
  `PARLEYU1|<label id>|<epoch>`. `signed` is a body (`label`, `epoch`, `from` (the sender's My card key), `name`,
  `at` (when it was made), and `files`, name → bytes) and the sender's Ed25519 signature over
  `PARLEY-LABEL-UPDATE-1`, the label id, the epoch and the body. Only a label's own file names are allowed (the
  header, `.parley-label-sig-<n>`, `c-<sid>.plabel`, `j-<member>.plabel`), each at most a folder file's size, at most
  5,000 files and 48 MB expanded.
- **Merging.** The update's files are laid over this phone's as if a sync app had brought them, one file at a time:
  a contact file when it is newer than the one here, a journal when it was written later (or has more changes), the
  header only for a later key, a key-change note when there is none. Then **one ordinary run** applies them with
  every rule on this page: members worked out from journals, only members' signed files applied, field-by-field
  merges, "Changed on two phones", tombstones, History & undo, the mass-deletion pause. What the run didn't write over
  stays in the files when it is newer, so the next update passes it on.
- **Edits made alongside each other.** Updates travel slowly, so two members often change the same contact from the
  same version without seeing each other's change. Every contact file now says which version it was written from (its
  **parent**, inside the signed body; older Parley versions ignore it), and each phone remembers the last three
  versions it held of each contact. A file written from one of those, rather than from the version this phone synced,
  is **concurrent** (`SharedLabelRules.concurrent`): it is merged field by field against the version both started
  from, whichever of the two is newer, so neither change is lost; a field both changed differently asks, as above. A
  deletion made alongside an edit loses to it, like any deletion that meets an edit. The same holds for folders, where
  a sync app's conflict could otherwise drop one side.
- **Copies put back.** An update must be sealed with this phone's key for the label (another key, another label or one
  changed byte: refused whole), signed by the member it names, and newer than the last update this phone opened from
  that member (an update opened again, or an older one, is refused: "You opened this update before"). Its time may be
  at most a day ahead of this phone's clock. Inside, every file is checked as a folder's would be: a contact file
  older than the version this phone saw is ignored.
- **Key changes.** An update made before the label's key changed is refused ("made before the label's key changed");
  one made after asks for a new invitation first. Removing a member works as above; the others then need a new
  invitation and an update.
- **Joining by file.** On the invitation's screen, **No shared folder? Use update files** joins without a folder: the
  label's contacts and members arrive with the first update the inviter sends; this phone's journal goes back with
  the first update it sends, so the others count it. The invitation itself is unchanged (same file, same QR code).
- **Leaving by file.** Leave this label offers a last update whose journal says you left; once the others open it they
  see it. The label's files are then removed from this phone.
- **What each member sees.** The label's page shows "Shared by update files", when it last changed, and Send an
  update / Open an update instead of Sync now. Members & invitations shows, for each member, when their last update
  was made ("Last update from them 2 days ago"), and when you last sent one.
- **A label can use both.** Send an update and Open an update are offered on every shared label: a member with the
  folder can send an update to one without it, whose replies are merged into the folder for everyone.

## Syncing

On the folder sync's cadence (`FolderSyncWorker`: shortly after start, a minute after the address book changes, and
hourly), each shared label runs once:

1. The folder is listed. **A listing that fails, or that the provider says is still loading, stops the run with
   nothing changed.** A missing file is never read as a deletion: only a signed deletion (a tombstone) deletes.
2. The header is read. A header this phone's key doesn't open is a key change only when a member this phone knows
   signed it (`.parley-label-sig-<n>`, sealed with this phone's key, so only someone who had it could write it); the
   phone then waits for a new invitation (see "Removing a member"). Otherwise the header was swapped in by someone
   with folder access: this phone keeps to the header it last accepted, keeps syncing, says so quietly, never moves to a
   lower epoch, and writes the last good header back when the folder's is missing, damaged or not newer.
3. Journals are read and checked; the members are worked out; their recent changes go into this phone's history.
4. Contact files that changed since the last run (by modified time and size, or by a hash of their content when the
   provider reports neither) are opened and checked; when the members changed, every file is read once. A file older
   than the version this phone already saw of it (a copy put back) is ignored (**replay detection**); a phone that
   joins later has no such memory, which this page states as a limit. A version more than a year ahead of the clock is
   not believed. Files that can't be used are remembered by stamp and not opened again until they change, and at most
   100 journals are read (members' own first), so junk in the folder costs little.
5. The label's members in the address book are compared with the last synced state, field by field.
6. Each contact gets one decision (`SharedLabelRules`):

| This phone | The folder | What happens |
|---|---|---|
| Unchanged | Unchanged | Nothing |
| Edited | Unchanged | Written to the folder, signed, version up |
| Unchanged | Edited | Applied here (History & undo keeps the previous version) |
| Edited | Edited | Merged field by field; a field both changed differently waits for the user |
| Deleted or taken out of the label | Unchanged | A tombstone is written |
| Unchanged | Tombstone | Deleted here when the label brought it, taken out of the label when it was already yours (History & undo either way) |
| Edited | Tombstone | The edit wins: written again, with a higher version |
| Deleted here | Edited | The edit wins: added back |
| Present | File missing | Written again (a file is never "deleted" by vanishing) |
| Present | File unreadable, or not a member's | Left alone for a grace period, then written again |
| New in the label | — | Shared under a new sid |
| — | New contact | Added to the label (joined only to a contact already in it and not shared yet) |

7. This phone's journal is written with the changes it made.

A run that would delete more than 3 contacts and more than a quarter of the label waits for the user (the same rule
as the folder sync: a folder half synced, or a member who emptied it by mistake).

### Fields and conflicts

A contact's shared fields: name, nickname, organisation, phone numbers, e-mails, addresses, websites, dates,
relations, notes, chat and SIP addresses, pronouns. **Not shared**: the photo (files stay small), labels other than
this one, ringtones, "send to voicemail", the star, Parley's own data (notes for calls, Circle, call-time limits) and
everything of private contacts.

Each field merges on its own with `ThreeWayMerge` against the version last synced (as the editor's "Changed
elsewhere" does): one side changed it, that side wins; both changed it the same way, fine; both changed it
differently, it is a conflict. The rest of the contact is merged at once; the conflicting fields keep this phone's
value until the user picks, and the contact isn't written to the folder until then. The label page shows "Changed on
two phones" with, per field, "Yours" and "Ana's". When a contact is first matched (no version in common), a field only
one side has is simply taken, and only fields both sides fill differently ask.

### Deletes and History & undo

Deleting a contact of the label, or taking it out of the label, writes a tombstone. Other phones delete their copy
(or take it out of the label when it was theirs before) through the usual delete, so **History & undo** keeps it for
30 days. Restoring it there makes it a new contact in the label, shared again.

## Removing a member, leaving

- **Leave this label** (any member): your journal is marked "left" and your key is forgotten. Your copies of the
  contacts stay on your phone as ordinary contacts in the label.
- **Remove a member**: the label gets a **new key** (a new passphrase typed now, a new salt, the epoch goes up). Your
  phone rebuilds the contact files under it **from what it accepted itself** (its synced versions and the deletions
  it saw), signed by your phone; nothing is taken from the folder's files, so a file planted by someone who still has
  the old key (the removed person, or someone with an old invitation), before or during the change, is never signed
  into the new key. It stays sealed with the old key: unreadable and ignored. A change cut short is finished on the
  next run from the same record. Then it writes the signed note of the key change, the new header, and its journal as
  the new anchor with the members who stay, and removes the old journals. The others then see "This
  label's key was changed": nothing syncs for them until they open a **new invitation** from you (their history and
  contacts stay; their pending edits are sent once they're back in). The label page lists who still needs one.
- **Honest limits**: a removed member keeps everything they already had (contacts on their phone, the old files and
  the old key). They can still write into the folder if the sync app lets them: those files can't be opened with the
  new key and are ignored, and one written over a contact's file is replaced by the members' copy after the grace
  period. Remove them from the Syncthing or Nextcloud share too.

The membership states (`SharedLabelMembership`): **Active** (epoch n) → **Key changed** (the header has another epoch
or the key doesn't open it) → **Active** again with a new invitation; **Active** → **Left**. A removal moves the remover
from **Active** (n) to **Active** (n + 1) as the new anchor.

## What each member sees

- On the label's page: a **Shared** section with the folder, the members (name, fingerprint, "You", "Invited by Ana",
  "Needs a new invitation") and the last sync, then **Changes**: "Ana changed Dr Lee's number · 2 days ago", "You added
  School office · yesterday", "Sam removed Old dentist · 3 weeks ago", filterable by member.
- In Settings › Backup & sync › Sync between your phones › **Shared labels** (search "family phonebook" or "join"):
  every shared label, and **Join a shared label** from a file or a QR code.
- Renaming the label on your phone keeps it shared (the others keep their own label's name), unless another label
  already has the new name: that would merge them, which is refused while either is shared. Deleting it stops its
  sync ("This label isn't on this phone any more") instead of reading as "everyone was removed".

## Family spam shield

Members of a shared label can warn each other about spam callers. Code: `FamilyShield` (core/common: hashing,
merging, what a call does), the `shield` field of each journal (`SharedLabelFiles`), `SharedLabelEngine` (writing and
reading it), `FamilyShieldStore` (core/data: this phone's own verdicts and the in-memory index the call path uses),
step 5a of `CallPolicy`, and `ui/sync/shared/FamilyShieldScreen.kt`.

- **Opt-in, per label, off by default.** The label's page has a **Family spam shield** row with a switch. Turning it
  on first says what is shared. Its page sets what a match does on this phone: **Warn only** (the default), **Silence**
  or **Block**. While it is off, this phone shares nothing and keeps nothing from the others.
- **What is shared.** The numbers this phone blocks one by one (exact block rules, not ranges, temporary rules or
  other kinds of rule), and numbers marked **It's a scam** or **Likely spam** from Report. Each verdict has a kind
  (blocked, scam, spam-likely) and a date. It travels in the member's own journal, so the member it came from is the
  journal's key, never a name. Names, notes and calls are never shared. A member shares at most 2,000 verdicts (the
  newest).
- **How a number travels.** As `HMAC-SHA256(k, E.164)` cut to 128 bits, where
  `k = HKDF-SHA256(label key, salt = label id, "parley/v1/family-shield")`. The hash means nothing in another label or
  under another key. Only numbers with a full international form are shared, and every phone reads them the same way
  (libphonenumber's E.164, with legacy spellings of a line made one).
- **Signed like everything else.** The verdicts are inside the journal's signed body: a folder writer can't add, change
  or move one, and the journal is sealed with the label key like every other file. Update files carry journals as they
  are, so a verdict is relayed through members who never exchange directly. Older Parley versions ignore the field.
- **Two voices to block.** A label set to **Block** declines a number only when at least two members shared a verdict
  on it, or when one of them is the label's anchor (who shared the label first); with one member's word it warns, as
  Warn only does (`FamilyShield.modeFor`). Warn only and Silence act on one. Only the label's members now count: a
  member who left or was removed, or who held the key before it changed, adds no voice.
- **Said when you block.** When a block will be shared (an exact block rule, while a shielded label syncs), the Block
  question says "Also shared with Family, so they're warned if this number calls them", with **Don't share** for that
  block alone: the number is remembered as withdrawn before its rule is written, so it is never shared, not even once.
  No setting.
- **On a call.** For an unknown caller (never a saved or private contact, never an emergency number or a call within
  the emergency window), the number's hash is looked up in memory for each shielded label. A match warns ("Blocked by
  someone in Family", "Called a scam by someone in Family", "Called spam by someone in Family") or is silenced or
  declined, as the label's choice says; of several labels, the one that does most counts. It sits below allow rules,
  numbers you called or talked to, block rules and spam lists, above sales lines and the default toggles, and, like a
  spam list, a repeat caller still rings. The index is rebuilt whenever a label's state changes (a run, an update
  opened, the switch, leaving), and read at app start or before the first call a process screens. Deciding whether a
  call needs screening reads nothing from storage. If a label's state can't be opened then (the Keystore busy just
  after the process started), the next call reads the states again, so the shield is never left matching nothing.
- **Withdrawing.** The shield's page lists what this phone shares; **Withdraw** stops sharing a number (it stays
  blocked here). This holds for a blocked number that was also marked from Report. Parley remembers the number as
  withdrawn for as long as it stays blocked, and trimming a long list never drops that record. Unblocking a number
  withdraws it too. The others lose it after the next run or update.
- **Leaving and removing.** A member who leaves or is removed is no longer a member, so what they shared stops counting
  at once. Leaving a label on this phone removes its verdicts with it. A key change starts over: each member's journal
  is written again under the new key.

**Honest limits.** The hash keeps the numbers from anyone without the label key: someone who can read the folder but
isn't a member can't tell them, even by trying every number. **Members can**: they hold the key, and phone numbers are
few enough to try them all, so anyone in the label can learn which numbers you blocked or reported, and when. Share
only in a label of people you'd tell. A removed member keeps what they had already received. A verdict is someone's
opinion, not a fact: that is why Warn only is the default, and why saved contacts and emergency numbers always ring.
Verdicts travel at the label's pace (the folder's next run, or the next update opened).

## Private contacts

Only address-book contacts in the label are shared. A private contact in the label stays on this phone: adding one to
a shared label from the Contacts selection is refused with "Private contacts aren't shared. Make it visible to other
apps to share it", and the label page says how many private members stay on this phone (never in discreet mode or a duress session, which hide that private contacts exist). A contact received from the
label is always an address-book contact (it can be made private afterwards: it then leaves the share, which is a
tombstone for the others).

## Chapters and archived contacts

A shared label can be given an end (a chapter, [GLOSSARY.md](GLOSSARY.md)) only on the phone that owns it: the
anchor, who started it or took it over at a key change (`SharedLabels.isOwner`). The others' label page says so and
their phones never ask. The chapter itself stays on the owner's phone (nothing new goes in the folder); what the owner
then chooses goes through the usual paths and syncs as any change does: a member archived or deleted leaves the
share (a tombstone for the others, kept in their History & undo), and removing the label stops its sync. An archived
contact is out of the address book, so it is never shared while archived; Unarchive brings it back into the label.

## Limits

- Made for family-sized labels (hundreds of contacts, not tens of thousands): each run reads the label's members. At
  most 50 members count.
- A journal keeps a member's last 300 changes, and each phone keeps the last 1,000 lines of history per label.
- Private contacts and photos aren't shared; a private contact added to the label from its editor stays on this phone
  (the label page says how many).
- Replay detection needs memory: a phone that joins later accepts the newest file it finds, even an old copy someone
  put back.
- Membership is by invitation. An invitation (and its passcode or the label's passphrase) lets in whoever has it,
  once, within 7 days. The members list shows everyone with their fingerprint: check it after someone joins.
- Nothing is real time: changes travel as fast as the folder app moves files, and Parley looks every hour. By file,
  they travel when someone sends an update and the others open it.
- By file, an update holds the whole label (not only what changed): a family label of a few hundred contacts is a
  few hundred kilobytes. An update older than one already opened from the same member is refused, so open them in
  the order they came; a member's change that only an older update carried still arrives with that member's next one.
- A removed member keeps what they had and can still fill the folder with files nobody reads; one they write over a
  contact's file holds that contact back for up to an hour (a day if signed by a key nobody knows) before the members'
  copy goes back.
