package app.parley.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Stores a decision trace as compact JSON: `[{"c":"Contact?","r":"no","m":"PASS"}, …]`. */
object TraceCodec {
    fun encode(steps: List<TraceStep>): String = buildJsonArray {
        steps.forEach { s ->
            add(buildJsonObject {
                put("c", JsonPrimitive(s.check))
                put("r", JsonPrimitive(s.result))
                if (s.mark != TraceMark.PASS) put("m", JsonPrimitive(s.mark.name))
            })
        }
    }.toString()

    fun decode(json: String?): List<TraceStep> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                TraceStep(
                    o["c"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    o["r"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["m"]?.jsonPrimitive?.contentOrNull?.let { m -> TraceMark.entries.firstOrNull { it.name == m } } ?: TraceMark.PASS,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** "Contact? no → Allow rules: none → Rule 'Telemarketing' → Silence" (with "!" on failed-open steps). */
    fun oneLine(steps: List<TraceStep>): String = steps.joinToString(" → ") { (if (it.mark == TraceMark.FAILED_OPEN) "! " else "") + "${it.check}: ${it.result}" }
}
