package com.cairn.reader.domain.rules

import com.cairn.reader.data.db.RuleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The item field a condition tests. */
enum class RuleField(val label: String) {
    ANY("Title, body or author"),
    TITLE("Title"),
    CONTENT("Body / excerpt"),
    AUTHOR("Author"),
    URL("Link URL"),
    FEED("Feed name"),
    FOLDER("Folder");

    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: ANY }
}

/** How a condition compares its field to its value. */
enum class RuleOp(val label: String) {
    CONTAINS("contains"),
    NOT_CONTAINS("does not contain"),
    EQUALS("is exactly"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    MATCHES("matches regex");

    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: CONTAINS }
}

/** What a matching rule does to the item. Some actions carry a value (tag name / collection id). */
enum class RuleActionType(val label: String, val needsValue: Boolean) {
    MARK_READ("Mark as read", false),
    STAR("Star", false),
    READ_LATER("Add to Read Later", false),
    ADD_TAG("Add tag", true),
    ADD_TO_COLLECTION("Add to collection", true),
    ARCHIVE("Archive", false),
    TRASH("Move to Trash", false);

    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: MARK_READ }
}

data class RuleCondition(val field: RuleField, val op: RuleOp, val value: String)
data class RuleAction(val type: RuleActionType, val value: String? = null)

/** A fully-parsed rule (its JSON columns decoded). */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val matchAll: Boolean,
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>,
    val stopAfter: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
) {
    fun toEntity() = RuleEntity(
        id = id, name = name, enabled = enabled, matchAll = matchAll,
        conditionsJson = encodeConditions(conditions), actionsJson = encodeActions(actions),
        stopAfter = stopAfter, sortOrder = sortOrder, createdAt = createdAt,
    )

    companion object {
        fun new(name: String = "New rule", sortOrder: Int = 0) = Rule(
            id = UUID.randomUUID().toString(), name = name, enabled = true, matchAll = true,
            conditions = listOf(RuleCondition(RuleField.ANY, RuleOp.CONTAINS, "")),
            actions = listOf(RuleAction(RuleActionType.MARK_READ)),
            stopAfter = false, sortOrder = sortOrder, createdAt = System.currentTimeMillis(),
        )

        fun from(e: RuleEntity) = Rule(
            id = e.id, name = e.name, enabled = e.enabled, matchAll = e.matchAll,
            conditions = decodeConditions(e.conditionsJson), actions = decodeActions(e.actionsJson),
            stopAfter = e.stopAfter, sortOrder = e.sortOrder, createdAt = e.createdAt,
        )

        fun encodeConditions(cs: List<RuleCondition>): String = JSONArray().apply {
            cs.forEach { put(JSONObject().put("field", it.field.name).put("op", it.op.name).put("value", it.value)) }
        }.toString()

        fun encodeActions(acts: List<RuleAction>): String = JSONArray().apply {
            acts.forEach { put(JSONObject().put("type", it.type.name).apply { it.value?.let { v -> put("value", v) } }) }
        }.toString()

        fun decodeConditions(json: String): List<RuleCondition> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RuleCondition(RuleField.from(o.optString("field")), RuleOp.from(o.optString("op")), o.optString("value"))
            }
        }.getOrDefault(emptyList())

        fun decodeActions(json: String): List<RuleAction> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RuleAction(RuleActionType.from(o.optString("type")), o.optString("value").ifBlank { null })
            }
        }.getOrDefault(emptyList())
    }
}
