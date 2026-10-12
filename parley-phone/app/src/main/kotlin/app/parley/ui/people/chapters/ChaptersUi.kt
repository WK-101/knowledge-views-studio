package app.parley.ui.people.chapters

import android.content.Context
import app.parley.common.people.Chapter
import app.parley.common.people.Chapters
import app.parley.data.db.TemporaryContactEntity
import app.parley.data.extras.ExtrasStore
import app.parley.data.sync.shared.SharedLabelState
import app.parley.data.sync.shared.SharedLabels
import app.parley.data.vault.VaultSummary
import app.parley.work.ChapterNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What a label's chapter section may read and do, as intents: the screens call these instead of reaching into the
 * data container. The stores are passed as getters so nothing is built before the section shows.
 */
class ChaptersUi(
    private val extras: () -> ExtrasStore,
    private val sharedLabels: () -> SharedLabels,
    private val temporaryContacts: () -> Flow<List<TemporaryContactEntity>>,
    private val privateContacts: () -> StateFlow<List<VaultSummary>>,
    private val appContext: Context,
    /** The app's scope: what a choice starts outlives the screen. */
    private val scope: CoroutineScope,
) {
    val chapters: StateFlow<Map<String, Chapter>> get() = extras().chapters
    val shared: StateFlow<List<SharedLabelState>> get() = sharedLabels().states
    val temporaries: Flow<List<TemporaryContactEntity>> get() = temporaryContacts()
    val vault: StateFlow<List<VaultSummary>> get() = privateContacts()

    /** Whether this phone owns the shared label [title] (null: not shared, or not known). */
    fun isOwner(title: String): Boolean? = sharedLabels().isOwner(title)

    /** Ends [title]'s chapter here and now: the card asks at once, so no notification follows. */
    fun endNow(title: String) {
        val t = System.currentTimeMillis()
        extras().updateChapters { m -> m[title]?.let { m + (title to it.copy(endsAt = t, askedAt = t)) } ?: m }
    }

    /** Gives [title] an end at [ends] (a new chapter starts with [people] as they are now). */
    fun setEnd(title: String, ends: Long, people: List<Chapters.Member>) {
        val at = System.currentTimeMillis()
        extras().updateChapters { m ->
            val old = m[title]
            m + (title to (if (old != null) Chapters.withEnd(old, ends, at) else Chapters.begin(people, ends, at)))
        }
    }

    /** Takes the end away (the label stays as it is). */
    fun remove(title: String) {
        extras().updateChapters { it - title }
    }

    /** "Keep": the chapter goes, the label stays, and its notification with it. */
    fun keep(title: String) {
        remove(title)
        cancelNotice()
    }

    fun cancelNotice() {
        ChapterNotices.cancel(appContext)
    }

    /** Runs [block] in the app's scope (archiving many contacts must not stop when the screen goes). */
    fun inBackground(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
