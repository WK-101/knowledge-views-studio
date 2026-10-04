# Parley glossary

One name per concept, used the same way in every screen, menu, notification and setting. English strings use UK spelling ("favourite", "colour", "optimisation") and "email" without a hyphen. When a string names a place in the app, it uses the exact title that place shows.

See also [WRITING.md](WRITING.md) for voice and tone, and [SETTINGS.md](SETTINGS.md) for where each setting lives.

## People

| Term | What it means | Not |
|---|---|---|
| **Favourites** | People you call often and want one tap away: the starred contacts. They're the Favourites tab (or the section at the top of Contacts), the widget, and the "loud favourites" option in blocking. Android stores this as the contact's star, so other apps see it too. | Not "Starred", not "Favorites". Starring someone and adding them to Favourites are the same action. |
| **Frequent** | A section of Favourites, not a separate list: the people you call most lately, picked by Parley from your call history. It's automatic, and you don't choose who appears. Always under the favourites (the Favourites tab, and Favourites in Contacts with "Show Frequent"). | Not a list you edit, and never a place of its own. |
| **Circle** | People you want to keep in touch with, each with a rhythm ("every 2 weeks") and gentle reminders. The one opt-in layer: it stays empty, and adds nothing to other screens, until you add someone. Always capitalised: "your Circle", "Add to your Circle". | Not a group and not Favourites: being in the Circle doesn't star anyone. Its suggestions are "Suggested for your Circle", never "Frequent". |
| **Labels** | Groups of contacts you make yourself ("Family", "Work"), for organising: filter by them, message a whole label, give one a ringtone. Android calls them groups; Parley says labels everywhere. | Not "groups" or "tags". |
| **To call** | A section of Recents, not a separate place: the calls you said you'd make (Remind me, follow-ups) and missed calls you haven't returned, as a strip at the top of Recents that opens the full list. Its reminder and Remind me's confirmation lead there. | Not a tab or a menu item. Not "callbacks" or "Call later". |
| **Private contacts** | Contacts kept only inside Parley, encrypted, invisible to other apps. Private is a variant of a contact, not another kind: the same page, editor and features, a lock on the photo in lists, the chip "Private" on the page (TalkBack: "Private · hidden from other apps"), and **Make private** / **Make visible to other apps** to convert ([CONTACT_MODEL.md](CONTACT_MODEL.md)), one contact or a selection alike. | Not "vault" in the interface. Not "Move to private". |
| **Hide private contacts** | The switch (Settings › Privacy & security, and the Quick Settings tile) that takes private contacts and their calls out of every list and search until it's turned off. A duress session turns it on whatever the switch says. | Not "discreet mode" in the interface (code and older notes call it that). |
| **Temporary contacts** | Contacts, private or not, that delete themselves after a time you choose. Also a variant: the chip "Temporary · 5 days left" (TalkBack: "deletes itself on …"), and **Keep permanently** to undo it. Made with **Delete automatically…**, the one name on the contact page, in the selection menu and in "Settings for this contact" (on a contact that already deletes itself, it changes when). When the time is up Parley asks once before deleting ("Ask before deleting temporary contacts", on by default). | Not "Delete after…" or "Change auto-delete". |
| **Relationship status** | The line under a contact's name from their relations: "Married to Sam", "Partner of Alex". **Ex-spouse** has no Android type, so it is stored as the custom label "Ex-spouse" (other apps and vCards show that text) and ends the "Married to" line. Labels are recognised in English ("ex-spouse", "ex-wife", "ex-husband"); one typed in another language in another app shows as written and doesn't change the line. Month and country names are matched in English, the phone's language and each country's own languages. | Not "marital status". |
| **My card** | Your own details (name, number), shared as a QR code or vCard and used for "Send my details". | Not "My details" or "My profile". |

### Which one?

Each has one job, so a person can be in several without it meaning the same thing twice:

