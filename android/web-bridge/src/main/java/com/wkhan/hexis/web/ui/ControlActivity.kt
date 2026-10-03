package com.wkhan.hexis.web.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.wkhan.hexis.web.pairing.ClientPresence
import com.wkhan.hexis.web.pairing.ClientStore
import com.wkhan.hexis.web.pairing.WebClient
import com.wkhan.hexis.web.server.WebServerService

/**
 * The addon's only screen (plain views, no Compose — keeps the module tiny):
 *  1. **Connect to Hexis** — the core's data-consent mints a scoped grant token ([GrantStore]).
 *  2. **Start / stop** the local web server.
 *  3. **Devices** — each paired browser has its own key, so you can add a device, hand it a QR, and revoke
 *     it alone; a read-only *share link* is just a client flagged read-only with an expiry.
 *
 * A client's key travels only in the pairing URL fragment (`#k=…`), which browsers never send to the
 * server, so the QR hands the key over out-of-band. Revoking a client (or a share link expiring) locks that
 * browser out at once, because the server only accepts a key that still matches a live client.
 */
class ControlActivity : Activity() {

    private lateinit var grants: GrantStore
    private lateinit var clients: ClientStore

    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    private lateinit var serverButton: Button
    private lateinit var clientsContainer: LinearLayout
    private lateinit var addButton: Button
    private lateinit var shareButton: Button
    private lateinit var hintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grants = GrantStore(this)
        clients = ClientStore(this)
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
        root.addView(spaced(body(getString(R.string.control_intro)), dp(4)))

        statusView = body("").apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(spaced(statusView, dp(20)))

        connectButton = Button(this).apply { setOnClickListener { onConnect() } }
        root.addView(spaced(connectButton, dp(12)))

        serverButton = Button(this).apply { setOnClickListener { onToggleServer() } }
        root.addView(spaced(serverButton, dp(8)))

        root.addView(spaced(sectionHeader("Devices"), dp(24)))
        clientsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(spaced(clientsContainer, dp(8)))

        addButton = Button(this).apply {
            text = "Add device…"
            setOnClickListener { onAddDevice() }
        }
        root.addView(spaced(addButton, dp(12)))

        shareButton = Button(this).apply {
            text = "Create read-only share link (24h)"
            setOnClickListener { onShareLink() }
        }
        root.addView(spaced(shareButton, dp(8)))

        hintView = body("").apply { setTextColor(mutedColor()) }
        root.addView(spaced(hintView, dp(16)))

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val running = WebServerService.running
        val connected = grants.isConnected

        connectButton.text = getString(if (connected) R.string.reconnect else R.string.connect)
        serverButton.text = getString(if (running) R.string.stop_server else R.string.start_server)
        serverButton.isEnabled = connected
        addButton.isEnabled = connected
        shareButton.isEnabled = connected

        statusView.text = when {
            !connected -> getString(R.string.status_not_connected)
            running -> getString(R.string.status_running)
            else -> getString(R.string.status_ready)
        }

        hintView.text = when {
            !connected -> getString(R.string.hint_connect_first)
            !running -> getString(R.string.hint_connected)
            else -> getString(R.string.hint_running)
        }

        rebuildClients(running)
    }

    private fun rebuildClients(running: Boolean) {
        clientsContainer.removeAllViews()
        val list = clients.active()
        if (list.isEmpty()) {
            clientsContainer.addView(body("No devices yet. Add one to pair a browser.").apply { setTextColor(mutedColor()) })
            return
        }
        for (client in list) clientsContainer.addView(clientRow(client, running))
    }

    private fun clientRow(client: WebClient, running: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = client.name
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        })
        info.addView(TextView(this).apply {
            text = subtitleFor(client)
            setTextColor(mutedColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP)
        })
        row.addView(info)

        row.addView(Button(this).apply {
            text = "Show"
            setOnClickListener { showClient(client, running) }
        })
        row.addView(Button(this).apply {
            text = "Revoke"
            setOnClickListener { onRevoke(client) }
        })
        return row
    }

    private fun subtitleFor(client: WebClient): String {
        val parts = mutableListOf<String>()
        if (client.readOnly) parts += "read-only"
        if (client.expiresAt > 0) {
            val hrs = ((client.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)) / 3_600_000L
            parts += "expires in ${hrs}h"
        }
        if (System.currentTimeMillis() - ClientPresence.lastSeen(client.id) < ACTIVE_WINDOW_MS) parts += "• active"
        return parts.joinToString(" · ").ifEmpty { "full access" }
    }

    // ---- actions -------------------------------------------------------------------------------------

    private fun onAddDevice() {
        val input = EditText(this).apply { hint = "Device name (e.g. Laptop)" }
        AlertDialog.Builder(this)
            .setTitle("Add device")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val client = clients.add(name = input.text.toString().trim().ifEmpty { "Device" })
                refresh()
                showClient(client, WebServerService.running)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onShareLink() {
        val client = clients.add(name = "Share link", readOnly = true, ttlMillis = SHARE_TTL_MS)
        refresh()
        showClient(client, WebServerService.running)
    }

    private fun onRevoke(client: WebClient) {
        AlertDialog.Builder(this)
            .setTitle("Revoke “${client.name}”?")
            .setMessage("That browser will lose access immediately.")
            .setPositiveButton("Revoke") { _, _ -> clients.remove(client.id); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClient(client: WebClient, running: Boolean) {
        val url = WebServerService.currentUrl()
        if (!running || url == null) {
            toast("Start the server first, then open this device again.")
            return
        }
        val full = "$url#k=${client.keyB64}"
        val pad = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        content.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(Color.WHITE)
            setImageBitmap(QrGen.bitmap(full, dp(240)))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.open_on_computer, url) + "\n\n" + full
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        })
        AlertDialog.Builder(this)
            .setTitle(client.name)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .show()
    }

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
            BridgeScopes.DATA_CALENDAR_READ, // calendar / time / habits are read-only companions (W3)
            BridgeScopes.DATA_TIME_READ,
            BridgeScopes.DATA_HABITS_READ,
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
        serverButton.postDelayed({ refresh() }, REFRESH_DELAY_MS)
    }

    // ---- view helpers --------------------------------------------------------------------------------

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP)
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

    private fun mutedColor(): Int = Color.parseColor("#8a949d")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_CONSENT = 101
        const val REFRESH_DELAY_MS = 600L
        const val SHARE_TTL_MS = 24L * 60 * 60 * 1000
        const val ACTIVE_WINDOW_MS = 120_000L
        const val TITLE_SP = 24f
        const val HEADER_SP = 18f
        const val BODY_SP = 15f
        const val SMALL_SP = 13f
    }
}
