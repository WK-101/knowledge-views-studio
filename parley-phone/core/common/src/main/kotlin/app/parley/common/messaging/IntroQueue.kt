package app.parley.common.messaging

/**
 * M13 "Introduce myself to a list": a step-by-step queue of chats. Parley opens one chat at a time with your details
 * prefilled; you press Send in the messenger yourself. Nothing is sent automatically: this is only the bookkeeping
 * of where you are ("3 of 12"), what you opened and what you skipped.
 */
data class IntroQueue(
    val targets: List<Target>,
    val index: Int = 0,
    /** Indexes whose chat was opened. */
    val opened: Set<Int> = emptySet(),
    val skipped: Set<Int> = emptySet(),
    val stopped: Boolean = false,
) {
    /** One person: [number] in international form; [name] may be empty. */
    data class Target(val name: String, val number: String)

    val current: Target? get() = if (stopped) null else targets.getOrNull(index)
    val finished: Boolean get() = current == null

    /** "3 of 12" (1-based), or "Done" at the end. */
    val progress: String get() = if (finished) "Done" else "${index + 1} of ${targets.size}"

    /** The current chat was opened in the messenger. */
    fun markOpened(): IntroQueue = if (finished) this else copy(opened = opened + index)

    /** Back from the messenger after opening the current chat: move on to the next person. */
    fun returned(): IntroQueue = if (!finished && index in opened) copy(index = index + 1) else this

    fun next(): IntroQueue = if (finished) this else copy(index = index + 1)

    fun skip(): IntroQueue = if (finished) this else copy(index = index + 1, skipped = skipped + index)

    fun stop(): IntroQueue = copy(stopped = true)

    /** "Opened 9 chats · skipped 2" for the end screen. */
    fun summary(): String = buildList {
        add(if (opened.size == 1) "Opened 1 chat" else "Opened ${opened.size} chats")
        if (skipped.isNotEmpty()) add("skipped ${skipped.size}")
        val left = if (stopped) targets.size - index - (if (index in opened || index in skipped) 1 else 0) else 0
        if (left > 0) add("$left not reached")
    }.joinToString(" · ")
}
