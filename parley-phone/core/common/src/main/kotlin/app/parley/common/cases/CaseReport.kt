package app.parley.common.cases

/**
 * The text of a case file's PDF, for a complaint: who, where it stands, the summary, the reference numbers (only when you said yes to
 * including them), the open promises and every call and note, oldest first as a complaint reads. Pure: the app turns
 * the lines into pages ([paginate]) and draws them; the words come from its resources ([Words]).
 */
object CaseReport {
    enum class Style { TITLE, SUBTITLE, HEADING, BODY, DETAIL }

    data class Line(val style: Style, val text: String)

    /** The words of the report, in the app's language. Counts and lengths are already formatted by the app. */
    interface Words {
        fun title(name: String): String
        fun madeOn(date: String): String
        fun numbers(numbers: List<String>): String
        val summary: String

        /** Where the case stands ("Status: waiting for them, since 4 Oct 2026"); [since] null when never set. */
        fun status(status: CaseStatus, since: String?): String
        fun calls(count: Int, first: String, last: String): String
        val noCalls: String
        fun hold(total: Long, average: Long, longest: Long, calls: Int): String
        fun menu(keys: String): String
        val references: String
        fun reference(label: String, value: String, date: String): String
        fun referencesLeftOut(count: Int): String
        val promises: String
        fun promise(text: String): String
        val timeline: String
        fun call(entry: CaseEntry.Call): String
        fun note(text: String): String
        fun referenceAdded(label: String): String

        /** A date and time ("4 Oct 2026, 14:05"). */
        fun date(at: Long): String
    }

    /** A reference number as the report may show it: opened only when the person chose to include them. */
    data class OpenReference(val label: String, val value: String, val at: Long)

    /**
     * The report's lines. [references] are the opened reference numbers, or null when the person didn't choose to
     * include them (then only how many were left out is said, [referenceCount]). [now] is when it is made; [status]
     * and [statusAt] say where the case stands ([CaseFile.status]).
     */
    @Suppress("LongParameterList") // One argument per part of the report.
    fun build(
        name: String,
        numbers: List<String>,
        timeline: CaseTimeline,
        references: List<OpenReference>?,
        referenceCount: Int,
        now: Long,
        w: Words,
        status: CaseStatus = CaseStatus.OPEN,
        statusAt: Long = 0,
    ): List<Line> = buildList {
        add(Line(Style.TITLE, w.title(name)))
        add(Line(Style.SUBTITLE, w.madeOn(w.date(now))))
        if (numbers.isNotEmpty()) add(Line(Style.SUBTITLE, w.numbers(numbers)))
        add(Line(Style.SUBTITLE, w.status(status, statusAt.takeIf { it > 0 }?.let(w::date))))
        addAll(summary(timeline.summary, w))
        if (referenceCount > 0) {
            add(Line(Style.HEADING, w.references))
            if (references == null) {
                add(Line(Style.DETAIL, w.referencesLeftOut(referenceCount)))
            } else {
                references.sortedBy { it.at }.forEach { add(Line(Style.BODY, w.reference(it.label, it.value, w.date(it.at)))) }
            }
        }
        if (timeline.promises.isNotEmpty()) {
            add(Line(Style.HEADING, w.promises))
            timeline.promises.forEach { add(Line(Style.BODY, w.promise(it.text))) }
        }
        addAll(entries(timeline.entries, w))
    }

    private fun summary(s: CaseSummary, w: Words): List<Line> = buildList {
        add(Line(Style.HEADING, w.summary))
        val first = s.firstCallAt
        val last = s.lastCallAt
        add(Line(Style.BODY, if (s.calls > 0 && first != null && last != null) w.calls(s.calls, w.date(first), w.date(last)) else w.noCalls))
        if (s.heldCalls > 0) add(Line(Style.BODY, w.hold(s.totalHoldSec, s.averageHoldSec, s.longestHoldSec, s.heldCalls)))
        if (s.menu.isNotEmpty()) add(Line(Style.BODY, w.menu(s.menu)))
    }

    /** Every call and note, oldest first as a complaint reads; at the same moment the call stays before its note (a stable sort). */
    private fun entries(entries: List<CaseEntry>, w: Words): List<Line> = if (entries.isEmpty()) {
        emptyList()
    } else {
        listOf(Line(Style.HEADING, w.timeline)) + entries.sortedBy { it.at }.flatMap { e ->
            val text = when (e) {
                is CaseEntry.Call -> w.call(e)
                is CaseEntry.Note -> w.note(e.text)
                is CaseEntry.Reference -> w.referenceAdded(e.label)
            }
            listOf(Line(Style.DETAIL, w.date(e.at)), Line(Style.BODY, text))
        }
    }

    /**
     * The lines of each page: [heights] are the lines' drawn heights, [usable] a page's room for them. A heading is
     * never left last on a page, nor a date apart from its entry. Always at least one page.
     */
    fun paginate(lines: List<Line>, heights: List<Float>, usable: Float): List<IntRange> {
        val pages = ArrayList<IntRange>()
        var start = 0
        var used = 0f
        var i = 0
        while (i < lines.size) {
            // A heading goes with the line after it, and a date with its entry.
            var end = i + 1
            if (lines[i].style == Style.HEADING && end < lines.size) end++
            if (lines[end - 1].style == Style.DETAIL && end < lines.size && lines[end].style == Style.BODY) end++
            val h = (i until end).sumOf { heights[it].toDouble() }.toFloat()
            if (used + h > usable && i > start) {
                pages += start until i
                start = i
                used = 0f
            }
            used += h
            i = end
        }
        pages += start until lines.size
        return pages
    }
}
