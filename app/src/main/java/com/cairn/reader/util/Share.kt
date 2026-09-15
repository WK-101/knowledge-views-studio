package com.cairn.reader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The system share sheet, in one place. Replaces the ~9 near-identical `Intent(ACTION_SEND)` +
 * `createChooser` blocks that had accreted across the reader, settings, notebook and triage screens.
 */

/** Share plain (or [mime]-typed) text — an article link, an export payload, a highlight. */
fun shareText(
    context: Context,
    text: String,
    subject: String? = null,
    mime: String = "text/plain",
    title: String? = null,
    chooser: String? = "Share",
) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        title?.let { putExtra(Intent.EXTRA_TITLE, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
}

/** Share a content [uri] (image, audio, video, zip…) with the given [mime] type. */
fun shareStream(
    context: Context,
    uri: Uri,
    mime: String,
    subject: String? = null,
    chooser: String? = "Share",
) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
}

/** Share a [file] via the app's FileProvider authority (`<package>.fileprovider`). */
fun shareFile(
    context: Context,
    file: File,
    mime: String,
    subject: String? = null,
    chooser: String? = "Share",
) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull() ?: return
    shareStream(context, uri, mime, subject, chooser)
}
