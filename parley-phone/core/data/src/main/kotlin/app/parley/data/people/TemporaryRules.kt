package app.parley.data.people

import app.parley.common.cases.CaseFiles
import app.parley.common.catching
import app.parley.common.people.TemporaryClock
import app.parley.common.people.TemporaryRule
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import org.json.JSONObject

/**
 * Temporary contacts that count from the last call or wait for their case file ([TemporaryRule]): the daily upkeep
 * moves their dates on here, for visible and private ones alike, before it looks for what is due. Nothing is
 * deleted here, and a date only ever moves later.
 */
object TemporaryRules {
    /** Backup keys, beside the date. Older versions ignore them and keep the date as a fixed one. */
    private const val K_SLIDE = "slide"
    private const val K_CASE = "case"

    fun put(o: JSONObject, rule: TemporaryRule): JSONObject = o.apply {
        rule.slideDays?.let { put(K_SLIDE, it) }
        if (rule.untilCase) put(K_CASE, true)
    }

    /** Read leniently: a backup from before these existed, or a value that makes no sense, is a fixed date. */
    fun read(o: JSONObject): TemporaryRule = TemporaryRule.stored(o.optInt(K_SLIDE, 0).takeIf { it > 0 }, o.optBoolean(K_CASE, false))

    /** Moves the dates on at [now] ([TemporaryRule.follow]). */
    suspend fun follow(c: DataContainer, now: Long = System.currentTimeMillis()) {
        val region = PhoneEnv.countryIso(c.appContext)
        val cases = catching { c.cases.load() }.getOrNull()?.takeIf { c.cases.available }
        val caseOf: suspend (List<String>) -> TemporaryRule.CaseNow = { numbers ->
            if (cases == null) TemporaryRule.CaseNow.UNKNOWN else TemporaryRule.caseNow(CaseFiles.find(cases, numbers, region))
        }
        catching {
            c.temporaries.followRules(now, lastContact = { numbers -> lastContact(c, numbers, now) }, caseOf = caseOf)
        }
        // Private ones: their calls are Parley's own, kept by entry; chats with them are never recorded.
        val private = catching { c.vault.summariesNow() }.getOrNull().orEmpty()
            .filter { it.expiresAt != null && it.rule.clock != TemporaryClock.FIXED }
        for (v in private) {
            val stored = v.expiresAt ?: continue
            val last = if (v.rule.clock == TemporaryClock.LAST_CALL) lastPrivateCall(c, v.id) else null
            val at = TemporaryRule.follow(stored, v.rule, last, caseOf(v.numbers), now)
            if (at != stored) catching { c.vault.setExpiry(v.id, at) }
        }
    }

    /** The newest connected call with any of [numbers] in the phone's call log, or chat started from Parley. */
    private suspend fun lastContact(c: DataContainer, numbers: List<String>, now: Long): Long? = numbers.mapNotNull { n ->
        val call = catching { c.callLog.pastCalls(n, now + 1, limit = RECENT_CALLS) }.getOrNull().orEmpty()
            .firstOrNull { it.durationSec > 0 }?.date
        val chat = catching { c.messaging.lastMessagedAt(n) }.getOrNull()
        listOfNotNull(call, chat).maxOrNull()
    }.maxOrNull()

    /** Entry [id]'s newest connected call (a missed or zero-length one doesn't count), or null. */
    private suspend fun lastPrivateCall(c: DataContainer, id: Long): Long? =
        catching { c.vault.privateCallsOf(id) }.getOrNull().orEmpty().filter { it.durationSec > 0 }.maxOfOrNull { it.date }

    /** Enough of the newest calls to find a connected one among missed attempts. */
    private const val RECENT_CALLS = 20
}