- **Favourites:** who you want one tap away (you choose; the star).
- **Frequent:** who you actually call most (Parley notices; shown under Favourites).
- **Circle:** who you want to keep in touch with (opt-in; a rhythm and reminders).
- **Labels:** how you organise everyone (you name them; Family, Work).
- **To call:** calls you owe right now (in Recents; done once you call).

### Favourites and Do Not Disturb

A label's **Allow through Do Not Disturb** works by starring its members, because Android only lets starred contacts through Do Not Disturb. Starred contacts *are* Favourites, so turning it on adds those people to Favourites. Parley doesn't hide this: the confirmation lists every person who will be starred ("These 4 people will be starred and appear in Favourites") before anything changes. The switch's summary says the same. "Star N new members" asks with the same list. Turning it off unstars only the people Parley starred for that label.

## App lock

| Term | What it means | Not |
|---|---|---|
| **Parley PIN** | The app lock's own PIN, used instead of the phone's fingerprint or screen lock (Settings › Privacy & security › App lock › Unlock with). | Not "passcode" or "app password". |
| **Duress PIN** | A second PIN that opens Parley looking normal, with private contacts and sensitive notes hidden until the Parley PIN is used again. | Not "panic PIN", "decoy" or "fake PIN". |

## Reaching someone

| Term | What it means | Not |
|---|---|---|
| **Message or call on…** | The one sheet, and the one menu name, for reaching a number through an app: **Call** (through the phone) first, then **Message on** (chat apps, SMS), then **Call on** (voice and video calls in apps). The same sheet opens for saved contacts, private contacts and unsaved numbers. The contact page's section of the same name uses the same rows. | Not "Message on…", "Reach via apps" or "Messengers". |
| **Usual** | The way Parley remembers for a person (their usual chat app, call app or video app). Long-press a button to make it the usual one. | Not "default" or "preferred" in the interface. |

## Calls

