package app.parley.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import app.parley.CallGate
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.ReasonFacts
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I12 "Call with a reason…": what the phone can do with a reason for one call. Whether a SIM's network carries call
 * subjects is its phone account's `CAPABILITY_CALL_SUBJECT` (with its own length limit); "Text first" needs an app that
 * takes `smsto:` (declared in the manifest's queries). The decision itself is [app.parley.common.calls.CallReason]'s.
 */
// Reading phone accounts is covered by the default-dialer role; each call handles SecurityException.
@SuppressLint("MissingPermission")
object CallReasons {
    /** The facts for calling [number] on [simId] (or the SIM it would use). Off the main thread. */
    suspend fun facts(context: Context, c: DataContainer, number: String, simId: String?): Facts = withContext(Dispatchers.IO) {
        val emergency = CallGate(c).isEmergency(MenuMemory.dialled(number))
        val chosen = simId ?: c.placer.resolveSim(number) ?: runCatching { c.sims.defaultOutgoing() }.getOrNull()
        val accounts = subjectAccounts(context)
        Facts(
            ReasonFacts(emergency, chosen, accounts.mapValues { it.value != null }, canText(context, number)),
            // The shortest limit of the SIMs it may go on, so the reason fits whichever is picked.
            maxLength = (if (chosen != null && chosen in accounts) listOf(accounts[chosen]) else accounts.values.toList())
                .mapNotNull { it?.takeIf { n -> n > 0 } }.minOrNull(),
        )
    }

    data class Facts(val reason: ReasonFacts, val maxLength: Int?)

    /** Each call-capable account → null without call subjects, else its length limit (0: none given). */
    private fun subjectAccounts(context: Context): Map<String, Int?> = try {
        val tm = context.getSystemService(TelecomManager::class.java)
        tm.callCapablePhoneAccounts.associate { h ->
            val a = tm.getPhoneAccount(h)
            val supports = a?.hasCapabilities(PhoneAccount.CAPABILITY_CALL_SUBJECT) == true
            h.id to if (supports) a.extras?.getInt(PhoneAccount.EXTRA_CALL_SUBJECT_MAX_LENGTH, 0) ?: 0 else null
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun smsIntent(number: String, text: String?): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", MenuMemory.dialled(number), null)).apply { if (text != null) putExtra("sms_body", text) }

    fun canText(context: Context, number: String): Boolean =
        runCatching { smsIntent(number, null).resolveActivity(context.packageManager) != null }.getOrDefault(false)

    /** Opens the messaging app with [text] for [number]; the user sends it. False when no app took it. */
    fun textFirst(context: Context, number: String, text: String): Boolean =
        runCatching { context.startActivity(smsIntent(number, text)) }.isSuccess
}
