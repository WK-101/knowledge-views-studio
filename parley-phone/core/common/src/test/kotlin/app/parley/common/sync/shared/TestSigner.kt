package app.parley.common.sync.shared

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.common.spam.Ed25519

/** A member's key for tests. */
class TestSigner(val secret: ByteArray = Ed25519.newSecret()) : MemberSigner {
    override val publicKey: ByteArray = Ed25519.publicKey(secret)
    var refuse = false

    override fun sign(message: ByteArray): ByteArray? = if (refuse) null else Ed25519.sign(secret, message)

    val hex: String get() = SharedLabelFiles.keyHex(publicKey)
}

/** A contact with a name and some numbers, e-mails and a note, as the address book would give it. */
fun person(
    name: String,
    phones: List<String> = emptyList(),
    emails: List<String> = emptyList(),
    note: String? = null,
    extra: List<DataRow> = emptyList(),
): ContactRecord {
    val rows = buildList {
        add(DataRow(Mime.NAME, mapOf(Col.D1 to name, Col.D2 to name.substringBefore(' '), Col.D3 to name.substringAfter(' ', "").ifEmpty { null })))
        phones.forEach { add(DataRow(Mime.PHONE, mapOf(Col.D1 to it, Col.D2 to "2"))) }
        emails.forEach { add(DataRow(Mime.EMAIL, mapOf(Col.D1 to it, Col.D2 to "1"))) }
        note?.let { add(DataRow(Mime.NOTE, mapOf(Col.D1 to it))) }
        addAll(extra)
    }
    return ContactRecord(key = "k-$name", displayName = name, raws = listOf(RawRecord("com.google", "a@example.com", rows = rows, rawId = 7)))
}
