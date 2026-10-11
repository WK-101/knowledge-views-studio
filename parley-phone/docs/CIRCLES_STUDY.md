# Circles study: Bondwidth, a richer Circle, and Favourites vs Circle vs Labels

*11 Oct 2026. A read-only study for the owner. It compares Parley 6.5 (`fb002282`) with Bondwidth 1.0.0 (source read in full at `phonepvr/friends@dbcca0dc`), proposes a richer Circle, and answers whether Favourites, Circle and Labels should be merged. No code was changed. IDs here use the prefixes **CI** (Circle features) and **FC** (Favourites/Circle/Labels) so they don't collide with AUDIT_3's SA/PF/LC/UX/GP/SG.*

## 1. Summary

**Is our Circle at par with Bondwidth?** Mostly ahead, and behind in one way that people notice. Parley's Circle is deeper: it learns each person's natural rhythm, sends a gentle weekly digest instead of daily nags, counts chats you start from Parley, knows who usually reaches out first, shows calls left open on either side, keeps an agenda and promises, suggests a good time to call, and keeps notes encrypted and hidden under duress. Bondwidth is better at **showing the whole picture at a glance**. It has one Width tab with a health score, a "cadence breakdown" (on track / due soon / overdue / never), a "bonds need you" carousel, call analytics split into bonds and everyone else, and a fuller year-in-review that covers past years. Parley has most of the same numbers, but they are spread over the Circle rows, the People card in Call insights and the digest, and they only cover the last 365 days.

**What to build.** Not a score. Seven focused additions that reuse data Parley already has, ranked by value (section 4):

1. **Circle at a glance**: a header with counts and this month's reach, on the Circle itself.
2. **How you two keep in touch**: one plain sentence per person, built from rhythm, last contact, who reaches out first and open loops.
3. **Seasons**: a rhythm with an end ("every 3 days until Grandma is home, then monthly"), built on Chapters.
4. **Quieter than usual**: a gentle signal when someone you used to talk to often is drifting away, inside the weekly digest.
5. **Your turn**: when they have reached out the last few times and you're due, the digest and the pre-call peek say so.
6. **Your year**: the year in review becomes a short reflection page for past years too, with "closer" and "quieter" people.
7. **Next catch-up after a call**: one choice in "Anything to remember?" for Circle members.

All seven fall under one privacy rule: under a duress unlock, every relationship insight is hidden (**CI-0**). Today "who usually reaches out first" still shows under duress (**verified**: `Concealed` has no entry for it, and `PeopleCard.kt` reads only the config switch).

**Should Favourites, Circle and Labels be merged?** **No, not into one thing. Connect them instead.** They rest on three different storage truths, and each has a reason to stay where it is. Favourites are Android's star, which Do Not Disturb and other apps rely on. Labels are Android contact groups, synced with the account. The Circle is Parley-only, which keeps your relationships private from other apps and from Google. Collapsing them would either leak the Circle into Google or star people without asking, the confusion round 2 warned about. The confusion people actually feel comes from **placement and side effects**, not from having three ideas. The recommendation (section 5.4) gives the Circle one fixed home next to Favourites, keeps chosen favourites apart from people starred only for Do Not Disturb, lets a label feed the Circle, and offers one "who" picker everywhere. Nothing is written to Android that isn't written today, and no data is moved.

## 2. Bondwidth

| | |
|---|---|
| What | An offline dialer, contacts manager and "relationship companion" (`com.phonepvr.friends`, F-Droid 1.0.0, added 18 Jul 2026) |
| Source | <https://github.com/phonepvr/friends>, cloned and read (171 Kotlin files, ~27.8k lines). Its build was not run |
| Licence | **GPL-3.0-only**. The phone and contacts parts are ported from Fossify Phone and Fossify Contacts (GPL-3.0). Parley is GPL-3.0 too, so borrowing would be legally possible with attribution, but nothing here copies code. Every proposal is designed for Parley's own model |
| Network | No `INTERNET` permission. CI fails if one appears |

**How its relationship side works** (from the code):

