package app.parley.telecom

import android.content.Context
import android.telecom.Call
import app.parley.common.calls.EmergencyPolicy
import java.util.concurrent.ConcurrentHashMap

/** Which calls are emergency calls, and what [EmergencyPolicy] needs to know about one. */
internal class EmergencyCalls(private val deps: () -> TelecomDependencies, private val context: () -> Context?) {
    /** Platform answers per number, while calls exist (the list depends on the SIM and network, so not for longer). */
    private val numbers = ConcurrentHashMap<String, Boolean>()

    fun isNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return numbers.getOrPut(number) {
            runCatching { deps().isEmergencyNumber(number) }.getOrElse { EmergencyPolicy.isFallbackEmergencyNumber(number) }
        }
    }

    /** An emergency number, or a call the network identified as one, or one taking place in emergency callback mode. */
    fun isCall(call: Call, number: String?): Boolean = isNumber(number) || hasEmergencyProperty(call)

    private fun hasEmergencyProperty(call: Call): Boolean = runCatching {
        call.details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) ||
            call.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)
    }.getOrDefault(false)

    /** What [EmergencyPolicy] needs about [call]. */
    fun facts(call: Call, number: String?, incoming: Boolean): EmergencyPolicy.Facts = EmergencyPolicy.Facts(
        emergencyNumber = isNumber(number),
        emergencyCallProperty = hasEmergencyProperty(call),
        inWindow = context()?.let { c -> runCatching { ScreeningGuard.inEmergencyWindow(c) }.getOrDefault(false) } ?: false,
        userListed = !incoming && !number.isNullOrBlank() && runCatching { deps().startsEmergencyWindow(number) }.getOrDefault(false),
    )

    /** No call is left: the next one asks the platform again. */
    fun clear() {
        numbers.clear()
    }
}
