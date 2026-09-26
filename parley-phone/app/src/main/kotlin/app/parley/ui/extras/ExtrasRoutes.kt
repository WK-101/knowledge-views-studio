package app.parley.ui.extras

import android.net.Uri
import android.util.Base64
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.AppViewModel
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.common.extras.SimpleConfig
import app.parley.common.extras.SimpleSetup
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Routes of the v3.2 extras (X2 trip mode, X4 simple-mode setup and import). */
object ExtrasRoutes {
    const val TRIP = "trip"
    const val SIMPLE_SETUP = "simplesetup"
    const val SIMPLE_IMPORT = "simpleimport"
}

/** X4: a setup waiting to be imported: a `parley://simple` link from a QR scanner, or a file picked in the setup. */
object SimpleInbox {
    val qr = MutableStateFlow<Uri?>(null)
    val file = MutableStateFlow<Uri?>(null)
}

/**
 * X4: a simple-mode setup travels encrypted, with the backup's crypto (AES-GCM, key from a passphrase): as a file
 * (the passphrase you choose) or as a `parley://simple?d=…` QR code (a one-time passcode read out, like the
 * encrypted contact QR). No account and no network: the other phone opens the file or scans the code.
 */
object SimpleTransfer {
    private const val QR_ITERATIONS = 200_000

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()

    private fun gunzip(b: ByteArray): String = GZIPInputStream(b.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }

    fun encryptFile(c: SimpleConfig, passphrase: CharArray): ByteArray = BackupCrypto.encryptBytes(gzip(SimpleSetup.export(c)), listOf(Recipient.Passphrase(passphrase)))

    /** Throws on a wrong passphrase or a file that isn't a setup. */
    fun decryptFile(bytes: ByteArray, passphrase: CharArray): SimpleConfig = SimpleSetup.import(gunzip(BackupCrypto.decryptBytes(bytes, Unlock.Passphrase(passphrase))))

    fun qrLink(c: SimpleConfig, passcode: String): String {
        val sealed = BackupCrypto.encryptBytes(gzip(SimpleSetup.export(c)), listOf(Recipient.Passphrase(normalize(passcode))), QR_ITERATIONS)
        return "parley://simple?v=1&d=" + Base64.encodeToString(sealed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun fromQr(uri: Uri, passcode: String): SimpleConfig {
        val data = Base64.decode(uri.getQueryParameter("d").orEmpty(), Base64.URL_SAFE)
        return SimpleSetup.import(gunzip(BackupCrypto.decryptBytes(data, Unlock.Passphrase(normalize(passcode)))))
    }

    private fun normalize(p: String) = p.uppercase().filter { it.isLetterOrDigit() }.toCharArray()
}

/** Adds the extras' screens to the navigation graph. */
fun NavGraphBuilder.extrasRoutes(vm: AppViewModel, nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (String) -> Unit = { r -> nav.navigate(r) }
    composable(ExtrasRoutes.TRIP) { TripScreen(vm, back, open) }
    composable(ExtrasRoutes.SIMPLE_SETUP) { SimpleSetupScreen(vm, back, open) }
    composable(ExtrasRoutes.SIMPLE_IMPORT) { SimpleImportScreen(vm, back, open) }
}
