package app.parley.ui.contact

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import app.parley.security.launchVault
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Parley-to-Parley encrypted contact QR. The QR holds `parley://qr?d=…` (AES-GCM, key from a
 * one-time passcode the sender reads out). Any QR scanner opens it in Parley; Parley itself never
 * needs the camera.
 */
object SecureQr {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    private const val ITERATIONS = 200_000

    fun newPasscode(): String {
        val r = SecureRandom()
        return (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
    }

    fun encode(details: ContactDetails, passcode: String): String {
        val json = ContactDetailsJson.encode(details.copy(photoUri = null)).toByteArray()
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json) } }.toByteArray()
        val sealed = BackupCrypto.encryptBytes(zipped, listOf(Recipient.Passphrase(normalize(passcode))), ITERATIONS)
        return "parley://qr?v=1&d=" + Base64.encodeToString(sealed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(uri: Uri, passcode: String): ContactDetails {
        val data = Base64.decode(uri.getQueryParameter("d").orEmpty(), Base64.URL_SAFE)
        val zipped = BackupCrypto.decryptBytes(data, Unlock.Passphrase(normalize(passcode)))
        val json = GZIPInputStream(zipped.inputStream()).use { it.readBytes() }
        return ContactDetailsJson.decode(String(json))
    }

    private fun normalize(p: String) = p.uppercase().filter { it.isLetterOrDigit() }.toCharArray()

    fun qr(text: String, size: Int = 720): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun SecureQrDialog(details: ContactDetails, onDismiss: () -> Unit) {
    val passcode = remember { SecureQr.newPasscode() }
    val bitmap by produceState<Bitmap?>(null, details) {
        value = withContext(Dispatchers.Default) { SecureQr.qr(SecureQr.encode(details, passcode)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share privately") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), "Encrypted QR code", Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                Text("Passcode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(passcode, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(
                    "Scan with the other phone's camera or QR app; it opens in Parley. Tell them the passcode in person — the QR alone reveals nothing.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

/** Receiving side of [SecureQr]: ask for the passcode, then save to the vault or phone contacts. */
@Composable
fun ReceiveSecureQrDialog(vm: AppViewModel, uri: Uri, onDone: () -> Unit, openEditor: (ContactDetails) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ContactDetails?>(null) }
    val r = result
    if (r == null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Encrypted contact") },
            text = {
                Column {
                    Text("Enter the passcode the sender gave you.")
                    OutlinedTextField(
                        code, { code = it; error = null }, singleLine = true, isError = error != null,
                        supportingText = error?.let { e -> { Text(e) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        result = try {
                            withContext(Dispatchers.Default) { SecureQr.decode(uri, code) }
                        } catch (_: Exception) {
                            error = "Wrong passcode or damaged code"
                            null
                        }
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onDone) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(r.displayName.ifBlank { "Contact" }) },
            text = { Text(listOfNotNull(r.phones.firstOrNull()?.value, r.emails.firstOrNull()?.value).joinToString(" · ")) },
            confirmButton = {
                TextButton({ scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast("Couldn't save: ${e.message}") }) { val id = vm.c.vault.save(null, r); vm.toast("Saved to private contacts"); onDone(); vm.navigate(app.parley.NavEvent.Vault(id)) } }) { Text("Save privately") }
            },
            dismissButton = { TextButton({ onDone(); openEditor(r) }) { Text("Save to phone") } },
        )
    }
}