| Term | What it means |
|---|---|
| **Recents** | The tab with your calls. |
| **Call history** | The calls themselves: Android's call log, and Parley's own encrypted copy. Two settings, named for what they act on: **Keep Parley's copy of calls** (the copy, on or off) and **Trim Android's call log** (deletes older calls from Android's log once you choose a limit; the copy keeps to the same limit). Not "Keep full call history" and "Keep call history", which read as the same thing. |
| **Call insights** | Talk time, top people and calls you didn't return. Always "Call insights", never plain "Insights". |
| **Blocking & screening** | The rules that decide which calls ring. The screen, its row in Tools and the Settings row all use this name. "Blocking & spam" is the Settings *category* that holds it and the spam lists. |
| **Expecting a call** | Lets unknown numbers ring for a while (a delivery, a callback). |
| **SIMs and calling abroad** | Settings › Calls › SIMs & carrier › the SIM screen: your SIMs and the abroad settings. **Plan minutes per SIM** (billing and a warning) open from Tools; the SIM screen shows them once a SIM has a plan. |
| **Sales lines (your calls)** | Numbers your own calls suggest are sales lines: one choice, Off · Tag quietly · Tag and silence. |
| **Situations** | One tap sets a moment: **Driving**, **Meeting**, **Night**, **Travelling** and the ones you make. Each sets who may ring, the reply offered first, the speaker, auto-answer, the drive profile's switches, the abroad help and the SIM for calls; turning it off puts back what you had. One is on at a time; it can turn on by itself at set times or when your car (or a Bluetooth device) connects. On Settings › Calls › Situations (the page that also holds helpers, the drive profile, phone menus and talk-time reminders and limits), the Quick Settings tile "Situation" and a line on the home screen while one is on ("Night is on · Turn off"). Not "mode", "profile" or "focus". |

## Undo

| Term | What it means |
|---|---|
| **History & undo** | The one place to get something back, one row in Settings › Backup & sync. It has three tabs: **Contacts** (contacts deleted, edited, merged or separated in Parley, 30 days; deleted private contacts are kept sealed there and listed after unlocking), **Calls** (calls deleted in Parley, 30 days) and **Snapshots** (daily snapshots of the address book, 6 months). Messages that say where to restore something name this place ("You can restore it from History & undo"). |
| **Version history** | One contact's earlier versions, on its page. |

## Navigation

| Term | What it means |
|---|---|
| **Tools** | The one hub: everything Parley does, grouped by the job you want done ("Stop spam", "Never lose a contact"…), each row opening the feature, the most used first and the rest under "n more"; searchable. Lock now (with the app lock) and Expecting a call work right on it. Reached from every tab's ⋮ menu, the top of Settings and the What's new card. Its rows come from `CapabilityCatalog`; every screen a row can open has its row. Settings holds preferences, Tools holds tools: Settings has no row that only opens a tool (search still finds the tool). Not "What Parley can do" (its old name, which search still finds). The Privacy dashboard lives under Settings › Privacy & security. |
| **Reminders** | The Settings page (a row of the Settings list) with every kind of reminder Parley sends (missed calls again, To call and follow-ups, keep in touch, birthdays and dates, backups, temporary contacts that are due), each with its switch and time (To call is a link to its own list); also the notification channel group that holds most of their channels. Missed calls, backup problems (a failed scheduled backup) and temporary contacts keep channels outside the group, so turning it off never hides them. Not "Notifications", which is Settings › Notifications & device. |
| **Coming from another phone?** | Where to export contacts, call history and block lists on the old phone, each opening Parley's own importer. Onboarding's optional last step, and in Tools. |
| **⋮ (More options)** | At most seven items, only the tab's own, then Tools and Settings. Every other menu and action sheet keeps to seven too, most used first; the rarer actions sit under **Share…**, **Privacy…**, **More…** or **Why it rang…**, each a sheet of its own. |
| **Long-press** | Selects, in every list that has a selection (Contacts, Recents); the selection bar's ⋮ then has the row's actions. A list without a selection gives each row a trailing ⋮. On buttons and tiles that act at once, a long-press offers the alternative (Call → Call with a reason, Message → Message or call on…, a Favourites tile → its page, keypad 2–9 → speed dial). |
| **Explainers** | One-line tips at a concept's first appearance (Private, Temporary, Circle, Labels, Favourites with Frequent, History & undo, To call), in the words of this glossary; each shows once. Each says what sets its concept apart from its neighbours (Circle: "doesn't star anyone"; Favourites: Frequent is under them). |
| **Contact health check** | The screen that finds numbers without a country code, numbers that seem out of service (calls to them keep failing; nothing changes unless you choose), and empty or stale contacts. Not "Tidy up". |
| **Search everything** | The Contacts search's chip that widens it to everything Parley remembers (Recall): calls with their dates, notes, promises, notes after calls, chats opened from Parley, deleted contacts, snapshots and number memory. It understands plain date and call words ("plumber march", "who called yesterday"), and runs by itself when the contacts give nothing. Not a tab or a screen of its own. |

## Words to avoid

| Instead of | Write |
|---|---|
| Favorites, favorite | Favourites, favourite |
| e-mail, E-mail | email, Email |
| Recently deleted (as a place), Recently deleted & changed, Recently deleted calls | History & undo (its Calls tab) |
| Discreet mode | Hide private contacts |
| Move to private | Make private |
| Delete after…, Change auto-delete | Delete automatically… |
| Keep full call history, Keep call history | Keep Parley's copy of calls, Trim Android's call log |
| Log interaction | Log a chat or visit |
| What changed, time machine (as a title) | Snapshots (tab), Daily snapshots (setting) |
| Insights | Call insights |
| Blocked numbers (for the rules screen) | Blocking & screening ("Blocked numbers (system list)" is Android's own list) |
| Message on…, Reach via apps, Messengers | Message or call on… |
| your circle | your Circle |
