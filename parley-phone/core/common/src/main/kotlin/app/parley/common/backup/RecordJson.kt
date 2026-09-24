package app.parley.common.backup

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.RawRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Canonical single-line JSON for [ContactRecord]. The same record always yields the same bytes
 * (fixed field order, value maps sorted by column), so the output can be content-addressed.
 *
 * Photo blobs (DATA15) are never inline: they are replaced by `"blobSha256"` and stored separately
 * (in `photos/<sha256>.bin` in an archive, or as a blob in a snapshot store).
 */
object RecordJson {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = true }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            c[2 * i] = HEX[v ushr 4]; c[2 * i + 1] = HEX[v and 15]
        }
        return String(c)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Encodes [record]; [onBlob] receives each photo blob with its SHA-256 (to store it once). */
    fun encode(record: ContactRecord, onBlob: (hash: String, bytes: ByteArray) -> Unit = { _, _ -> }): String =
        json.encodeToString(JsonElement.serializer(), toJson(record, onBlob))

    fun toJson(record: ContactRecord, onBlob: (String, ByteArray) -> Unit): JsonObject = buildJsonObject {
        put("key", record.key)
        put("displayName", record.displayName)
        put("starred", record.starred)
        put("customRingtone", record.customRingtone)
        put("sendToVoicemail", record.sendToVoicemail)
        put("raws", buildJsonArray {
            record.raws.forEach { raw ->
                add(buildJsonObject {
                    put("accountType", raw.accountType)
                    put("accountName", raw.accountName)
                    put("dataSet", raw.dataSet)
                    put("sourceId", raw.sourceId)
                    put("rows", buildJsonArray { raw.rows.forEach { add(rowJson(it, onBlob)) } })
                })
            }
        })
    }

    private fun rowJson(row: DataRow, onBlob: (String, ByteArray) -> Unit): JsonObject = buildJsonObject {
        put("mimeType", row.mimeType)
        put("values", JsonObject(row.values.toSortedMap().mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull }))
        val blob = row.blob
        if (blob != null) {
            val h = sha256Hex(blob)
            onBlob(h, blob)
            put("blobSha256", h)
        } else {
            put("blobSha256", JsonNull)
        }
        put("isPrimary", row.isPrimary)
        put("isSuperPrimary", row.isSuperPrimary)
    }

    /**
     * Decodes a line produced by [encode]. [blob] resolves a photo hash to its bytes; a missing blob
     * is an integrity error.
     */
    fun decode(line: String, blob: (hash: String) -> ByteArray?): ContactRecord {
        val o = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            throw BackupIntegrityException("Malformed contact record", e)
        }
        return try {
            fromJson(o, blob)
        } catch (e: BackupIntegrityException) {
            throw e
        } catch (e: RuntimeException) {
            throw BackupIntegrityException("Malformed contact record", e)
        }
    }

    private fun fromJson(o: JsonObject, blob: (String) -> ByteArray?): ContactRecord = ContactRecord(
        key = o.str("key") ?: throw BackupIntegrityException("Contact without key"),
        displayName = o.str("displayName").orEmpty(),
        starred = o.bool("starred"),
        customRingtone = o.str("customRingtone"),
        sendToVoicemail = o.bool("sendToVoicemail"),
        raws = (o["raws"] as? JsonArray).orEmpty().map { r ->
            val ro = r.jsonObject
            RawRecord(
                accountType = ro.str("accountType"),
                accountName = ro.str("accountName"),
                dataSet = ro.str("dataSet"),
                sourceId = ro.str("sourceId"),
                rows = (ro["rows"] as? JsonArray).orEmpty().map { rowFrom(it.jsonObject, blob) },
            )
        },
    )

    private fun rowFrom(o: JsonObject, blob: (String) -> ByteArray?): DataRow {
        val h = o.str("blobSha256")
        val bytes = h?.let { hash ->
            val b = blob(hash) ?: throw BackupIntegrityException("Missing photo $hash")
            if (sha256Hex(b) != hash) throw BackupIntegrityException("Photo $hash does not match its hash")
            b
        }
        val values = (o["values"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
        return DataRow(
            mimeType = o.str("mimeType") ?: throw BackupIntegrityException("Data row without mimetype"),
            values = values,
            blob = bytes,
            isPrimary = o.bool("isPrimary"),
            isSuperPrimary = o.bool("isSuperPrimary"),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.boolean ?: false
}
