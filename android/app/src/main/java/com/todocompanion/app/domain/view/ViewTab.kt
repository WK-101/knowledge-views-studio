package com.todocompanion.app.domain.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A saved working state (MLO-style tab): a view plus its grouping, sort, outline/hierarchy toggles
 * and zoom, so switching tabs restores the whole configuration. Stored as JSON in settings.
 * [ref] encodes the target as "smart:KIND" / "list:id" / "filter:id" / "tag:id" / "context:id" / "folder:id".
 */
@Serializable
data class ViewTab(
    val id: String,
    val name: String,
    val ref: String,
    val group: String = GroupMode.DATE.name,
    val sort: String = SortMode.MANUAL.name,
    val outline: Boolean = false,
    val hierarchy: Boolean = false,
    val zoom: String? = null,
)

object ViewTabs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(tabs: List<ViewTab>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ViewTab.serializer()), tabs)
    fun decode(s: String?): List<ViewTab> =
        if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ViewTab.serializer()), s) }.getOrDefault(emptyList())

    /** Token for a ViewRef, matching the sidebar-pin scheme. */
    fun refOf(v: ViewRef): String = when (v) {
        is ViewRef.Smart -> "smart:${v.kind.name}"
        is ViewRef.ListView -> "list:${v.listId}"
        is ViewRef.FolderView -> "folder:${v.folderId}"
        is ViewRef.TagView -> "tag:${v.tagId}"
        is ViewRef.ContextView -> "context:${v.contextId}"
        is ViewRef.FilterView -> "filter:${v.filterId}"
    }

    /** Rebuild a ViewRef from a token, or null if malformed. */
    fun viewOf(ref: String): ViewRef? {
        val id = ref.substringAfter(':', "")
        return when (ref.substringBefore(':')) {
            "smart" -> runCatching { ViewRef.Smart(SmartKind.valueOf(id)) }.getOrNull()
            "list" -> ViewRef.ListView(id)
            "folder" -> ViewRef.FolderView(id)
            "tag" -> ViewRef.TagView(id)
            "context" -> ViewRef.ContextView(id)
            "filter" -> ViewRef.FilterView(id)
            else -> null
        }
    }
}
