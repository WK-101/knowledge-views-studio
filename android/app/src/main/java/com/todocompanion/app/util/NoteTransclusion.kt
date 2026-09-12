package com.todocompanion.app.util

/**
 * Wave P (moat · N1) — the dynamic note. A note can embed LIVE data from the rest of the app with
 * `{{scope:arg}}` tokens, recomputed every time the note is opened — a briefing that writes itself
 * from your tasks and other notes. A notes-only app has no such data to embed; only a whole-life app
 * can. This is also M3's "daily cockpit": a day note that leads with `{{today:agenda}}`.
 *
 * Pure: [expand] takes the note body and a provider `(scope, arg) -> markdown?`; the ViewModel supplies
 * the provider from live repository data. An unknown/failed token is left verbatim, so a stray `{{…}}`
 * never vanishes. Date/time tokens ({{date}}/{{time}}) belong to [com.todocompanion.app.domain.NoteTokens]
 * and are intentionally NOT matched here.
 */
object NoteTransclusion {
    /** Scopes backed by live data. `note` also accepts `Title#Heading` for block-level transclusion (Wave T).
     *  Every literal brace is escaped (`\{` / `\}`): Android's ICU regex engine rejects a bare `}` outside a
     *  quantifier as a syntax error (the JVM's engine is lenient and accepts it), so an unescaped trailing
     *  `}}` compiled fine in unit tests but threw ExceptionInInitializerError on-device — crashing the moment
     *  a note editor's reading view touched this class. */
    private val TOKEN = Regex("\\{\\{(today|tasks|note|events|habits)(?::([^}]*))?\\}\\}")

    data class Token(val scope: String, val arg: String)

    fun hasTokens(body: String): Boolean = TOKEN.containsMatchIn(body)

    fun tokens(body: String): List<Token> =
        TOKEN.findAll(body).map { Token(it.groupValues[1], it.groupValues[2].trim()) }.toList()

    fun expand(body: String, provider: (scope: String, arg: String) -> String?): String =
        TOKEN.replace(body) { m -> provider(m.groupValues[1], m.groupValues[2].trim()) ?: m.value }
}