- **Bonds.** A separate `people` table copied from a contact when you "Track" it: name, photo, numbers, birthday, wedding anniversary, a free-text relationship tag, notes and a cadence in days (a default cadence applies when you start tracking). Untracking archives the row and keeps the history. A reconciler re-links a bond when the contact's lookup key changes.
- **Timeline.** A `timeline_entries` table. Calls are **copied** from the call log on every launch, on a call-log observer and every 6 hours. Only answered calls longer than 0 s count as contact. Meets, messages and "other" are logged by hand, with a date and a note, and can be edited or deleted in bulk. After you tap Call, SMS, WhatsApp or Signal on a bond, it asks "Log this … as an interaction?". For a call this can count twice: once by hand and once from the call log.
- **Cadence** (`CadenceCalculator`): Not tracked · Never contacted · On track · Due soon (3 days before) · Overdue.
- **Connection health** (`ConnectionHealth`): each bond scores 1.0 on track, 0.7 due soon, 0.4 never contacted, and 0.5 falling to 0 as it gets overdue (by one more cadence). The average is shown as **0–100**, with a **trend against 30 days ago** that is worked out by replaying the history up to that date (no snapshot table).
- **Width tab** with three sub-tabs. **Health**: the score, a "cadence breakdown" card and "N bonds need you" (6 at most). **Calls**: talk time and call counts split into bonds, saved contacts and unknown numbers, "x% of your talk time was with your bonds", bonded reach ("12 of 20 bonds heard from you"), top callers, in/out/missed and "worth a call back" (unreturned missed calls), over 30/60/90/365 days. **Reflection**: year in review for any year (most connected, least connected, longest gap with its dates, birthdays acknowledged with a same-day contact, a breakdown by type).
- **Reminders.** One daily job: a notification per birthday or anniversary within N days, with **Mark as wished** (it logs a contact, which resets the cadence), and **one summary of everyone overdue**, every day.
- **Widget.** Upcoming dates, people you haven't spoken to and a daily quote.
- **Other.** Daily quote (you can add your own), quick replies, app lock, a screenshot-blocking mode, backups as JSON with an optional AES-GCM passphrase (automatic folder backups are not encrypted), and a separate in-app favourites table that is not Android's star.

## 3. Comparison

"Better" or "worse" is Parley compared with Bondwidth.

| Feature | Bondwidth | Parley | Verdict |
|---|---|---|---|
| Who is tracked | Its own copy of the contact (a second source of truth that needs reconciling) | A view over contacts: a rhythm in `contact_meta`, nothing copied | **Better** |
| How often | Fixed days, one default for all | Fixed, or **Natural rhythm** learned from your history (median gap ×1.5, relearned monthly); a label can suggest the rhythm | **Better** |
| Status | On track · Due soon · Overdue · Never contacted | Fine · Soon · Due ("Not in touch yet" counts as Due) | Same; Bondwidth's separate "never contacted" is a little clearer |
| What counts as contact | Answered calls, hand-logged entries, wished | Answered calls, logged chats and visits, **chats and video calls started from Parley** (per app: Always / Ask / Never), wished | **Better** (no double counting) |
| Reminders | Daily list of everyone overdue | Weekly digest of 3 at most (one due, one date or life event, one person quiet for a year) or as-due with a weekly cap; "Not now" doubles the gap; nothing escalates | **Better** (kinder); Bondwidth's is simpler |
| Dates | Birthday and wedding anniversary, kept in its own table | Android's dates incl. custom, lunar/Hebrew/Hijri calendars, lead days, Mark as wished, life events "remembered yearly" | **Better** |
| Timeline | Own copy of calls plus manual entries; bulk delete | Calls, logged moments, notes and dates merged, with search and filters | **Better** |
| Health score 0–100 with trend | Yes | No | **Missing** (on purpose, see CI-1) |
| Cadence breakdown at a glance | Yes, one card | Only a chip on each row | **Worse** → CI-1 |
| "Needs you" list | Carousel of 6 | Circle sorted by urgency, widget, digest | Same |
| Reach this month | "12 of 20 bonds" by call, per window | "In touch with 9 of 17 this month, up from 6" (People card in Call insights) | Same, but ours is two screens away from the Circle → CI-1 |
| Who reaches out first | No | Yes, per person (private, can be hidden) | **Better** |
| Unanswered both ways | "Worth a call back" (their missed calls) | Open loops: their call **and** your unanswered tries, fading after 30 days; To call | **Better** |
| Share of talk time with your people | Yes ("41% with your bonds") | No | **Missing** (small) → CI-1 |
| Year in review | Any year; most and least connected; longest gap with dates; occasions acknowledged; by type | Last 365 days only; top 3; longest gap; occasions | **Worse** → CI-6 |
| Per-person summary | "N interactions · X on calls, last 365 days" on the person | Timeline and call history, no one-line summary | **Worse** (small) → CI-2 |
| Notes, agenda, promises | Free-text notes | Notes for calls, call notes, `[ ]` promises, "To talk about", "Did you cover these?", pre-call peek, good time to call with their local time, follow-up reminders | **Much better** |
| Suggestions | None | "Suggested for your Circle" with a rhythm from your history | **Better** |
| Widget | Dates, quiet people, quote | Dates and people with a Call button; only counts while locked with the app lock | **Better** (privacy) |
| Privacy of this data | Room database, notes in plain text; app lock; screenshot blocking | Notes sealed with Keystore; hidden under duress; lock-screen rules; private contacts' Circle data travels only sealed | **Better**, except CI-0 |
| Backup | JSON, passphrase optional; automatic folder backups unencrypted | Encrypted backup with a Circle section; restore matches people by number | **Better** |
| Favourites | Its own table, invisible to Android and Do Not Disturb | Android's star (Do Not Disturb, other apps, sync) | **Better** |
| Groups | Filter and edit membership | Labels with ringtone, SIM, rhythm, Do Not Disturb, vibration, auto-answer, rules, Chapters, sharing, family shield | **Much better** |
| One concept for "my people" | Bonds (plus favourites and groups, the same split as ours) | Favourites, Circle, Labels (plus Frequent, To call) | Same problem in both |
| Daily quote | Yes | No | Not wanted (gimmick) |

