# Contact page design

How a contact's page is laid out, and why. The code is in `app/ui/contact/ContactDetailScreen.kt` (the page),
`ContactPageSections.kt` (foldable groups and the pinned bar) and `DetailParts.kt` (`InfoRow`, `GroupDataRow`,
the action tiles). The pure rules (section families, joined groups, the at-a-glance line) are in
`core/common/people/ContactPageLayout.kt` and `ContactGlance.kt`, with unit tests.

## Feedback on 4.2.0

"When I open a contact the detail page is not properly optimized for efficient space utilization… make a modern
looking contact page which is space efficient and modern and yet calm."

## What 4.2.0 spent its height on

Measured from the code, for a typical contact: two numbers (the mobile one has WhatsApp and Signal), one email,
a birthday 40 days away, about 20 calls, not in the Circle, no note for calls, default section settings. The
screen is an upright phone about 412 × 900 dp, so roughly 800 dp sit under the status and top bars.

| Part | 4.2.0 | Why it cost that much |
|---|---|---|
| Header | 388 dp | 168 dp photo, 16 dp gaps, "Last talked" line, a row of "Saved in" chips, 72 dp tiles |
| Stay in touch | 260 dp | Header, a three-line "Add to your Circle" invitation, "Usually free…", "Birthday in 40 days" |
| Dates | 188 dp | Header, one 72 dp row, then a chip row for the missing anniversary |
| Phone | 206 dp | Header, two 72 dp rows |
| Email | 132 dp | A 48 dp header and a 12 dp gap for one 72 dp row |
| Message or call on… | 206 dp | A 72 dp row per app and number, repeating what the phone rows are for |
| Timeline | 548 dp | Header, a month heading per month, five 72 dp rows, "Show all" |
| Note for calls | 148 dp | Header and a three-line empty-state row |
| Call insights, Settings (folded) | 120 dp | Two headers |
| Bottom padding | 32 dp | |
| **Total** | **≈ 2230 dp** | The first screen showed only the header and Stay in touch; no number was in view |

The waste, by kind:

- **Oversized header.** A 168 dp photo plus generous padding took nearly half the first screen before the tiles.
- **A header and a card per tiny section.** Email, a single birthday or a single address each paid 48 dp of
  header plus a 12 dp gap for one row.
- **Tall rows.** Every fact was a Material two-line list item (72 dp) with its label on a second line.
- **Repeated facts.** "Last talked" in the header and again in Stay in touch; the next birthday in Stay in touch
  and again in Dates; each messenger as its own row although the phone row is the same line.
- **Near-empty sections.** Dates showed for every contact just to offer "Add birthday"; Stay in touch showed a
  three-line invitation for everyone outside the Circle.
- **Settings mixed with facts.** "Saved in" chips sat in the header; Circle membership sat above the numbers.

## Research

