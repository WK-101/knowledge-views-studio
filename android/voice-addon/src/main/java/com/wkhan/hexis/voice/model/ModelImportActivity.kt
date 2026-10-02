package com.wkhan.hexis.voice.model

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import com.wkhan.hexis.voice.R
import com.wkhan.hexis.voice.engine.ModelStore

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The model-import surface (no launcher). The core launches it (ACTION = [ACTION_IMPORT_MODEL]); the
 * user picks a model `.zip` via SAF and the addon copies it into its own private storage. No network
 * is involved — the addon never downloads anything, which is how it keeps zero network permissions.
 *
 * Settings for the feature still live in the core; this is only the file-picker + unzip plumbing the
 * core cannot do on the addon's behalf (the files must land in the addon's filesDir).
 */
class ModelImportActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        setTitle(R.string.import_title)
        if (savedInstanceState == null) launchPicker()
    }

    private fun buildUi(): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply { text = getString(R.string.import_intro) }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        action = Button(this).apply {
            text = getString(R.string.import_choose)
            setOnClickListener { launchPicker() }
        }
        root.addView(status)
        root.addView(progress)
        root.addView(action)
        return root
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
            // Allow picking a model split into parts (e.g. .zip.001 / .002) so a large model can be
            // transferred to the phone and imported without any network. The parts are concatenated
            // in filename order before unzipping; a single .zip still works.
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK) }
            .onFailure { fail(getString(R.string.import_failed)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        if (resultCode != RESULT_OK || data == null) {
            // User backed out of the picker; leave the intro visible so they can retry or leave.
            return
        }
        val uris = collectUris(data)
        if (uris.isEmpty()) return
        beginInstall(uris)
    }

    /** One file (data.data) or many (data.clipData). Ordering is decided later by [orderParts]. */
    private fun collectUris(data: Intent): List<Uri> {
        val clip = data.clipData
        return if (clip != null) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private fun beginInstall(uris: List<Uri>) {
        status.text = getString(R.string.import_working)
        progress.visibility = View.VISIBLE
        action.visibility = View.GONE
        Thread({ install(uris) }, "model-import").start()
    }

    // The unzip is one cohesive unit (order -> open -> extract -> validate -> swap); any failure must
    // surface as an error state (with the reason, so a stuck import can be diagnosed), never crash the
    // addon, hence the broad catch.
    @Suppress("TooGenericExceptionCaught")
    private fun install(uris: List<Uri>) {
        val tmp = File(filesDir, "${ModelStore.DIR}.tmp")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            val ordered = orderParts(uris)
            openConcatenated(ordered).use { extract(it, tmp) }

            if (!isValidModel(tmp)) {
                val found = tmp.listFiles()?.joinToString(", ") { it.name }.orEmpty().ifBlank { "nothing" }
                tmp.deleteRecursively()
                main.post { fail("${getString(R.string.import_invalid)}\nGot: $found") }
                return
            }
            swapIntoPlace(tmp)
            main.post { succeed() }
        } catch (t: Throwable) {
            Log.w(TAG, "model import failed", t)
            tmp.deleteRecursively()
            val detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".trim()
            main.post { fail("${getString(R.string.import_failed)}\n$detail") }
        }
    }

    /**
     * Put the part that carries the ZIP header (magic `PK\x03\x04`) first; the rest follow by name.
     * A raw split only puts that header on the first part, so this reconstructs the right order even
     * when a download renamed the parts or the picker returned them out of order — the common reason a
     * two-part import fails. Falls back to name order if no part shows the magic (e.g. a single .zip).
     */
    private fun orderParts(uris: List<Uri>): List<Uri> {
        if (uris.size <= 1) return uris
        val head = uris.firstOrNull { startsWithZipMagic(it) }
        return if (head != null) {
            listOf(head) + uris.filter { it != head }.sortedBy { displayName(it) }
        } else {
            uris.sortedBy { displayName(it) }
        }
    }

    private fun startsWithZipMagic(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = s.read(b, off, 4 - off)
                if (n < 0) break
                off += n
            }
            off == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
        } ?: false
    }.getOrDefault(false)

    /** A single stream over all parts in order — `cat part1 part2` reproduces the original zip. */
    private fun openConcatenated(uris: List<Uri>): InputStream {
        if (uris.isEmpty()) throw IllegalStateException("no files selected")
        val streams = uris.map {
            contentResolver.openInputStream(it) ?: throw IllegalStateException("cannot open $it")
        }
        return java.io.SequenceInputStream(java.util.Collections.enumeration(streams))
    }

    /** rename is instant when it works; some devices refuse to rename a dir, so fall back to a copy. */
    private fun swapIntoPlace(tmp: File) {
        val dest = File(filesDir, ModelStore.DIR)
        dest.deleteRecursively()
        if (tmp.renameTo(dest)) return
        dest.mkdirs()
        tmp.listFiles()?.forEach { f -> f.copyTo(File(dest, f.name), overwrite = true) }
        tmp.deleteRecursively()
    }

    /** Flatten each entry to its basename: trivial, and it closes zip-slip (no path separators survive). */
    private fun extract(input: InputStream, target: File) {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.isNotBlank()) {
                    File(target, name).outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun isValidModel(dir: File): Boolean {
        val files = dir.listFiles()?.map { it.name } ?: return false
        fun onnx(prefix: String) = files.any { it.startsWith(prefix) && it.endsWith(".onnx") }
        return onnx("encoder") && onnx("decoder") && onnx("joiner") && files.contains("tokens.txt")
    }

    private fun succeed() {
        progress.visibility = View.GONE
        status.text = getString(R.string.import_done)
        action.apply {
            visibility = View.VISIBLE
            text = getString(R.string.import_finish)
            setOnClickListener { setResult(RESULT_OK); finish() }
        }
        setResult(RESULT_OK)
    }

    private fun fail(message: String) {
        progress.visibility = View.GONE
        status.text = message
        action.apply {
            visibility = View.VISIBLE
            text = getString(R.string.import_retry)
            setOnClickListener { launchPicker() }
        }
        setResult(RESULT_CANCELED)
    }

    companion object {
        const val ACTION_IMPORT_MODEL = "com.wkhan.hexis.voice.IMPORT_MODEL"
        private const val REQUEST_PICK = 1
        private const val TAG = "ModelImport"
    }
}