**Where Bondwidth is honestly better:** the overview. One place answers "how am I doing with my people?", and its year review is a real reflection. Its single concept, "track this contact", is also easier to grasp on day one. Everything else is either the same or deeper in Parley.

## 4. A richer Circle, from the ground up

**Principles** (Parley's, applied to the Circle):

- **Describe, don't grade.** No score. A number for your relationships invites guilt and gaming. Under coercion it is one easy-to-read verdict about your private life. With natural rhythms, "overdue" is already relative. We show counts and plain sentences instead.
- **Calls are facts, chats are hints.** The call log says who started each call and whether it connected. A chat opened from Parley is only "you reached out". Nothing claims to know what happened inside another app.
- **Nothing new is stored where something can be worked out.** Every insight below is computed when shown, from the call log index, interactions, rhythms and dates. Nothing new goes to backups or disk, except Seasons (one end date).
- **No new permission, no new setting row.** Every addition rides on an existing switch: the People card choice, "who reaches out first", the digest, Anything to remember?, Chapters.
- **One voice, at most once a week.** New signals go into the existing digest and its three places, or onto surfaces you open yourself. Never a new notification type.

### 4.1 Ranked features

| Rank | ID | Feature | Value | Effort |
|---|---|---|---|---|
| — | CI-0 | Relationship insights hidden under duress | Privacy promise | S |
| 1 | CI-1 | Circle at a glance | High, every visit | S |
| 2 | CI-2 | How you two keep in touch | High | S |
| 3 | CI-3 | Seasons (a rhythm with an end) | High at hard times | M |
| 4 | CI-4 | Quieter than usual | Medium–High | M |
| 5 | CI-5 | Your turn | Medium | S |
| 6 | CI-6 | Your year | Medium, yearly | M |
| 7 | CI-7 | Next catch-up after a call | Medium | S |
| later | SG-10 | Care circle (already planned, after Phase B) | High for some | L |

**Not recommended:** a 0–100 health score; streaks or badges; a "relationship strength" ranking of people; mood ratings after calls; daily quotes; a separate Width tab (it would add a place; CI-1 and CI-6 give the overview where the Circle already is).

### 4.2 Each feature

**CI-0. Relationship insights hidden under duress.**
- *Problem:* under a duress unlock Parley hides Circle notes, but "who usually reaches out first", reach and the year review still show. To a controlling partner these say exactly who matters to you and who chases whom.
- *How:* add one `Concealed.RELATIONSHIP_INSIGHTS`. While it is on, the People card shows only reach counts without names, and the first-mover line, CI-2's sentence, CI-4, CI-5 and CI-6 disappear, with nothing on screen that says so. Circle membership and the Due/Soon/Fine chips stay, so the app looks normal. Add one line to the duress test.
- *Reuses:* `Privacy.duressOnly()`, the existing duress test. *Effort:* S. *Risk:* none. It should ship before any of CI-2 to CI-6.

**CI-1. Circle at a glance.**
- *Problem:* "How am I doing with my people?" needs three screens today.
- *How:* a quiet header on the Circle (the tab or its section): **"2 due · 3 soon · 11 fine"**, where each count is a filter chip, plus **"In touch with 9 of 16 this month · up from 6"**. A third line, only when there is call history: **"38% of your talk time was with your Circle"**. Tapping the header opens the People card. No score and no colours beyond the existing chips.
- *Reuses:* `CirclePlanner.status`, `PeopleInsights.reach`, the call log index's durations. *Privacy:* counts only; under CI-0 the talk-time share goes. *Effort:* S. *Risk:* clutter on a small Circle, so hide the header below 4 members.

**CI-2. How you two keep in touch.**
- *Problem:* the row says "Last in touch 23 days ago · call", but not whether that is normal for you two.
- *How:* on the contact page's Stay in touch card, and as the pre-call peek's first line, one sentence built from at most three facts, most useful first:
  - *rhythm:* "You usually talk every 2–3 weeks; it's been 5." (natural rhythm, or the learned gap even in Every mode);
  - *balance:* "Ana usually calls first." (existing first mover);
  - *loose end:* "Her call on Tuesday is still unanswered." (open loops);
  - and a footnote line, "14 calls, 3 h 10 min this year · 6 chats started from Parley", which is Bondwidth's per-person summary.
- *Reuses:* `NaturalRhythm.learn`, `PeopleInsights.firstMover`, `openLoops`, `touches`. *Privacy:* computed when shown; hidden under CI-0; never on the lock screen or the call screen while ringing. *Effort:* S. *Risk:* wording that sounds like blame. Use the existing kind voice and never "you failed to".

**CI-3. Seasons: a rhythm with an end.**
- *Problem:* life changes how often you need to be in touch: someone in hospital, a new baby, a friend going through a breakup, a parent living alone after a loss. Today you change the rhythm and must remember to change it back.
- *How:* on the rhythm dialog, **"For a while…"**: a temporary rhythm ("every 3 days") with an end ("for 4 weeks" or "until 30 Nov"). The row shows "Every 3 days · until 30 Nov", then the usual rhythm comes back by itself, with a one-line note in the next digest ("Grandma's season ended; back to every 2 weeks"). For a group, a **Chapter can carry a rhythm**: "Hospital stay" ends 30 Nov, and its members get "every 3 days" while it runs. This joins Chapters (a label with an end) and the label's rhythm policy, both of which exist.
- *Reuses:* `KeepRhythm` (one more optional field, `seasonDays` plus `seasonUntil`, in the same JSON), `Chapters`, `LabelPolicy.rhythmDays`, the digest. *Privacy:* stored like the rhythm (Parley-only, backed up in the Circle section); a season's name is the label's or none. *Effort:* M. *Risk:* stored format: an older Parley reading a newer backup ignores the unknown fields (`Codecs.stored` has `ignoreUnknownKeys`), so the person simply keeps their usual rhythm there. Add a test.

**CI-4. Quieter than usual.**
- *Problem:* drifting apart happens to people you never put in a Circle: the friend you called weekly last year and monthly since spring. Due reminders only cover the Circle and a fixed gap.
- *How:* compare two windows of your own history with a person (the last 6 months against the 12 before). When the learned gap has at least doubled, there were at least 6 conversations before, and the person isn't in a Season, they become a candidate. At most **one** a week, in the digest's existing serendipity place: "You and Marco used to talk every week; since June it's been about monthly." For someone outside the Circle the actions are **Call**, **Add to your Circle** and **They're fine**. "They're fine" silences that person for a year, stored like a snooze.
- *Reuses:* `NaturalRhythm.learn` (twice), `CircleDigest.pick`'s quiet slot, `CircleSuggestions`. *Privacy:* computed weekly, nothing stored but the "They're fine" mark; hidden under CI-0. *Effort:* M. *Risks:* false alarms (people who moved in together, a seasonal colleague, someone you now message in another app). Thresholds stay strict and one tap ends it for that person. Tune on the owner's own history before release.

**CI-5. Your turn.**
- *Problem:* reciprocity matters more than frequency: people feel it when they always have to make the first move.
- *How:* when a Circle member is due **and** started the last 3 or more conversations, the digest line becomes "Ana has called you the last 3 times. Maybe call her?", and the peek's CI-2 sentence leads with it. Your chats started from Parley count as you reaching out, never as them. Nothing is said the other way round ("you always call first"): that is information, not a nudge, and it stays on the contact page.
- *Reuses:* `PeopleInsights.firstMover` (a "last N" variant), `CircleDigest`. *Privacy:* follows the existing "who reaches out first" switch; hidden under CI-0. *Effort:* S. *Risk:* none beyond tone.

**CI-6. Your year.**
- *Problem:* reflection is the one thing Bondwidth does better: a proper look back, any year.
- *How:* "Your year" on the People card opens a short page, with a year chip row for every year with history: in touch N times; most in touch (top 3); **closer this year** and **quieter this year** (the 3 people whose conversation count changed most against the year before, among people with at least 4 in either year); longest gap in the Circle, with its dates; occasions acknowledged; calls against chats started from Parley. One action at the end: **"Add to your Circle"** for the quieter people who aren't in it. Around 1 January, the digest offers it once.
- *Reuses:* `PeopleInsights.yearInReview` (given a year instead of "last 365 days"), touches. *Privacy:* computed when opened; hidden under CI-0. History before Parley's own copy of calls depends on Android's call log, so older years may be thin; the page then says "Parley has calls from March 2025". *Effort:* M. *Risk:* slow over many years of calls; compute one year at a time from the call log index.

**CI-7. Next catch-up after a call.**
- *Problem:* after a good call you often know when you should talk again ("let's speak after her results on the 14th").
- *How:* for Circle members, "Anything to remember?" (already opt-in) gains one row: **Next catch-up: Usual rhythm · In a week · On a date**. "On a date" sets a one-off due date that replaces the rhythm's next due, once. It shows in To call, and in Next up once SG-5 lands. This is SG-1 "Promise a time" for people you keep in touch with, and it should share its storage.
- *Reuses:* the memory prompt, `KeepRhythm.snoozedUntil`'s sibling (`nextDue`), To call. *Privacy:* as To call. *Effort:* S. *Risk:* overlap with SG-1. Build both on one "promised time" record.
- *Built in 6.7:* `CatchUp` (core/common). "In a week" and "On a date" set the item; the keep-in-touch reminders and digest, and Next up, treat the date as the rhythm's quiet time (`CatchUp.quietUntil`). The Circle screens and the widget don't read it yet.
- *Storage (built with SG-1 in 6.6):* the record is `PromisedTime` (`at`, `made`, `told`, `zone`) on the person's `ToCallItem.promised`. SG-1 writes it with `told = true`; CI-7 writes the same record with `told = false` (a date you keep for yourself, never said back on the call screen or in To call's "You said you'd call" line) on a To call item for the member's number, so To call and Next up read one place. `ToCall.remind` keeps an existing promise when the reminder moves; a call with them settles the item and the promise with it.

