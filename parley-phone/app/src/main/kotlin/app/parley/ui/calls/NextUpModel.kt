package app.parley.ui.calls

import app.parley.common.CallEntry
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.people.ContactRef
import app.parley.common.people.PrivateDates
import app.parley.data.vault.VaultRepository
import app.parley.data.vault.VaultSummary
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CatchUp
import app.parley.common.calls.NextUp
import app.parley.common.calls.NextUpKind
import app.parley.common.calls.NextUpLine
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallState
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.GoodTime
import app.parley.common.circle.Occasion
import app.parley.common.circle.Occasions
import app.parley.common.people.Chapter
import app.parley.common.people.Chapters
import app.parley.common.security.PrivacyView
import app.parley.common.suspendRunCatching
import app.parley.data.ContactsRepository
import app.parley.data.NumberInfo
import app.parley.data.circle.CircleRepository
import app.parley.data.people.IcuCalendars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/** One line of Next up, with what its row shows and does. */
sealed interface NextUpItem {
    val line: NextUpLine

    /** The calls owed now: how many, the first names, and the earliest time you told one of them you'd call. */
    data class Owed(override val line: NextUpLine, val rows: List<ToCallRow>, val promisedAt: Long?) : NextUpItem

    /** Nothing owed now; [count] items wait for later. */
    data class Later(override val line: NextUpLine, val count: Int) : NextUpItem

    data class Dated(override val line: NextUpLine, val person: Person, val occasion: Occasion) : NextUpItem

    data class GoodHours(override val line: NextUpLine, val person: Person, val window: GoodTime.Window) : NextUpItem

    data class ChapterEnds(override val line: NextUpLine, val title: String, val lastDay: LocalDate) : NextUpItem
}

/** A contact a line is about: who to open ([ContactRef.navId]: negative for a private contact), and the number its Call uses. */
data class Person(val contactId: Long, val name: String, val number: String?) {
    /** A private contact: the line goes the moment private contacts may not show. */
    val private: Boolean get() = contactId < 0
}

/** Whether this line is about a private contact. */
private val NextUpItem.private: Boolean
    get() = when (this) {
        is NextUpItem.Dated -> person.private
        is NextUpItem.GoodHours -> person.private
        else -> false
    }

