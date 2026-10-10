# On-device checklist

One hour on real phones, riskiest first. Each line points at the full steps in [TESTING.md](TESTING.md). Nothing below has been run on a device yet; unit and Robolectric tests cover the logic, not the phone.

**Phones.** A: a Pixel on Android 17, signed in to Google, Parley as the default phone app. B: a Samsung on One UI 8 or 9. C: a Xiaomi or a Motorola, which stop background work hardest (used by the 6.x lines). A second phone (or a friend) to call from. Where a line says "A only" or "B only", the other phones can skip it. The lines of sections 1–4 were written for Android 15 and 16 and hold on Android 17; where a line names an Android version, read it as "that version or later".

**Before you start.** Install the debug build on both phones and finish onboarding. Save three contacts: one in Google with a photo, one phone-only, and one private contact (with a note). Keep `adb logcat` running on A and watch for crashes.

**Mark each line** Pass, Fail (with a screenshot) or Skipped (why).

## 1. Privacy and safety (about 20 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 1 | **Duress PIN.** Set a Parley PIN and a duress PIN, lock Parley, unlock with the duress PIN | A | Parley opens as usual, with no banner and the same wait. No private contacts, their calls, notes for calls, safe words or "Shared with" show anywhere: Contacts, Recents, search, widgets or notifications. The real PIN brings everything back unchanged | §29.2 3–8, §29.3 9–10 |
| 2 | **Lock-screen caller.** Set Caller on the lock screen to *Initials*, then to *Just "Incoming call"*. Lock the phone and call it from a saved contact | A, B | *Initials*: "AL" with no photo, number, label or note, also in the notification with full lock-screen content on. *Just "Incoming call"*: nothing about who it is. After unlocking, everything shows again | §30.2 4, §30.3 1–4 |
| 3 | **Private contact calls.** The private contact calls you; check Recents, then the system call log (another dialer, or `adb shell content query --uri content://call_log/calls`) | A, B | Recents shows the call with a lock. The system log loses it within a few seconds of the call ending | §30.2, CONTACT_MODEL.md |
| 4 | **Widgets while Parley is locked.** Place the Favourites and Circle widgets. Turn on the app lock with "Lock after" 1 minute, keep the phone unlocked throughout. Lock now; then unlock Parley, leave it with Home and wait two minutes; then tap a widget | A (Android 16), B (One UI launcher) | After Lock now, the widgets show counts, not names. After leaving Parley, they still show names at first, then counts within a few minutes of the minute passing, with no screen-off. The tap opens Parley's lock screen, and names come back a few seconds after unlocking | §35.1 9, §35.4 4 |
| 5 | **Shared labels.** Share "Family" between A and B through one Syncthing folder; add a contact on A | A + B | The contact shows on B within a sync. Leaving on A removes the label's page, and B shows "This label isn't on this phone any more" | §29.1 |

