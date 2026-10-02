# Shared labels (a family phonebook)

One label ("Family", "Doctors & school") kept the same on several people's phones, through a folder those phones
already share (Syncthing, Nextcloud, a USB stick). There is no server and Parley has no internet permission: the
folder app moves the files, Parley only reads and writes them. This page is the design; the code references are the
source of truth.

Code: `core/common/.../common/sync/shared/` (formats, signatures, merge decisions, membership; all unit-tested) and
`core/data/.../data/sync/shared/` (the folder, the address book and the sync run); screens in
`app/.../ui/sync/shared/`.

## What it is, in the user's words

- On a label's page, ⋮ › **Share this label…**: choose an empty folder in your Syncthing or Nextcloud folder, set a
  passphrase for this label, and your name as the others will see it. Then **Invite** people, one at a time, with a
  QR code (in the same room) or a file (sent any way you like, opened with the label's passphrase).
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
| `c-<sid>.plabel` | One contact: its shared fields, its version, who wrote it, signed | Any member |
| `j-<member>.plabel` | One member's journal: their key, their name, the invitation that let them in, their recent changes, signed | That member only |

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
  epoch and the invitation's random id.

### Who is a member

Members are worked out from the folder on every run, never taken from a list someone could simply edit:

1. The **anchor** is the person who created the label (or who last changed its key). Its key comes from the
   invitation, so every member knows it without trusting the folder.
2. A journal is a member's when it is signed by its key **and** carries a ticket signed by someone who is already a
   member, for this label and this key epoch. This repeats until nothing new is found.
3. The anchor's journal may list **carried members** after a key change: the people who stay. They are members of the
   new epoch without a new ticket.
4. A journal marked **left** is not a member any more.

A contact file signed by someone who isn't a member (a forged one, or one written by a removed member) is ignored,
never applied, and never deleted.

An invitation lets in whoever opens it: it is a key, not a name. That is why it is wrapped (see below), and why the
label page lists every member with their fingerprint, so anyone can check who is in.

## Joining

An invitation holds: the label's id and title, the **folder hint** (the folder's name on the inviter's phone, for
example "Syncthing › Family", since a folder can't be handed from one phone to another), the label key and its epoch,
the anchor's public key, the inviter's name and public key (shown as its **fingerprint**), and the ticket.

- **QR code** (`parley://label?v=1&d=…`): sealed with a one-time passcode shown under the code (PBKDF2, the exact
  setting the simple-mode QR uses; a scanned code must use exactly it). For people in the same room. Parley reads it
  from a photo or a screenshot with Tools › Scan QR code (it has no camera permission).
- **File** (`.parleyinvite`): sealed with the **label's passphrase** (scrypt, the backup envelope). The inviter types
  the passphrase again (checked against the header) so a file is useless without it: send the file by any app and
  tell the passphrase in person or on a call.

Opening an invitation shows the label, who invited you and their fingerprint, and the folder hint. Then: choose that
folder on your phone (Parley checks its header against the key: a wrong folder says so), see the members found in it
with their fingerprints, type your name, choose **Join**. Parley adds the label on your phone (in your default
account) when you don't have it, and the first sync brings its contacts. Contacts you already have are matched by
phone number or e-mail address: they join the label instead of being added twice.

## Syncing

On the folder sync's cadence (`FolderSyncWorker`: shortly after start, a minute after the address book changes, and
hourly), each shared label runs once:

1. The folder is listed. **A listing that fails, or that the provider says is still loading, stops the run with
   nothing changed.** A missing file is never read as a deletion: only a signed deletion (a tombstone) deletes.
2. The header is read: a key that no longer opens it means the key was changed (see "Removing a member").
3. Journals are read and checked; the members are worked out; their recent changes go into this phone's history.
4. Contact files that changed since the last run (by modified time and size) are opened and checked. A file older
   than the version this phone already saw of it (a copy put back) is ignored (**replay detection**); a phone that
   joins later has no such memory, which this page states as a limit.
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
| New in the label | — | Shared under a new sid |
| — | New contact | Matched by number or e-mail, else added, in the label |

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
  phone re-encrypts every contact file under it, signs again the files the removed person wrote last, writes its
  journal as the new anchor with the members who stay, and removes the old journals. The others then see "This
  label's key was changed": nothing syncs for them until they open a **new invitation** from you (their history and
  contacts stay; their pending edits are sent once they're back in). The label page lists who still needs one.
- **Honest limits**: a removed member keeps everything they already had (contacts on their phone, the old files and
  the old key). They can still write into the folder if the sync app lets them: those files can't be opened with the
  new key and are ignored. Remove them from the Syncthing or Nextcloud share too.

The membership states (`SharedLabelMembership`): **Active** (epoch n) → **Key changed** (the header has another epoch
or the key doesn't open it) → **Active** again with a new invitation; **Active** → **Left**. A removal moves the remover
from **Active** (n) to **Active** (n + 1) as the new anchor.

## What each member sees

- On the label's page: a **Shared** section with the folder, the members (name, fingerprint, "You", "Invited by Ana",
  "Needs a new invitation") and the last sync, then **Changes**: "Ana changed Dr Lee's number · 2 days ago", "You added
  School office · yesterday", "Sam removed Old dentist · 3 weeks ago", filterable by member.
- In Settings › Backup & sync › Sync between your phones › **Shared labels** (search "family phonebook" or "join"):
  every shared label, and **Join a shared label** from a file or a QR code.
- Renaming the label on your phone keeps it shared (the others keep their own label's name). Deleting it stops its
  sync ("This label isn't on this phone any more") instead of reading as "everyone was removed".

## Private contacts

Only address-book contacts in the label are shared. A private contact in the label stays on this phone: adding one to
a shared label from the Contacts selection is refused with "Private contacts aren't shared. Make it visible to other
apps to share it", and the label page says how many private members stay on this phone (never in discreet mode or a duress session, which hide that private contacts exist). A contact received from the
label is always an address-book contact (it can be made private afterwards: it then leaves the share, which is a
tombstone for the others).

## Limits

- Made for family-sized labels (hundreds of contacts, not tens of thousands): each run reads the label's members, and
  a contact received for the first time is matched against the address book. At most 50 members count.
- A journal keeps a member's last 300 changes, and each phone keeps the last 1,000 lines of history per label.
- Private contacts and photos aren't shared; a private contact added to the label from its editor stays on this phone
  (the label page says how many).
- Replay detection needs memory: a phone that joins later accepts the newest file it finds, even an old copy someone
  put back.
- Membership is by invitation. An invitation (and its passcode or the label's passphrase) lets in whoever has it.
- Nothing is real time: changes travel as fast as the folder app moves files, and Parley looks every hour.
- A removed member keeps what they had and can still fill the folder with files nobody reads.