**About shared family circles.** SG-10 Care circle ("last spoke with Grandma: yesterday", shared through a shared label) remains the right design and stays after Phase B. When it is built, it shares only "in touch on [date] by [member]", never notes or durations. Each member opts in per label, and a Season on that label (CI-3) is shared with it, so the family keeps the same rhythm while Grandma is ill.

## 5. Favourites, Circle and Labels

### 5.1 What each means today

| | Favourites | Circle | Labels |
|---|---|---|---|
| In one line | Who I want one tap away | Who I don't want to drift from | How I sort everyone |
| Stored as | Android's **star** (private contacts: Parley's own star); order kept by Parley | Parley's `contact_meta` rhythm; **never** in Android | Android **contact groups**, matched by title across accounts |
| Seen by other apps / Google | Yes, and it syncs | No | Yes, and it syncs (per account) |
| Where it shows | Favourites tab, or a section or strip in Contacts; Frequent under it; Favourites widget; launcher shortcuts | Circle tab (hidden by default), or a section at the top of Favourites, or in Contacts when Favourites moved there; Circle widget; weekly digest | Contacts chips, label pages, pickers |
| What it changes | Situations "Favourites may ring", off hours, auto-answer for favourites, drive profile, Android Do Not Disturb (starred), loud favourites | Reminders, digest, "Log this?", People card | Ringtone, SIM, vibration, auto-answer, a suggested Circle rhythm, **Do Not Disturb by starring members**, block/allow rules, Situations "a label may ring", time limits, Chapters, sharing, family shield |

