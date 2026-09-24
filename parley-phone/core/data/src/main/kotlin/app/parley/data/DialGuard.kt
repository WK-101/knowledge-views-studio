package app.parley.data

import android.content.Context
import app.parley.common.CallPolicy
import app.parley.common.CallType
import app.parley.common.LineType
import app.parley.common.PhoneNumbers
import app.parley.common.RuleKind
import app.parley.common.blocking.WangiriGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something worth a second look before dialling (B10, B11). */
data class DialWarning(val title: String, val body: String, val severe: Boolean = false)

/**
 * Checks an outgoing number before the call is placed: premium-rate and shared-cost lines, numbers on your
 * block rules or spam lists, and one-ring "wangiri" callers. Shared by every place that dials, through the
 * common confirmation sheet (`ui.common.DialGuardSheet`). Offline and fast; never throws.
 */
class DialGuard(
    private val context: Context,
    private val blocks: BlockRepository,
    private val lists: SpamListStore,
    private val callLog: CallLogRepository,
    private val contacts: ContactsRepository,
) {
    suspend fun check(number: String): List<DialWarning> = withContext(Dispatchers.IO) {
        runCatching { checkInternal(number) }.getOrDefault(emptyList())
    }

    private suspend fun checkInternal(number: String): List<DialWarning> {
        if (number.isBlank() || PhoneNumbers.isServiceCode(number)) return emptyList()
        if (PhoneEnv.isEmergency(context, number)) return emptyList()
        val iso = PhoneEnv.countryIso(context)
        val out = ArrayList<DialWarning>()
        val facts = NumberFacts.of(number, iso)
        when (facts.lineType) {
            LineType.PREMIUM_RATE -> out += DialWarning("Premium-rate number", "Calls to this number can cost a lot per minute, on top of your plan.", severe = true)
            LineType.SHARED_COST -> out += DialWarning("Shared-cost number", "This number is charged at a special rate that may not be in your plan.")
            else -> Unit
        }
        val isContact = contacts.isContact(number) == true
        if (!isContact) {
            val rule = blocks.rules.value.firstOrNull { it.enabled && it.kind == RuleKind.BLOCK && it.type.isNumberRule && CallPolicy.ruleMatches(it, number, iso) }
            if (rule != null) out += DialWarning("Matches your block rule", "'${rule.title}' blocks calls from this number.")
            val hit = lists.lookup(number, iso).hits.maxByOrNull { it.score }
            if (hit != null) out += DialWarning("Listed as spam", "${hit.packName}" + (hit.category?.let { ": $it" } ?: "") + ". Scam lines often charge you for calling back.", severe = hit.score >= 70)
            wangiri(number, iso, facts.lineType, facts.region)?.let { out += it }
        }
        return out
    }

    private suspend fun wangiri(number: String, iso: String, type: LineType, region: String?): DialWarning? {
        val lastIncoming = callLog.calls.value.orEmpty().firstOrNull { it.type != CallType.OUTGOING && PhoneNumbers.same(it.number, number, iso) } ?: return null
        if (System.currentTimeMillis() - lastIncoming.date > 14 * 86_400_000L) return null
        val ring = blocks.ringsFor(number).firstOrNull { kotlin.math.abs(it.startedAt - lastIncoming.date) < 120_000 }
        if (!WangiriGuard.isSuspect(lastIncoming.type, ring?.ringMs, type, region, iso)) return null
        return DialWarning(
            "Don't call back?",
            "This number rang once and hung up" + (if (region != null && region != iso) " from abroad" else "") +
                ". That's a common trick to make you call an expensive line.",
            severe = true,
        )
    }

    /** Recents badge (B10): whether this missed call looks like a one-ring scam. */
    fun isWangiri(type: CallType, number: String, date: Long, rings: List<app.parley.data.db.CallRingEntity>, iso: String): Boolean {
        if (type != CallType.MISSED && type != CallType.REJECTED) return false
        val key = blocks.ringKey(number)
        val ring = rings.firstOrNull { blocks.ringMatches(it, number, key) && kotlin.math.abs(it.startedAt - date) < 120_000 }
        val f = NumberFacts.of(number, iso)
        return WangiriGuard.isSuspect(type, ring?.ringMs, f.lineType, f.region, iso)
    }
}
