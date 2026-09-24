package app.parley.common

/**
 * The screening settings as the diagnostics report shows them: switches, actions and counts only. Numbers
 * (emergency extras), the busy-reply text, ringtone URIs, label titles and the search URL are never printed.
 */
object ScreeningDiagnostics {
    fun describe(s: ScreeningSettings): String = listOf(
        "blockHidden=${s.blockHidden}${sched(s.hiddenSchedule)}",
        "blockNonContacts=${s.blockNonContacts}${sched(s.nonContactsSchedule)}",
        "neighbourSpoofing=${s.blockNeighbourSpoofing}${sched(s.neighbourSchedule)}",
        "failedVerification=${s.blockFailedVerification}${sched(s.verificationSchedule)}",
        "invalid=${s.blockInvalid}/${s.invalidAction}${sched(s.invalidSchedule)}",
        "defaultAction=${s.defaultAction}",
        "offHours=${s.offHours.enabled}/${s.offHours.allow}/${s.offHours.action}",
        "repeatCallers=${s.repeatCallers}/${s.repeatWindowMinutes}min/${s.repeatMinIntervalSeconds}s",
        "allowDialled=${s.allowDialled}/${s.dialledDays}d",
        "allowAnswered=${s.allowAnswered}/${s.answeredMinSeconds}s/${s.answeredDays}d",
        "expectingCall=${s.snoozeUntil > 0}",
        "emergencyExtras=${s.emergencyExtras.size}",
        "notify=${s.notifyBlocked}/${s.notifyReported}/${s.notifyLikelySpam}",
        "busyReply=${s.busyReply} busyReplyCustomised=${s.busyReplyText != ScreeningSettings().busyReplyText}",
        "ringLoud=${s.ringLoudFavourites}/${s.ringLoudRepeat}",
        "repeatRingtone=${if (s.repeatRingtone != null) "set" else "default"} likelySpamRingtone=${if (s.likelySpamRingtone != null) "set" else "default"}",
        "reputationSuggestions=${s.reputationSuggestions} webSearchCustomised=${s.webSearchUrl != ScreeningSettings().webSearchUrl}",
    ).joinToString(" ")

    private fun sched(x: Schedule?) = if (x == null) "" else "(scheduled)"
}