### 5.2 Where they overlap

| Overlap | Where | Effect on people |
|---|---|---|
| **Do Not Disturb stars a label** | `LabelPolicy.allowThroughDnd` stars every member (tracked in `DndStars`) | Favourites fills with people you never chose. It is disclosed, but afterwards Favourites can't tell "chosen" from "starred for Family" |
| **The Circle has no fixed home** | `HomeLayout.circleHost`: a tab, or the top of Favourites, or Contacts, depending on 3 layout settings | "Where's my Circle?" One person can see it in three places over a week of trying layouts |
| **"Who may ring" has several sources** | Favourites, a label, the DND star, auto-answer per person, per label and for favourites | The same question gets different answers on different screens (UX-11 already plans one model) |
| **Rhythm in two places** | Circle rhythm per person; the label's rhythm, used only as a suggestion when someone joins | Setting "Family: every 2 weeks" doesn't put Family in the Circle, which people expect |
| **Two widgets** | Favourites widget and Circle widget, with duplicated plumbing (round 2 A21) | Small; mostly code |
| **The same people everywhere** | Family is often a label, the favourites and the Circle | Three gestures to say one thing; no way to do it from the label |

**What is not duplicated:** the data. Each fact is stored once, in the one place that can hold it. The overlap is in the **experience**: where things show, and the side effects.

