package com.cairn.reader.ui.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/** A platform Cairn can build a feed URL for from a short handle — no server, just a URL template
 *  handed to the same discovery that powers "Add feed". */
enum class PlatformFeed(val label: String, val hint: String, val prefix: String) {
    REDDIT("Reddit", "subreddit, e.g. androiddev", "r/"),
    YOUTUBE("YouTube", "@handle or channel URL", "@"),
    SUBSTACK("Substack", "publication, e.g. platformer", ""),
    MEDIUM("Medium", "@user or publication", "@"),
    TUMBLR("Tumblr", "blog name", ""),
    WEBSITE("Website", "any site or feed URL", "");

    /** Turn the user's short input into a canonical URL discovery can resolve. */
    fun buildUrl(input: String): String? {
        val t = input.trim()
        if (t.isBlank()) return null
        return when (this) {
            REDDIT -> {
                val sub = t.substringAfterLast("/r/").removePrefix("r/").trim('/').substringBefore('/')
                if (sub.isBlank()) null else "https://www.reddit.com/r/$sub/.rss"
            }
            YOUTUBE -> when {
                t.startsWith("http") -> t
                t.startsWith("@") -> "https://www.youtube.com/$t"
                t.startsWith("UC") && t.length in 20..30 -> "https://www.youtube.com/feeds/videos.xml?channel_id=$t"
                else -> "https://www.youtube.com/@$t"
            }
            SUBSTACK -> if (t.startsWith("http")) t else "https://${t.substringBefore('.').trim('/')}.substack.com/feed"
            MEDIUM -> {
                val h = if (t.startsWith("@")) t else t.removePrefix("medium.com/").trim('/')
                if (h.isBlank()) null else "https://medium.com/feed/$h"
            }
            TUMBLR -> if (t.startsWith("http")) t else "https://${t.substringBefore('.').trim('/')}.tumblr.com/rss"
            WEBSITE -> t
        }
    }
}

data class CatalogFeed(val title: String, val url: String, val site: String)
data class CatalogCategory(val name: String, val feeds: List<CatalogFeed>)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    sourceRepository: SourceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _catalog = MutableStateFlow<List<CatalogCategory>>(emptyList())
    val catalog: StateFlow<List<CatalogCategory>> = _catalog.asStateFlow()

    /** Feed URLs already subscribed, so the catalog can show a ✓ instead of "Add". */
    val subscribed: StateFlow<Set<String>> =
        sourceRepository.sources()
            .map { list -> list.map { it.feedUrl.trimEnd('/') }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _snacks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snacks = _snacks.asSharedFlow()

    init {
        viewModelScope.launch { _catalog.value = loadCatalog() }
    }

    private suspend fun loadCatalog(): List<CatalogCategory> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.assets.open("explore_catalog.json").bufferedReader().use { it.readText() }
            val cats = JSONObject(json).getJSONArray("categories")
            (0 until cats.length()).map { i ->
                val c = cats.getJSONObject(i)
                val feeds = c.getJSONArray("feeds")
                CatalogCategory(
                    name = c.getString("name"),
                    feeds = (0 until feeds.length()).map { j ->
                        val f = feeds.getJSONObject(j)
                        CatalogFeed(f.getString("title"), f.getString("url"), f.optString("site"))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addCatalogFeed(feed: CatalogFeed) = add(feed.url, feed.title)

    fun addFromPlatform(platform: PlatformFeed, input: String) {
        val url = platform.buildUrl(input)
        if (url == null) {
            viewModelScope.launch { _snacks.emit("Enter a ${platform.label} name first") }
            return
        }
        add(url, null)
    }

    private fun add(url: String, label: String?) = viewModelScope.launch {
        _busy.value = true
        val result = feedRepository.addFeedByUrl(url)
        _busy.value = false
        _snacks.emit(
            result.fold(
                onSuccess = { label?.let { "Subscribed to $it" } ?: "Feed added" },
                onFailure = { it.message ?: "Couldn't find a feed there" },
            ),
        )
    }
}
