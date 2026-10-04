package app.parley.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.parley.R
import app.parley.ui.startOrSay

object Intents {
    private fun launch(context: Context, intent: Intent) = context.startOrSay(intent, context.getString(R.string.main_no_app))

    fun sms(context: Context, number: String) = launch(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    fun email(context: Context, address: String) = launch(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))
    fun map(context: Context, address: String) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
    fun web(context: Context, url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun shareVcard(context: Context, uri: Uri, name: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(i, context.getString(R.string.main_share_contact)))
    }

    fun shareText(context: Context, text: String) {
        launch(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

}