### 5.3 Options

| Option | What it means | Good | Bad | Verdict |
|---|---|---|---|---|
| **A. Keep separate, clarify** | Status quo plus clearer words and fixed placement | No migration; matches AUDIT_2/3 | Leaves the DND star mix-up and the label/Circle gap | Not enough on its own |
| **B. One model with rings** | "Inner ring" = favourites, "Keep in touch" ring = Circle, everyone else outside | One idea to learn | Favourites and Circle aren't nested: the plumber is a favourite, the cousin abroad is Circle only. Rings would star Circle members, letting them through Do Not Disturb unasked (the confusion round 2 warned about) | **Reject** |
| **C. Labels as the only primitive** | Favourites and Circle become special labels; everything is a smart view of labels | Most elegant on paper | A Circle label would be an Android group: other apps and Google would see who you keep in touch with (a privacy regression). Groups are per account, so a person can't be in a group of another account. The star must stay, because Android's Do Not Disturb only knows starred contacts | **Reject** |
| **D. Keep three, connect them** *(recommended)* | Three primitives on their own storage. One home for Favourites and Circle, labels can feed both, side effects kept visible and apart, one "who" picker | Fixes what people trip over; no data moves; nothing new reaches Android or Google | Still three words to learn, helped by the glossary, explainers and fewer places | **Recommend** |

### 5.4 Recommendation (D): keep three, connect them

