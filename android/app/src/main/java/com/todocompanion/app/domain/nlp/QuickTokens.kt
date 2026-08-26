package com.todocompanion.app.domain.nlp

/**
 * Tier V9 — inline capture tokens, inspired by timeto.me's TextFeatures: one text box where a few
 * symbols set structured fields and are stripped from the visible title.
 *
 *   "read the report #t25 !!"   → title "read the report", estimate 25 min, priority 2
 *   "gym @exercise *"           → title "gym", activity "exercise", starred
 *
 * Pure and side-effect-free; the caller decides how to apply the parsed fields.
 */
object QuickTokens {
    data class Parsed(
        val text: String,
        val estimateMin: Int? = null,
        val priorityLevel: Int? = null,   // 1..3 from ! !! !!!
        val star: Boolean = false,
        val activity: String? = null,     // from @name
    ) {
        val hasAny: Boolean get() = estimateMin != null || priorityLevel != null || star || activity != null
    }

    private val EST = Regex("(?<=^|\\s)#t(\\d{1,4})(?=\\s|$)")
    private val ACT = Regex("(?<=^|\\s)@([\\p{L}0-9_-]{1,32})(?=\\s|$)")
    private val PRIO = Regex("(?<=^|\\s)(!{1,3})(?=\\s|$)")
    private val STAR = Regex("(?<=^|\\s)\\*(?=\\s|$)")

    /**
     * @param handleActivity when true (omnibox), an `@name` token is consumed as a time-tracking
     *   activity. When false (the task funnel), `@name` is left in the text so the quick-add parser
     *   can read it as a context — keeping `@` coherent between the omnibox and the quick-add sheet.
     */
    fun parse(raw: String, handleActivity: Boolean = true): Parsed {
        var s = raw
        var est: Int? = null; var act: String? = null; var prio: Int? = null; var star = false
        EST.find(s)?.let { m -> est = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..1440 }; s = s.removeRange(m.range.first, m.range.last + 1) }
        if (handleActivity) ACT.find(s)?.let { m -> act = m.groupValues[1]; s = s.removeRange(m.range.first, m.range.last + 1) }
        PRIO.find(s)?.let { m -> prio = m.groupValues[1].length; s = s.removeRange(m.range.first, m.range.last + 1) }
        STAR.find(s)?.let { m -> star = true; s = s.removeRange(m.range.first, m.range.last + 1) }
        return Parsed(s.replace(Regex("\\s{2,}"), " ").trim(), est, prio, star, act)
    }
}