- **Google Contacts (Material 3 Expressive, 2025).** A centred photo and name, a row of pill-shaped Call /
  Message / Video / Email buttons, then the details in rounded containers that join into one card. The
  "Contact info" label above the numbers was removed; each row shows the value with its type under it and a
  trailing message icon on phone rows. Contact settings (ringtone, route to voicemail, "Saved in") come after the
  details. ([Android Authority](https://www.androidauthority.com/google-contacts-material-3-expressive-rollout-3583994/),
  [9to5Google](https://9to5google.com/2025/08/14/google-contacts-material-3-expressive/),
  [Android Central](https://www.androidcentral.com/apps-software/google-contacts-material-3-expressive-redesign-rolls-out))
- **iOS 26 contact card.** The poster or photo fills the top, then short inset-grouped lists where the label sits
  next to or above the value in a small secondary style, one group for all phone numbers, one for emails, and
  notes and settings-like rows (ringtone, "Share contact", "Block") at the end.
  ([TidBITS](https://tidbits.com/2025/11/17/how-to-set-contact-avatars-and-posters-on-the-iphone/),
  [MacRumors](https://www.macrumors.com/how-to/ios-17-how-to-create-your-own-contact-poster/))
- **Samsung One UI 8 / 8.5.** A profile card at the top, then a single card of numbers and emails with the
  per-number actions at the row's end; history and settings further down.
  ([9to5Google](https://9to5google.com/2025/05/28/samsung-one-ui-8-update-everything-new-changelog/),
  [Android Headlines](https://www.androidheadlines.com/2025/09/first-look-samsung-one-ui-8-5-phone-my-files-apps-images.html))
- **Material 3 lists.** One-line items are 56 dp, two-line 72 dp, three-line 88 dp; segmented (expressive)
  lists join rows of one topic into a single rounded container with small gaps between rows.
  ([Material 3 list specs](https://m3.material.io/components/lists/specs))

What they share: the photo is big but not dominant, the details come right after the actions, facts of one kind
live in one container with the label as a quiet second line, and settings go last.

## The chosen layout

1. **Header, about a third of the screen.** Photo 128 dp (a real photo) or 96 dp (a monogram); 160 / 120 dp on
   tablets; 88 / 72 dp on a short (landscape) screen. Name, then nickname / job, then one **at-a-glance line**:
   "Last talked 3 days ago · Birthday in 6 days · 1 open promise" (the date only within 30 days). Then the
   Call / Message / Video / Email tiles, now 64 dp tall. The "deletes itself on…" warning stays in the header
   for temporary contacts. The header still shrinks and docks into the top bar on scroll, and the pinned action
   strip and jump chips work as before.
2. **Compact rows** (`InfoRow`): the value with its label as a supporting line under it, 56 dp for one line and
   60 dp for two (the text keeps its size; only padding shrinks). The kind's icon shows once, on the first row;
   trailing 48 dp icon actions.
3. **Contact info.** Phones, emails, addresses and "Message or call on…" join into one group under one header.
   A number's supporting line names its type, "Default", a remembered SIM and the apps that reach it
   ("Mobile · Default · WhatsApp, Signal"); its trailing actions are "Message or call on…" (when apps reach it)
   and Message. Separate app rows remain only for apps on numbers not saved here, and for typed-in handles.
4. **Stay in touch** shows only when it has something to say: the Circle rhythm, a good time to call, or open
   promises. For everyone else "Add to your Circle" moves into the settings group.
5. **About {name}.** Dates (with the "Add birthday" / "Add anniversary" chips as one row), websites, relations,
   the contact's note and the note for calls, joined into one group.
6. **Timeline.** The latest three entries in one group (no month headings; each entry carries its date) and
   "Show all", which opens the full timeline with search and filters.
7. **Call insights** and **Other fields**, folded by default.
8. **Settings for this contact**, folded by default, at the bottom: Add to your Circle, Send to voicemail,
   talk-time reminder and call time limit, ringtone, call-screen picture, "Saved in" chips (with their edit /
   move / unlink menus), who changed it last, "Deletes itself on…" (temporary contacts) and a link to
   Settings › Contacts › Contact page sections.

**Profiles** (4.5): a contact's Instagram, LinkedIn, X, GitHub… (website rows that name a service, docs/EDITOR_DESIGN.md)
form their own section in the Contact info family, after "Message or call on…": each row is the handle as the service
writes it ("@ana.lima") over the service's name, with the service's badge. A tap opens the https profile address, which
the service's app takes when it is installed (its verified app links) and the browser otherwise; long-press offers
Copy (the handle) and Copy link. Such rows are no longer listed again under About's websites.

Groups are 8 dp apart, rows inside a group 2 dp apart (the segmented container from `SegmentedGroup`), and there
is never a card inside a card.

### Families: joined groups that still respect the user's order

Sections keep their stored ids, their start modes and the user's order from Settings › Contacts › Contact page
sections. Each section may belong to a family: Phone, Email, Address and "Message or call on…" are **Contact
info**; Dates, About and Note for calls are **About**. `ContactPageLayout.blocks()` joins neighbours of one family
that are folded alike into one group with one header; a section the user moved away from its family, or folded
differently, stays on its own with its own title. Folding a joined group folds each of its sections, and the
fold is remembered per section as before. Jump chips list the groups.

The default order is now Stay in touch, Phone, Email, Address, Message or call on…, Dates, About, Note for
calls, Timeline, Call insights, Other fields, Settings. A stored order equal to the old default was never chosen
(it was written along with any fold), so it reads as the new default; any other stored order is kept.

## After

The same typical contact:

| Part | 4.3 |
|---|---|
| Header | 278 dp (4 + 128 photo + 12 + name 36 + glance 22 + 12 + tiles 64) |
| Contact info (2 numbers with their apps, 1 email) | 240 dp |
| Stay in touch ("Usually free 6–9 pm") | 112 dp |
| About (birthday, add-anniversary chip, note for calls) | 238 dp |
| Timeline (3 entries and Show all) | 332 dp |
| Call insights, Settings (folded) | 112 dp |
| Bottom padding | 32 dp |
| **Total** | **≈ 1345 dp, about 40 % shorter** |

The header is about 35 % of the space under the bars, and the numbers and email are in view without scrolling.
A sparse contact (one number, no calls, monogram) goes from about 975 dp to about 735 dp (about 25 % shorter).

## Copying (5.3.1)

Feedback: "long press contact name then it should copy the name only and when clicked on its detail below it should
also get copied."

- **Name**: press and hold copies the name as shown, alone (not the nickname, pronouns or job), with haptic feedback.
  A tap does nothing, so a stray tap while scrolling or reaching for the photo never fills the clipboard; TalkBack
  offers "Copy" as the long-press action.
- **The line under the name** (pronouns, nickname, job · department · company) is now one part per fact on a centred
  line that wraps. A tap copies that part, and so does a long press: the same rule as the page's other facts with
  nothing to open (`GroupDataRow` without an action, and More's rows since 5.3), so tap was free to use. The
  separators aren't read or tapped.
- Every copy goes through `Intents.copy`: the clip is marked sensitive (`EXTRA_IS_SENSITIVE`, so Android 13+ hides
  its preview), and before Android 13 Parley says "Copied" (Android shows its own preview after that).
- **Rows**: numbers, emails, addresses, dates, websites, profiles, handles, relations, the note, More and Other fields
  already copied on a long press (their menu's Copy, or the copy itself). The note for calls, whose tap opens its
  editor, now copies on a long press too. Private contacts have the same page, so all of this applies to them.

## Relations from other contacts (5.3.1)

Under About, after the contact's own relations, the page lists relations other contacts give this one when one of
the two is private ("Ana" over "Child · From their contact"; a tap opens Ana). Parley doesn't write those rows (see
docs/CONTACT_MODEL.md, "Relations with private contacts"), so they're shown from its own links instead, only with
"Add relations to both contacts" on, outside discreet mode, and while a private contact's details can be opened. A
person the contact already names in its own relations isn't listed twice.

## Relationship status (5.3.1)

Android and vCard have no marital-status field, so the page reads one from the relations (`RelationshipStatus`, no
new field or setting): a spouse (or wife, husband) adds "Married to Sam" and a partner or domestic partner "Partner of
Alex" to the line under the name, married first, each person once. Relations shown from other contacts (see above)
count too. A tap opens that person the way the relation's row does (its link, then its name; a private contact only
outside discreet mode); a long press copies the line. A former spouse ("Ex-spouse", a relation type since 5.3.1; rows
other apps wrote as "Ex-wife", "Ex-husband" or "Former spouse" are read as it) is never in the header: its row under
About says "Formerly married to" in place of the type. Girlfriend, boyfriend and fiancé(e) stay relations only.
