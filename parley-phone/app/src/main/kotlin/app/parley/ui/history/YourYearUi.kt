package app.parley.ui.history

import app.parley.common.catching
import app.parley.common.circle.PeopleInsights
import app.parley.common.circle.YourYear
import app.parley.common.people.ContactRef
import app.parley.common.security.PrivacyView
import app.parley.data.circle.CircleRepository
import app.parley.data.history.CallHistory
import app.parley.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * "Your year" (`vm.yourYear`): the years with history, and one year's look back, read when the page asks and one year
 * at a time, so many years of calls are never worked through at once. [contacts] are the lookup keys of the people
 * Parley's own lists show now (`vm.everyone`): private contacts are among them only while they may show, and their
 * calls are read then too. Nothing is kept. A duress unlock hides relationship insights, so the page has nothing then.
 */
class YourYearUi(private val circle: CircleRepository, private val history: CallHistory, private val vault: VaultRepository) {
    /** The years with history (newest first), and when Parley's call history starts (null: no calls). */
    data class Years(val years: List<Int>, val historyFrom: Long?)

    /** One year's page: the look back, and when history starts inside that year ("Parley has calls from March 2025"). */
    data class Page(val review: YourYear.Review, val thinFrom: Long?, val circle: Set<String>)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun years(contacts: Set<String>, privacy: PrivacyView): Years = withContext(Dispatchers.Default) {
        if (!privacy.insightsShown) return@withContext Years(emptyList(), null)
        val idx = history.awaitIndex()
        val calls = idx?.calls().orEmpty()
        val contactCalls = calls.asSequence().mapNotNull { c ->
            c.date.takeIf { c.personKey.startsWith(CONTACT) && c.personKey.removePrefix(CONTACT) in contacts }
        }
        val logged = catching { circle.interactions.touchesSince(Long.MIN_VALUE) }.getOrDefault(emptyList())
            .asSequence().filter { it.lookupKey in contacts }.map { it.time }
        val private = privateCalls(contacts, privacy).map { it.time }
        // Newest first: the oldest is the last.
        val from = listOfNotNull(calls.lastOrNull()?.date, private.minOrNull()).minOrNull()
        Years(YourYear.years(contactCalls + logged + private, zone), from)
    }

    /** [year]'s page, or null when nothing happened that year (or a duress unlock hides insights). */
    suspend fun page(year: Int, contacts: Set<String>, privacy: PrivacyView, historyFrom: Long?): Page? = withContext(Dispatchers.Default) {
        if (!privacy.insightsShown) return@withContext null
        // This year and the one before (closer and quieter compare them), nothing more.
        val before = YourYear.bounds(year - 1, zone).first
        val until = YourYear.bounds(year, zone).last + 1
        val idx = history.awaitIndex()
        val touches = catching { circle.touches(before, idx, until) }.getOrDefault(emptyList()).filter { it.key in contacts } +
            privateCalls(contacts, privacy).filter { it.time in before until until }
        val members = catching { circle.members() }.getOrDefault(emptyList()).map { it.lookupKey }.filter { it in contacts }.toSet()
        val review = YourYear.review(touches, members, year, zone, historyFrom) ?: return@withContext null
        Page(review, YourYear.thinFrom(historyFrom, year, zone), members)
    }

    /** Adds someone quieter this year to the Circle, with Natural rhythm from how often you talked the year before. */
    suspend fun addToCircle(lookupKey: String, contactId: Long, change: YourYear.Change) {
        circle.setRhythm(lookupKey, contactId.takeIf { it > 0 }, YourYear.suggestedDays(change), natural = true)
    }

    /** Calls with private contacts, as touches, only while they may show (their calls aren't in the call log). */
    private fun privateCalls(contacts: Set<String>, privacy: PrivacyView): List<PeopleInsights.Touch> {
        if (!privacy.privateShown) return emptyList()
        return vault.privateCalls.value.mapNotNull { p ->
            val key = ContactRef.privateKey(p.vaultId).takeIf { it in contacts } ?: return@mapNotNull null
            PeopleInsights.touchOf(key, CallHistory.privateEntry(p))
        }
    }

    private companion object {
        const val CONTACT = "c:"
    }
}
