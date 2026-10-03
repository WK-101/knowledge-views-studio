# On-device checklist

One hour on real phones, riskiest first. Each line points at the full steps in [TESTING.md](TESTING.md). Nothing below has been run on a device yet; unit and Robolectric tests cover the logic, not the phone.

**Phones.** A: a Pixel on Android 16, signed in to Google, Parley as the default phone app. B: a Samsung on Android 15 (One UI). A second phone (or a friend) to call from. Where a line says "A only" or "B only", the other phone can skip it.

**Before you start.** Install the debug build on both phones and finish onboarding. Save three contacts: one in Google with a photo, one phone-only, and one private contact (with a note). Keep `adb logcat` running on A and watch for crashes.

**Mark each line** Pass, Fail (with a screenshot) or Skipped (why).

## 1. Privacy and safety (about 20 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 1 | **Duress PIN.** Set a Parley PIN and a duress PIN, lock Parley, unlock with the duress PIN | A | Parley opens as usual, with no banner and the same wait. No private contacts, their calls, notes for calls, safe words or "Shared with" show anywhere: Contacts, Recents, search, widgets or notifications. The real PIN brings everything back unchanged | §29.2 3–8, §29.3 9–10 |
| 2 | **Lock-screen caller.** Set Caller on the lock screen to *Initials*, then to *Just "Incoming call"*. Lock the phone and call it from a saved contact | A, B | *Initials*: "AL" with no photo, number, label or note, also in the notification with full lock-screen content on. *Just "Incoming call"*: nothing about who it is. After unlocking, everything shows again | §30.2 4, §30.3 1–4 |
| 3 | **Private contact calls.** The private contact calls you; check Recents, then the system call log (another dialer, or `adb shell content query --uri content://call_log/calls`) | A, B | Recents shows the call with a lock. The system log loses it within a few seconds of the call ending | §30.2, CONTACT_MODEL.md |
| 4 | **Widgets while locked.** Place the Favourites and Circle widgets. Turn on the app lock and discreet mode, then lock Parley | A (Android 16), B (One UI launcher) | Right after unlocking the phone, the widgets show no private contacts. A tap goes through Parley's lock | §32.2 9–13, §32.4 1, 4, 6 |
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
| 16 | **Exports keep going.** Settings › Contacts › Export all to .vcf; leave Settings at once, then press Home | A, B | The export finishes and the file is complete. You get a snackbar if you are still in Parley, otherwise an "Exports and imports" notification. A failure is said in plain words, never as a technical error | §35.3 1–3 |

## 4. Looks (about 5 min)

| # | Check | Phones | Expected | Steps |
|---|---|---|---|---|
| 17 | **Themes and sizes.** Check the call screen, Recents in Cards and the Filters sheet in light, dark and AMOLED, at the largest font, right to left and in landscape | A; B (One UI font scaling) | Nothing is cut off, sheets scroll, rows wrap and numbers stay left to right | §32.3 5, §33.1 12, §34.3 10 |

**When something fails,** note the phone, Android version, Parley build and the row number, and attach the logcat around it. Device-only flows (Telecom, widgets, the camera, the Storage Access Framework) can't be reproduced in unit tests, so the report is what gets them fixed.
