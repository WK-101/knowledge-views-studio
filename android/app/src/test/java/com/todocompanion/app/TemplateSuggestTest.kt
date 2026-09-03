package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.TemplateSuggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R78 — coverage for the template-suggestion engine lifted out of AppViewModel. */
class TemplateSuggestTest {
    private var seq = 0
    private fun t(title: String, trashed: Boolean = false, isNote: Boolean = false) =
        TaskEntity(id = "t${seq++}", listId = "l", title = title, trashed = trashed, isNote = isNote,
            createdAt = seq.toLong(), updatedAt = 0)

    @Test fun suggestsTitlesThatRecurAtLeastMinCount() {
        val tasks = listOf(t("Weekly report"), t("Weekly report"), t("Weekly report"), t("one-off"))
        val out = TemplateSuggest.suggest(tasks, existingNames = emptySet(), minCount = 3)
        assertEquals(1, out.size)
        assertEquals("Weekly report", out.single().title)
        assertEquals(3, out.single().count)
    }

    @Test fun skipsTitlesThatAreAlreadyTemplates() {
        val tasks = List(3) { t("Standup") }
        assertTrue(TemplateSuggest.suggest(tasks, existingNames = setOf("standup"), minCount = 3).isEmpty())
    }

    @Test fun ignoresTrashedNotesAndBlankTitles() {
        val tasks = List(3) { t("Groceries") } + List(3) { t("Groceries", trashed = true) } +
            List(3) { t("Journal", isNote = true) } + List(3) { t("   ") }
        val out = TemplateSuggest.suggest(tasks, emptySet(), minCount = 3)
        assertEquals(listOf("Groceries"), out.map { it.title })   // only the 3 live, titled, non-note ones
    }

    @Test fun exampleIsTheNewestInEachGroupAndRankedByFrequency() {
        val tasks = List(3) { t("Rare") } + List(5) { t("Common") }
        val out = TemplateSuggest.suggest(tasks, emptySet(), minCount = 3)
        assertEquals(listOf("Common", "Rare"), out.map { it.title })   // most-frequent first
        // The example id for "Common" is its newest task (highest createdAt = last created).
        val newestCommonId = tasks.filter { it.title == "Common" }.maxByOrNull { it.createdAt }!!.id
        assertEquals(newestCommonId, out.first { it.title == "Common" }.exampleId)
    }
}
