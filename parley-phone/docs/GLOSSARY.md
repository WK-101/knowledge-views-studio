# Parley glossary

One name per concept, used the same way in every screen, menu, notification and setting. English strings use UK spelling ("favourite", "colour", "optimisation") and "email" without a hyphen. When a string names a place in the app, it uses the exact title that place shows.

See also [WRITING.md](WRITING.md) for voice and tone, and [SETTINGS.md](SETTINGS.md) for where each setting lives.

## People

| Term | What it means | Not |
|---|---|---|
| **Favourites** | People you call often and want one tap away: the starred contacts. They're the Favourites tab (or the section at the top of Contacts), the widget, and the "loud favourites" option in blocking. Android stores this as the contact's star, so other apps see it too. | Not "Starred", not "Favorites". Starring someone and adding them to Favourites are the same action. |
| **Frequent** | People Parley suggests from your call history. It's automatic, and you don't choose who appears. Shown under Favourites. | Not a list you edit. |
| **Circle** | People you want to keep in touch with, each with a rhythm ("every 2 weeks") and gentle reminders. Always capitalised: "your Circle", "Add to your Circle". | Not a group and not Favourites: being in the Circle doesn't star anyone. |
| **Labels** | Groups of contacts you make yourself ("Family", "Work"). Android calls them groups; Parley says labels everywhere. | Not "groups" or "tags". |
| **Private contacts** | Contacts kept only inside Parley, encrypted, invisible to other apps. Private is a variant of a contact, not another kind: the same page, editor and features, a lock on the photo in lists, the chip "Private" on the page (TalkBack: "Private · hidden from other apps"), and **Make private** / **Make visible to other apps** to convert ([CONTACT_MODEL.md](CONTACT_MODEL.md)). | Not "vault" in the interface. |
| **Temporary contacts** | Contacts, private or not, that delete themselves after a time you choose. Also a variant: the chip "Temporary · 5 days left" (TalkBack: "deletes itself on …"), and **Keep permanently** to undo it. When the time is up Parley asks once before deleting ("Ask before deleting temporary contacts", on by default). | |
| **My card** | Your own details (name, number), shared as a QR code or vCard and used for "Send my details". | Not "My details" or "My profile". |

### Favourites and Do Not Disturb

A label's **Allow through Do Not Disturb** works by starring its members, because Android only lets starred contacts through Do Not Disturb. Starred contacts *are* Favourites, so turning it on adds those people to Favourites. Parley doesn't hide this: the confirmation lists every person who will be starred ("These 4 people will be starred and appear in Favourites") before anything changes. The switch's summary says the same. "Star N new members" asks with the same list. Turning it off unstars only the people Parley starred for that label.

## Reaching someone

| Term | What it means | Not |
|---|---|---|
| **Message or call on…** | The one sheet, and the one menu name, for reaching a number through an app: **Call** (through the phone) first, then **Message on** (chat apps, SMS), then **Call on** (voice and video calls in apps). The same sheet opens for saved contacts, private contacts and unsaved numbers. The contact page's section of the same name uses the same rows. | Not "Message on…", "Reach via apps" or "Messengers". |
| **Usual** | The way Parley remembers for a person (their usual chat app, call app or video app). Long-press a button to make it the usual one. | Not "default" or "preferred" in the interface. |

## Calls

| Term | What it means |
|---|---|
| **Recents** | The tab with your calls. |
| **Call history** | The calls themselves, Android's and Parley's own encrypted copy ("Keep full call history"). |
| **Call insights** | Talk time, top people and calls you didn't return. Always "Call insights", never plain "Insights". |
| **Blocking & screening** | The rules that decide which calls ring. The screen, the Tools row and the Settings row all use this name. "Blocking & spam" is the Settings *category* that holds it and the spam lists. |
| **Expecting a call** | Lets unknown numbers ring for a while (a delivery, a callback). |
| **SIMs & plan minutes** | The per-SIM screen: plan minutes, billing and each SIM's options. |

## Undo

| Term | What it means |
|---|---|
| **History & undo** | The one place to get something back. It has three tabs: **Contacts** (contacts deleted, edited, merged or separated in Parley, 30 days; deleted private contacts are kept sealed there and listed after unlocking), **Calls** (calls deleted in Parley, 30 days) and **Snapshots** (daily snapshots of the address book, 6 months). Messages that say where to restore something name this place ("You can restore it from History & undo"). |
| **Version history** | One contact's earlier versions, on its page. |

## Navigation

| Term | What it means |
|---|---|
| **Tools** | The page for app-wide destinations that aren't tied to a tab: What Parley can do, Birthdays & dates, Temporary contacts, Contact health check, Scan QR code, Import & export contacts, Coming from another phone?, Blocking & screening, Expecting a call, Messaged numbers, History & undo, Backup & restore, Privacy dashboard, and Lock now when the app lock is on. It's reached from every tab's ⋮ menu and from the top of Settings. |
| **What Parley can do** | The page that lists what Parley does by the job you want done ("Stop spam", "Never lose a contact"…), each row opening the feature; searchable. In Tools, under Tools in Settings, and linked from the What's new card. Its rows come from `CapabilityCatalog`. |
| **Coming from another phone?** | Where to export contacts, call history and block lists on the old phone, each opening Parley's own importer. Onboarding's optional last step, and in Tools. |
| **⋮ (More options)** | At most seven items, only the tab's own, then Tools and Settings. |
| **Explainers** | One-line tips at a concept's first appearance (Private, Temporary, Circle, Labels, Favourites, History & undo, To call), in the words of this glossary; each shows once. |
| **Contact health check** | The screen that finds numbers without a country code, and empty or stale contacts. Not "Tidy up". |

## Words to avoid

| Instead of | Write |
|---|---|
| Favorites, favorite | Favourites, favourite |
| e-mail, E-mail | email, Email |
| Recently deleted (as a place), Recently deleted & changed | History & undo |
| What changed, time machine (as a title) | Snapshots (tab), Daily snapshots (setting) |
| Insights | Call insights |
| Blocked numbers (for the rules screen) | Blocking & screening ("Blocked numbers (system list)" is Android's own list) |
| Message on…, Reach via apps, Messengers | Message or call on… |
| your circle | your Circle |