## 2. Calls (about 20 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 6 | **Speaker default.** Set Start calls on speaker to *Always*, then to *Numbers not in your contacts* | A, B | *Always*: the call starts on the speaker. Earbuds or a car keep the audio. A conference keeps the earpiece. *Not in contacts*: saved and private contacts stay on the earpiece. An emergency call is never switched | §33.1 1–4, §33.4 1–4 |
| 7 | **Flip to silence.** Turn it on; while the phone rings face up, turn it face down | A, B (B: check One UI's own "turn over to mute" is off) | The ringing stops, and the call keeps ringing silently and is not declined | §33.1 6 |
| 8 | **Send to another number** (deflect), on the incoming screen's ⋮ | A on a carrier that supports it; B notes the carrier | The call moves to the other number. On a carrier that refuses, a message after about 10 s says it is still ringing silently | §33.1 7, §33.4 6 |
| 9 | **Screen at your ear.** Choose *Once answered*, then place and answer a call | A, B | The screen stays on while dialling, then turns off at your ear once the call is answered | §33.1 5 |
| 10 | **Video call answered as voice.** Call the phone with ViLTE from another phone | A (carrier with ViLTE) | The call screen says it's a video call. Answering gives a voice call, and Recents shows the camera badge | §30.2 2–3 |
| 11 | **Scam help.** Answer a call from an unsaved number, then choose More › Is this a scam?. Hang up and check the post-call card | A | The six signs show. The post-call card offers Was it a scam?, with Call a saved number and Block | §33.1 9–10 |

## 3. Contacts and photos (about 15 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 12 | **Android 16 default account.** Set the phone's default account for new contacts to Google, then save a new contact, import a .vcf and restore one from History & undo | A only | Each one lands in Google. "Save new contacts to" says Android puts them in Google and offers no Device. On B (Android 15) nothing changes | §30.1 1–3, §30.3 6–7 |
| 13 | **Photo framing.** New contact › photo › Take photo, then frame it off centre, save, and check Google Contacts | A, B (B: Samsung camera app) | The camera app opens and Parley asks for no camera permission. Lists and the call screen show the framed circle. Rotating while framing keeps the picture | §32.2 1–8 |
| 14 | **Save and share the original.** Open a contact's photo and tap Save, then Share; repeat on the private contact | A, B | The saved file is the original image at full size. For a private contact, Parley asks to unlock first. In discreet mode there's no Save or Share | §34.1 1–6 |
| 15 | **Search filters.** Open Contacts search › Filters, choose two countries, then Has an email; also search "ana lisbon" | A | Only matching people show, each choice is a chip, and the search names the matched field. Typing stays smooth with thousands of contacts | §34.3 1–6, 9 |
| 16 | **Exports keep going.** Settings › Contacts › Export all to .vcf; leave Settings at once, then press Home | A, B | The export finishes and the file is complete. While away, an "Exports and imports" notification shows its progress. You get a snackbar if you are still in Parley, otherwise a notification when it ends. A failure is said in plain words, never as a technical error | §35.3 1–3, §35.4 3 |

## 4. Looks (about 5 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 17 | **Themes and sizes.** Check the call screen, Recents in Cards and the Filters sheet in light, dark and AMOLED, at the largest font, right to left and in landscape | A; B (One UI font scaling) | Nothing is cut off, sheets scroll, rows wrap and numbers stay left to right | §32.3 5, §33.1 12, §34.3 10 |

## 5. The 6.x features (about 35 min)

Riskiest first: Android 17 itself, then what depends on alarms, background work and the carrier, which no test on a computer can show. Install the debug build: a crash or "isn't responding" on any line shows "Parley stopped unexpectedly" at the next start, so save that report with the row.

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 18 | **Android 17.** Make Parley the default phone app on a fresh install, take a call, place one, then open Recents and a contact | A | Onboarding asks for the phone role and permissions as on Android 16; the call screen, Recents and the system call log behave the same. Nothing asks for a new permission | §30.1, §30.2 |
| 19 | **Rescue call in Doze.** Set a rescue call for in 15 minutes, lock the phone and leave it still, face down, unplugged | A, B, C | It rings, at most a few minutes late, full screen. On C note how late, and whether it rang at all with battery saver on | §41.4 3–4 |
| 20 | **Duress and Rescue.** Unlock with the duress PIN, then open Settings › Calls › Situations › Rescue call; get a call blocked by a rule from a private contact's number | A | Rescue shows nothing pending and no last choices. The blocked-call notification names no private contact, also on the lock screen | §44.4 |
| 21 | **Situations by time and by car.** Set a Situation for a window of 10 minutes; then one that starts when the car's Bluetooth connects | A, B, C | The window turns it on and off within a few minutes; calls follow it at once. Connecting the car turns it on, disconnecting turns it off. On C, check the window still ends with the screen off | §39.3 |
| 22 | **Archive with Google.** Archive a contact saved in Google, check Google Contacts on the web, then Unarchive | A, B | Archived: it leaves Google Contacts and every list, yet its calls ring as a saved contact with its name. Unarchived: it is back in Google, photo whole | §41.2 |
| 23 | **A card from a stranger.** Open a .vcf someone sent you that asks to be archived and a favourite, and scan a QR card with the same | A | Before "Import all" the dialog lists what is left out. The contact is in your lists, not a favourite, and their calls get the usual unknown-number help | §46.13 |
| 24 | **Shield between two phones.** Share a family label between A and B with the spam shield on; block a number on A | A + B | B warns about that number within a sync; nothing is shared until both have opted in | §40.2 |
| 25 | **"This number never calls you".** Save the second phone as a company you've only called, then let it call you | A, B | The calm card shows while it rings, with Is this a scam? | §39.1 1–4 |
| 26 | **Network names.** Take a call from an unsaved number on a carrier that sends the caller's name (CNAP: most US carriers, some in India) | A or B, by carrier | The name shows marked "From the network", and is offered in Search everything once Remember names from the network is on | §43.1, §44.1 |
| 27 | **Dead-number radar.** Call a number that no longer exists, twice | A, B | After the second failed call the contact page offers to check the number; a busy or unanswered call never counts | §40.3 |
| 28 | **Chapter end.** Give a label an end two minutes ahead and wait with Parley closed | A, C | One notification and a card on the label at the end, with nothing done by itself | §41.1 |
| 29 | **Ringing.** Ring style Increasing, then Vibrate first, then ring; take calls with the ringer on, on vibrate and with Do Not Disturb | A, B | The ring grows from quiet; after any call, even one Parley was closed during, the ring volume is back where you set it. Vibrate first stops at once when the phone goes silent | §45.5, §45.6, §45.8 1–3 |
| 30 | **SIM that learns.** On a dual-SIM phone, call the same number three times on the second SIM | B or C (dual SIM) | The fourth call offers that SIM first, and says why | §40.4 |
| 31 | **Crash report.** With Parley open, crash it (`adb shell am crash app.parley.phone.debug`; a force-stop is no crash), then open it again; once more with Keep crash reports off | A, B | One card, "Parley stopped unexpectedly", with Save a report, both times; the saved file has the stack, versions and phone model only, and the card doesn't come back | §46.15 |
| 32 | **Situation tile offer.** Remove the Situation tile from Quick Settings, then turn a Situation on from Settings › Calls › Situations | A, B (Android 13+) | Android's own "Add tile?" question appears once with Parley's Situation tile; turning another one on never asks again | §47.2 |
| 33 | **Search everything shortcut.** Press and hold Parley's launcher icon, choose Search everything | A, B, C | Recents opens with its search ready and the hint "Search everything", even with Recents hidden from the bar | §47.3 |

**When something fails,** note the phone, Android version, Parley build and the row number, and attach the logcat around it. Device-only flows (Telecom, widgets, the camera, the Storage Access Framework) can't be reproduced in unit tests, so the report is what gets them fixed.
