package app.parley.ui.contact

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.ui.Bidi
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
        val json = ContactDetailsJson.encode(details.copy(photoUri = null, pinnedNote = "", context = "", messengerPrefs = "")).toByteArray()
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
        title = { Text(stringResource(R.string.sqr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.sqr_code), Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                Text(stringResource(R.string.sqr_passcode), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(Bidi.ltr(passcode), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(
                    stringResource(R.string.sqr_hint),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_done)) } },
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
    val res = androidx.compose.ui.platform.LocalResources.current
    if (r == null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.sqr_encrypted_contact)) },
            text = {
                Column {
                    Text(stringResource(R.string.sqr_enter_passcode))
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
                            error = res.getString(R.string.sqr_wrong_passcode)
                            null
                        }
                    }
                }) { Text(stringResource(R.string.msg_open)) }
            },
            dismissButton = { TextButton(onDone) { Text(stringResource(R.string.main_cancel)) } },
        )
    } else {
        // X5 handshake: where you met (a MEET entry, and optionally the note), and "Swap" shows your own card.
        var place by remember { mutableStateOf("") }
        var toNote by remember { mutableStateOf(false) }
        val swap by vm.c.extras.handshakeSwap.collectAsStateWithLifecycle()
        var showMine by remember { mutableStateOf(swap) }
        val received = remember { System.currentTimeMillis() }
        val nonce = remember { java.util.UUID.randomUUID().toString() }
        fun withMet(): Pair<ContactDetails, String> {
            val line = app.parley.ui.extras.HandshakeInbox.line(res, place, received)
            return (if (toNote) r.copy(note = app.parley.common.extras.Handshake.appendToNote(r.note, line)) else r) to line
        }
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(r.displayName.ifBlank { stringResource(R.string.sqr_contact) }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(listOfNotNull(r.phones.firstOrNull()?.value?.let(Bidi::ltr), r.emails.firstOrNull()?.value).joinToString(stringResource(R.string.main_separator)))
                    app.parley.ui.extras.HandshakeFields(vm, place, { place = it }, toNote, { toNote = it })
                    TextButton({ showMine = true }) { Text(stringResource(R.string.x_hs_show_mine)) }
                }
            },
            confirmButton = {
                TextButton({
                    val (details, _) = withMet()
                    scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast(res.getString(R.string.edit_save_failed, e.message.orEmpty())) }) {
                        val id = vm.c.vault.save(null, details); vm.toast(res.getString(R.string.sqr_saved_private)); onDone(); vm.navigate(app.parley.NavEvent.Vault(id))
                    }
                }) { Text(stringResource(R.string.sqr_save_privately)) }
            },
            dismissButton = {
                TextButton({
                    val (details, line) = withMet()
                    // Logged in the Circle timeline once the editor has saved the contact.
                    app.parley.ui.extras.HandshakeInbox.pending = app.parley.ui.extras.HandshakeInbox.Pending(line, received, nonce)
                    onDone(); openEditor(details)
                }) { Text(stringResource(R.string.sqr_save_phone)) }
            },
        )
        if (showMine) app.parley.ui.extras.MyCardQrDialog(vm) { showMine = false }
    }
}
