package com.wkhan.hexis.web.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.Capabilities
import com.wkhan.hexis.bridge.client.BridgeDiscovery
import com.wkhan.hexis.bridge.data.DataConsent
import com.wkhan.hexis.bridge.security.BridgeTrust
import com.wkhan.hexis.web.R
import com.wkhan.hexis.web.bridge.GrantStore
import com.wkhan.hexis.web.pairing.PairingStore
import com.wkhan.hexis.web.server.WebServerService

/**
 * The addon's only screen. It does three things, in plain views (no Compose — keeps this module tiny):
 *  1. **Connect to Hexis** — launches the core's data-consent for a scoped grant token (stored in [GrantStore]).
 *  2. **Start / stop** the local web server.
 *  3. Shows the live URL + pairing key as text and a QR, so a browser on the same network can pair.
 *
 * The pairing key travels in the URL fragment (`#k=…`), which browsers never send to the server — so even
 * the QR hands the key to the browser out-of-band, never over the wire.
 */
class ControlActivity : Activity() {

    private lateinit var grants: GrantStore
    private lateinit var pairing: PairingStore

    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    private lateinit var serverButton: Button
    private lateinit var urlView: TextView
    private lateinit var qrView: ImageView
    private lateinit var hintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grants = GrantStore(this)
        pairing = PairingStore(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- UI ------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title(getString(R.string.app_name)))
        root.addView(body(getString(R.string.control_intro)).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) })

        statusView = body("").apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(spaced(statusView, dp(20)))

        connectButton = Button(this).apply { setOnClickListener { onConnect() } }
        root.addView(spaced(connectButton, dp(12)))

        serverButton = Button(this).apply { setOnClickListener { onToggleServer() } }
        root.addView(spaced(serverButton, dp(8)))

        urlView = body("").apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(spaced(urlView, dp(20)))

        qrView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(12) }
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(qrView)

        hintView = body("").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) }
        hintView.movementMethod = LinkMovementMethod.getInstance()
        root.addView(hintView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val running = WebServerService.running
        val connected = grants.isConnected

        connectButton.text = getString(if (connected) R.string.reconnect else R.string.connect)
        serverButton.text = getString(if (running) R.string.stop_server else R.string.start_server)
        serverButton.isEnabled = connected

        statusView.text = when {
            !connected -> getString(R.string.status_not_connected)
            running -> getString(R.string.status_running)
            else -> getString(R.string.status_ready)
        }

        val url = WebServerService.currentUrl()
        if (running && url != null) {
            val full = "$url#k=${pairing.keyB64()}"
            urlView.visibility = View.VISIBLE
            urlView.text = getString(R.string.open_on_computer, url) + "\n\n" + full
            qrView.setImageBitmap(QrGen.bitmap(full, dp(220)))
            qrView.visibility = View.VISIBLE
            hintView.text = getString(R.string.hint_running)
        } else {
            urlView.visibility = View.GONE
            qrView.visibility = View.GONE
            hintView.text = if (connected) getString(R.string.hint_connected) else getString(R.string.hint_connect_first)
        }
    }

    // ---- actions -------------------------------------------------------------------------------------

    private fun onConnect() {
        val provider = BridgeDiscovery
            .providersFor(this, Capabilities.DATA, BridgeTrust.HEXIS_KEYSET)
            .firstOrNull()
        if (provider == null) {
            toast(getString(R.string.err_core_missing))
            return
        }
        val scopes = listOf(
            BridgeScopes.DATA_TASKS_READ, BridgeScopes.DATA_TASKS_WRITE,
            BridgeScopes.DATA_NOTES_READ, BridgeScopes.DATA_NOTES_WRITE,
        ).joinToString(",")
        val intent = Intent(DataConsent.ACTION).apply {
            setPackage(provider.packageName)
            putExtra(DataConsent.EXTRA_CONSUMER_PACKAGE, packageName)
            putExtra(DataConsent.EXTRA_SCOPES, scopes)
        }
        runCatching { startActivityForResult(intent, REQ_CONSENT) }
            .onFailure { toast(getString(R.string.err_core_missing)) }
    }

    @Deprecated("startActivityForResult is fine for a single in-app consent handoff")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONSENT) return
        if (resultCode == RESULT_OK && data != null) {
            val token = data.getStringExtra(DataConsent.EXTRA_TOKEN)
            if (token.isNullOrEmpty()) {
                toast(getString(R.string.err_denied))
            } else {
                grants.token = token
                grants.scopes = data.getStringExtra(DataConsent.EXTRA_GRANTED_SCOPES).orEmpty()
                toast(getString(R.string.connected_ok))
            }
        } else {
            toast(getString(R.string.err_denied))
        }
        refresh()
    }

    private fun onToggleServer() {
        if (WebServerService.running) {
            WebServerService.stop(this)
        } else {
            if (!grants.isConnected) { toast(getString(R.string.hint_connect_first)); return }
            WebServerService.start(this)
        }
        // The service flips its flag asynchronously; nudge the UI shortly after.
        serverButton.postDelayed({ refresh() }, REFRESH_DELAY_MS)
    }

    // ---- view helpers --------------------------------------------------------------------------------

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
    }

    private fun spaced(view: View, topMargin: Int): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = topMargin
        view.layoutParams = lp
        if (view is Button) view.gravity = Gravity.CENTER
        return view
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_CONSENT = 101
        const val REFRESH_DELAY_MS = 600L
        const val TITLE_SP = 24f
        const val BODY_SP = 15f
    }
}