1. **FC-1. Chosen favourites and starred-for-Do-Not-Disturb, apart.** Favourites shows the people you starred, then a folded row: **"Also starred for Do Not Disturb: 4 (Family)"**. Opening it lists them, with **Make a favourite** (keeps the star, removes the label's claim in `DndStars`) and **Open label**. Android sees no change: they are all starred, as before. *Reuses* `DndStars`, which already knows who Parley starred and for which label. *Effort* S. **Shipped in 6.6:** `DndStars.split`; Favourites and Favourites in Contacts show the folded row, and its sheet offers Make a favourite (with Undo) and Open for each label.
2. **FC-2. One home for the Circle.** Where Favourites live (tab, or the section or strip in Contacts), a two-way switch at the top: **Favourites · Circle**. The "Circle as a section in Favourites or in Contacts" rules go. The optional Circle tab stays for people who chose it; when it is shown, the switch isn't. Favourite tiles show a small Due dot for Circle members, and Circle rows show the star. *Effort* S–M. Old layout values keep working, because `circleHost` simply always resolves to the Favourites surface.
3. **FC-3. A label can feed the Circle.** The label policy row "Keep in touch when they join your Circle" becomes **"Keep in touch with everyone here: every 2 weeks"**. While it is on, members are in the Circle **through the label** ("In your Circle via Family"). Leaving the label takes them out unless they have a rhythm of their own, and a person's own rhythm always wins. The Circle list counts each person once, with the shortest rhythm. Labels that already have a rhythm keep today's behaviour (a suggestion) until you switch this on. Nothing is turned on by an update. *Reuses* `LabelPolicies.rhythmFor`, `CircleRepository.members` (members become own ∪ label-fed). *Effort* M.
4. **FC-4. One "who" picker.** Situations, off hours, auto-answer and the drive profile all ask "who" with the same choices: **Everyone · Contacts · Favourites · Your Circle · A label…**. "Your Circle" works in Parley's own screening (off hours silences the others), with no Do Not Disturb access needed; the picker says that Android's own Do Not Disturb follows Favourites only. Lands with **UX-11** (D10), which already rebuilds the "who may ring" model. *Effort* M as part of UX-11.
5. **FC-5. Bulk from a label.** The label page (after UX-6's "Add people") gets **Add everyone to Favourites** (asks, lists who, Undo) and the FC-3 switch, so "Family are my people" is one place and two taps.
6. **FC-6. One People widget, later.** Merge the two widgets' code (round 2 A21) into one widget with a Favourites or Circle mode chosen when placing it. Existing widgets keep working. *Effort* S–M. Optional.
7. **Words.** The glossary's "Which one?" stays the definition. Explainers gain one line each: Favourites "one tap away, and they can ring through Do Not Disturb"; Circle "keep in touch; private to Parley, never starred"; Labels "sort and set rules; a label can fill your Favourites or your Circle".

**Migration (never loses data, stays Android-compatible):**

| Step | What changes on disk | Android star / groups | Undo / fallback |
|---|---|---|---|
| FC-1 | Nothing new: reads `DndStars` | Unchanged | — |
| FC-2 | Nothing: `HomeLayout.circleHost` resolves differently; `favoritesSectionCollapsed` is reused for the switch's last choice | Unchanged | Turning on the Circle tab gives today's tab |
| FC-3 | `LabelPolicy` gains `circleFromLabel: Boolean` (default false; lenient decoding ignores it in older versions). Membership = own rhythm ∪ label-fed, computed. Backup: in the label policies, which are already backed up | Unchanged (a Parley-only policy, by title like the others) | Switching it off removes only label-fed members; their history stays (calls and interactions are never deleted) |
| FC-4 | Situation and off-hours "allow" gain a `CIRCLE` value. Old values are unchanged. **Check before shipping:** `Codecs.stored` ignores unknown keys but not unknown enum values, so an older Parley restoring a newer backup could fail to read the Situation. Write `CIRCLE` so it reads as Favourites in older versions (a separate field, or a tolerant codec), with a test | Unchanged | Choosing Favourites again |
| FC-5 | Stars written only after a confirmation listing who, recorded as chosen stars (not `DndStars`) | Writes stars, as starring by hand does | Undo unstars exactly those |

**Navigation, settings and screens after D:**

- **Navigation:** the bar is unchanged (Favourites, Recents, Contacts, Keypad, optional Circle). One fewer rule decides where the Circle appears.
- **Settings:** no new rows. "Favourites in Contacts" and the Circle tab switch stay. Circle settings opens its own group (UX-15). The label policy row is renamed, not added.
- **Screens:** the Favourites surface gets the Favourites · Circle switch and the folded "Also starred for Do Not Disturb" row. The label page gets "Keep in touch with everyone here" and "Add everyone to Favourites". The contact page shows "In your Circle via Family" when that applies. Every "who" choice uses one picker.

This confirms AUDIT_3 §5 ("keep apart") for the **concepts** and refines it for the **experience**: the three stay apart, and the places where they touched by accident are joined on purpose.

## 6. Plan (6.6 and later)

It fits around AUDIT_3 §9. 6.5 is merged (`fb002282`), so the next release is 6.6.

| Release | AUDIT_3 contents (unchanged) | Added from this study | Why here |
|---|---|---|---|
| **6.6** Daily signature, wave 1 | SG-1, SG-2, SG-3, SG-4 | **CI-0** (duress hides relationship insights), **FC-1** (starred for Do Not Disturb kept apart) | Both are S and fix promises Parley already makes (duress; "starring is never silent"). CI-0 must come before any new insight |
| **6.7** Now and next | SG-5 + UX-13 + GP-7, SG-6, UX-11 (D10), UX-14 | **CI-1** glance, **CI-2** keep-in-touch sentence, **CI-5** your turn, **CI-7** next catch-up (sharing SG-1's promised time), **FC-2** one home for the Circle, **FC-4** one "who" picker inside UX-11 | Next up and the peek consume CI-2, CI-5 and CI-7; FC-4 is part of the "who may ring" rebuild; FC-2 touches the same home surfaces |
| **6.8** Occasional and safety | SG-7, SG-8, SG-9 | **CI-3** Seasons (with Chapters), **CI-4** quieter than usual, **FC-3** label-fed Circle, **FC-5** bulk from a label | Format additions (KeepRhythm, LabelPolicy) after the 6.5 storage work; CI-4 needs tuning on real history from the device run |
| **6.9** | — | **CI-6** Your year, **FC-6** one People widget (optional) | Best shipped before 1 January, when people look back |
| **After Phase B** | SG-10 Care circle | Care circle carries Seasons | Needs other Parley users |

**Every item** lands with its Tools row (UX-1 rule), a device-checklist line and a duress-test line (SA-1 rule), and adds no settings row.

## 7. Decisions for the owner

| # | Question | Recommended answer |
|---|---|---|
| C1 | Adopt a 0–100 "connection health" score like Bondwidth? | **No.** Show counts and plain sentences (CI-1, CI-2). A score grades your relationships, invites guilt and is one easy verdict under coercion |
| C2 | Hide relationship insights (who reaches out first, reach names, the new lines) during a duress unlock? | **Yes, in 6.6 (CI-0).** It closes a gap in an existing promise |
| C3 | Build Seasons on Chapters (a label's Chapter can carry a rhythm) as well as per person? | **Yes.** One idea ("for a while") on two existing tools, no new place |
| C4 | Let Parley suggest people who are drifting **outside** the Circle (CI-4)? | **Yes, at most one a week in the digest**, with "They're fine" ending it for a year |
| C5 | Merge Favourites, Circle and Labels into one concept? | **No.** Keep three primitives and connect them (Option D) |
| C6 | Give the Circle one fixed home as a Favourites · Circle switch (FC-2), dropping "Circle inside Contacts"? | **Yes**, in 6.7. The optional Circle tab stays |
| C7 | Let a label put its members in the Circle (FC-3)? | **Yes**, off until switched on per label; a person's own rhythm wins |
| C8 | Offer "Your Circle" as a "who may ring" choice (FC-4)? | **Yes**, inside UX-11. Say plainly that Android's own Do Not Disturb follows Favourites only |
| C9 | Show people starred only for Do Not Disturb apart from chosen favourites (FC-1)? | **Yes**, in 6.6 |
| C10 | Merge the two widgets (FC-6)? | **Optional**, 6.9. Do it if the code merge (round 2 A21) is wanted anyway |
| C11 | Borrow any Bondwidth code (GPL-3.0-only)? | **No need.** Everything here builds on Parley's own code; if a snippet is ever reused, keep its file header and attribution |

**Owner decisions (11 Oct 2026):** C1–C10 accepted as recommended. CI-0 shipped in 6.5; FC-1 goes into 6.6; the rest follow the plan above.
