package app.parley.ui.contact

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.data.ContactDetails
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Generates the QR code on-device; the user picks which fields are shared. */
@Composable
fun QrDialog(details: ContactDetails, onDismiss: () -> Unit) {
    val fields = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            details.phones.forEach { add("TEL" to it.value) }
            details.emails.forEach { add("EMAIL" to it.value) }
            if (details.company.isNotBlank()) add("ORG" to details.company)
        }
    }
    val selected = remember { mutableStateListOf<Int>().apply { addAll(fields.indices.filter { fields[it].first == "TEL" }.take(1)) } }
    val vcard = buildString {
        append("BEGIN:VCARD\nVERSION:3.0\n")
        append("N:${esc(details.family)};${esc(details.given)};${esc(details.middle)};${esc(details.prefix)};${esc(details.suffix)}\n")
        append("FN:${esc(details.displayName)}\n")
        selected.sorted().forEach { i -> fields.getOrNull(i)?.let { (k, v) -> append("$k:${esc(v)}\n") } }
        append("END:VCARD")
    }
    val bitmap = remember(vcard) { encode(vcard, 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_share_title, details.displayName)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.qr_code), Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                Text(stringResource(R.string.qr_scan_hint), modifier = Modifier.padding(vertical = 8.dp))
                fields.forEachIndexed { i, (_, v) ->
                    Row(Modifier.fillMaxWidth().clickable { if (i in selected) selected.remove(i) else selected.add(i) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i in selected, { if (it) selected.add(i) else selected.remove(i) })
                        Text(v)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_done)) } },
    )
}

private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

private fun encode(text: String, size: Int): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
