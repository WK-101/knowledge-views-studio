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

    /** One file (data.data) or many (data.clipData), returned in filename order so split parts concatenate right. */
    private fun collectUris(data: Intent): List<Uri> {
        val clip = data.clipData
        val uris = if (clip != null) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        return uris.sortedBy { displayName(it) }
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

    // The unzip is one cohesive unit (open -> extract -> validate -> swap); any failure must surface as
    // an error state, never crash the addon, hence the broad catch.
    @Suppress("TooGenericExceptionCaught")
    private fun install(uris: List<Uri>) {
        val tmp = File(filesDir, "${ModelStore.DIR}.tmp")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            openConcatenated(uris).use { extract(it, tmp) }

            if (!isValidModel(tmp)) {
                tmp.deleteRecursively()
                main.post { fail(getString(R.string.import_invalid)) }
                return
            }
            val dest = File(filesDir, ModelStore.DIR)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IllegalStateException("swap failed")
            main.post { succeed() }
        } catch (t: Throwable) {
            Log.w(TAG, "model import failed", t)
            tmp.deleteRecursively()
            main.post { fail(getString(R.string.import_failed)) }
        }
    }

    /** A single stream over all parts in order — `cat part.001 part.002` reproduces the original zip. */
    private fun openConcatenated(uris: List<Uri>): InputStream {
        if (uris.isEmpty()) throw IllegalStateException("no files")
        val streams = uris.map {
            contentResolver.openInputStream(it) ?: throw IllegalStateException("cannot open $it")
        }
        return java.io.SequenceInputStream(java.util.Collections.enumeration(streams))
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