/**
 * Next up, the strip at the top of Recents ([NextUp]): the To call list's count ([ToCallModel]) with today's birthdays and
 * anniversaries, a Circle member due in their good hours and a Chapter ending this week. The slower sources are read
 * again every [REFRESH_MS], when the contacts or the calls change, and when the privacy view does (a duress unlock
 * hides the Circle's line, a relationship insight). Private contacts' birthdays (their caller-ID copy's dates) and
 * Circle catch-ups join while private contacts may show ([PrivateDates.mayShow]); hiding or locking them, or a duress
 * unlock, takes their lines away at once, before the slower sources are read again.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // One store per kind of line it reads, passed in so tests use the real ones.
class NextUpModel(
    scope: CoroutineScope,
    toCall: ToCallModel,
    toCallState: StateFlow<ToCallState>,
    calls: StateFlow<List<CallEntry>?>,
    private val contacts: ContactsRepository,
    private val circle: CircleRepository,
    chapters: StateFlow<Map<String, Chapter>>,
    privacy: StateFlow<PrivacyView>,
    private val countryIso: String,
    vault: VaultRepository,
) {
    /** Private contacts may show here now: shown, not locked with "Lock private contacts", no duress unlock hiding them. */
    private val privateOpen: Flow<Boolean> = combine(privacy, vault.lock.lockedByPerson) { p, locked -> PrivateDates.mayShow(p, locked) }

    /** The listed private contacts while they may show, else none. */
    private val privatePeople: Flow<List<VaultSummary>> =
        combine(privateOpen, vault.contacts) { open, list -> if (open) list.filterNot { it.archived } else emptyList() }

    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MS)
        }
    }

    private data class Sources(
        val calls: List<CallEntry>?,
        val contacts: List<ContactSummary>?,
        val insights: Boolean,
        val tick: Long,
        val privates: List<VaultSummary> = emptyList(),
    )

    private val candidates: Flow<List<NextUpItem>> =
        combine(calls.debounce(CALLS_DEBOUNCE_MS), contacts.contacts, privacy, privatePeople, ticks) { cl, ct, p, pv, t ->
            Sources(cl, ct, p.insightsShown, t, pv)
        }
            .combine(chapters) { s, ch -> s to ch }
            .combine(toCallState) { (s, ch), state -> Triple(s, ch, state) }
            .mapLatest { (s, ch, state) -> read(s, ch, state) }
            .flowOn(Dispatchers.IO)

    /** The lines, at most [NextUp.MAX_LINES], best first; empty when nothing is due. */
    val items: StateFlow<List<NextUpItem>> = combine(toCall.rows, candidates, privateOpen) { rows, all, open ->
        // Private lines go the moment private contacts may not show, not at the next read.
        val found = if (open) all else all.filterNot { it.private }
        val now = System.currentTimeMillis()
        val count = ToCall.count(rows.map { it.entry }, now)
        val keys = rows.map { it.entry.key }.toSet()
        val byLine = found.associateBy { it.line.kind to it.line.id }
        NextUp.lines(count, keys, found.map { it.line }).mapNotNull { line ->
            when (line.kind) {
                NextUpKind.TO_CALL -> {
                    val due = rows.filter { it.entry.isDue(now) }
                    NextUpItem.Owed(line, due, due.mapNotNull { it.entry.promisedAt }.minOrNull())
                }
                NextUpKind.LATER -> NextUpItem.Later(line, count.later)
                else -> byLine[line.kind to line.id]
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    private suspend fun read(s: Sources, chapters: Map<String, Chapter>, state: ToCallState): List<NextUpItem> {
        val device = s.contacts ?: return emptyList()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val byId = device.associateBy { it.id }
        // Private contacts as rows of their own, under their navigation id and Parley key (the Circle keeps them so).
        val people = device + s.privates.map { v -> asPerson(v) }
        val today = LocalDate.now(zone)
        val out = ArrayList<NextUpItem>()
        out += (occasions(byId, today) + privateOccasions(s.privates, today)).sortedBy { it.occasion.ordinal }
        // The Circle's line is a relationship insight: a duress unlock hides it.
        if (s.insights && s.calls != null) out += goodHours(people, s.calls, state, now)
        chapters.filterValues { NextUp.chapterEndingSoon(it.endsAt, now) }.entries.sortedBy { it.value.endsAt }.forEach { (title, ch) ->
            out += NextUpItem.ChapterEnds(NextUpLine(NextUpKind.CHAPTER, title), title, Chapters.lastDay(ch, zone))
        }
        return out
    }

    /** Today's birthdays first, then anniversaries; the dead are never called out. */
    private suspend fun occasions(byId: Map<Long, ContactSummary>, today: LocalDate): List<NextUpItem.Dated> {
        val events = suspendRunCatching { contacts.events() }.getOrDefault(emptyList()).filter { it.contactId in byId }
        return events.groupBy { it.contactId }.mapNotNull { (id, list) ->
            val occasion = Occasions.today(list.map { Occasions.Dated(it.type, it.label, it.date, it.calendar) }, today, IcuCalendars) ?: return@mapNotNull null
            val ct = byId.getValue(id)
            NextUpItem.Dated(NextUpLine(NextUpKind.OCCASION, ct.lookupKey), Person(id, ct.displayName, ct.primaryNumber), occasion)
        }.sortedBy { it.occasion.ordinal }
    }

    /** Private contacts' birthdays and anniversaries today, from the dates their caller-ID copy keeps. */
    private fun privateOccasions(privates: List<VaultSummary>, today: LocalDate): List<NextUpItem.Dated> = privates.mapNotNull { v ->
        val occasion = Occasions.today(v.dates.map { Occasions.Dated(it.type, it.label, it.date, it.calendar) }, today, IcuCalendars) ?: return@mapNotNull null
        val ref = ContactRef.Private(v.id)
        NextUpItem.Dated(NextUpLine(NextUpKind.OCCASION, ref.parleyKey("")), Person(ref.navId, v.name, v.numbers.firstOrNull()), occasion)
    }

    /** A private contact as the Circle and the good hours read a contact. */
    private fun asPerson(v: VaultSummary): ContactSummary {
        val ref = ContactRef.Private(v.id)
        return ContactSummary(ref.navId, ref.parleyKey(""), v.name, null, v.starred, v.numbers.map { PhoneEntry(it, PHONE_TYPE_MOBILE, null) })
    }

    /**
     * Circle members due now whose usual hours are now (in their time when their number tells it), the longest overdue
     * first. A catch-up date set after a call keeps a member quiet until then.
     */
    private suspend fun goodHours(people: List<ContactSummary>, calls: List<CallEntry>, state: ToCallState, now: Long): List<NextUpItem> {
        val members = suspendRunCatching { circle.members() }.getOrDefault(emptyList())
        if (members.isEmpty()) return emptyList()
        val byKey = people.associateBy { it.lookupKey }
        val lasts = suspendRunCatching { circle.lastContactsAll() }.getOrDefault(emptyMap())
        val mine = ZoneId.systemDefault()
        val planned = members.mapNotNull { m ->
            val ct = byKey[m.lookupKey] ?: return@mapNotNull null
            val keys = ct.phones.map { PhoneIdentity.key(it.number, countryIso) }
            val quiet = CatchUp.quietUntil(m.rhythm.snoozedUntil, CatchUp.of(state, keys))
            Triple(ct, keys.toSet(), CirclePlanner.Member(m.lookupKey, m.days, lasts[m.lookupKey], quiet))
        }
        return CirclePlanner.sort(planned.map { it.third }, now).mapNotNull { member ->
            val (ct, keys, _) = planned.first { it.third.lookupKey == member.lookupKey }
            val numbers = PhoneIdentity.LineSet(ct.phones.map { it.number }, countryIso)
            val theirs = NumberInfo.timeZone(ct.primaryNumber, countryIso, now) ?: mine
            val window = GoodTime.window(calls.filter { it.number in numbers }, theirs, now) ?: return@mapNotNull null
            if (!NextUp.goodTimeNow(member, window, theirs, now)) return@mapNotNull null
            NextUpItem.GoodHours(NextUpLine(NextUpKind.GOOD_TIME, ct.lookupKey, keys), Person(ct.id, ct.displayName, ct.primaryNumber), window)
        }
    }

    private companion object {
        /** Birthdays turn over at midnight and good hours begin on the hour: a quarter of an hour is soon enough. */
        const val REFRESH_MS = 15 * 60_000L
        const val CALLS_DEBOUNCE_MS = 2_000L
        const val STOP_AFTER_MS = 5_000L

        /** A private contact's numbers carry no type in its listing: read as mobile, as elsewhere. */
        const val PHONE_TYPE_MOBILE = 2
    }
}
