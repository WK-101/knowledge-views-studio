package com.wkhan.hexis.voice.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttMode
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.voice.R
import com.wkhan.hexis.voice.engine.SttListener
import com.wkhan.hexis.voice.engine.WhisperSttEngine

/**
 * A privacy-first dictation keyboard: an on-device whisper.cpp input method that types transcribed
 * speech into ANY field in ANY app. This is the one clean way to "dictate everywhere" without an
 * accessibility service — the mic + model live in this addon (which already owns RECORD_AUDIO); only the
 * resulting text is committed to the focused field, and nothing leaves the device (no network
 * permission). The user enables it in system keyboard settings and switches to it like any other IME.
 */
class VoiceImeService : InputMethodService() {

    private val engine by lazy { WhisperSttEngine(applicationContext) }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var recording = false
    private var status: TextView? = null
    private var micButton: Button? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            // Adapt to the device's light/dark theme so the keyboard isn't transparent over apps.
            val bg = TypedValue()
            if (theme.resolveAttribute(android.R.attr.colorBackground, bg, true)) setBackgroundColor(bg.data)
        }

        status = TextView(this).apply {
            text = getString(R.string.ime_hint)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(status, lp(matchW = true))

        micButton = Button(this).apply {
            text = getString(R.string.ime_mic_start)
            setOnClickListener { toggle() }
        }
        root.addView(micButton, lp(matchW = true, heightDp = 96))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(key(getString(R.string.ime_switch)) { switchAway() }, rowLp())
        row.addView(key(getString(R.string.ime_space)) { ic()?.commitText(" ", 1) }, rowLp(weight = 2f))
        row.addView(key(getString(R.string.ime_backspace)) { ic()?.deleteSurroundingText(1, 0) }, rowLp())
        row.addView(key(getString(R.string.ime_enter)) { sendEnter() }, rowLp())
        root.addView(row, lp(matchW = true))

        return root
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (recording) cancelCapture()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    private fun toggle() {
        if (recording) stopCapture() else startCapture()
    }

    private fun startCapture() {
        recording = true
        setStatus(getString(R.string.ime_listening))
        micButton?.text = getString(R.string.ime_mic_stop)
        engine.startListening(
            ListenRequest(mode = SttMode.DICTATION),
            object : SttListener {
                override fun onPartial(partial: SttPartial) = Unit // batch engine: no partials
                override fun onFinal(result: SttFinal) { main.post { commit(result.text) } }
                override fun onError(type: SttErrorType, message: String?) { main.post { fail(type, message) } }
            },
        )
    }

    private fun stopCapture() {
        recording = false
        setStatus(getString(R.string.ime_transcribing))
        micButton?.text = getString(R.string.ime_mic_start)
        engine.stop("")
    }

    private fun cancelCapture() {
        recording = false
        engine.cancel("")
        micButton?.text = getString(R.string.ime_mic_start)
    }

    private fun commit(text: String) {
        recording = false
        micButton?.text = getString(R.string.ime_mic_start)
        val t = text.trim()
        if (t.isNotEmpty()) {
            ic()?.commitText("$t ", 1)
            setStatus(getString(R.string.ime_hint))
        } else {
            setStatus(getString(R.string.ime_nothing))
        }
    }

    private fun fail(type: SttErrorType, message: String?) {
        recording = false
        micButton?.text = getString(R.string.ime_mic_start)
        setStatus(
            when (type) {
                SttErrorType.MODEL_NOT_AVAILABLE -> getString(R.string.ime_no_model)
                SttErrorType.PERMISSION_DENIED -> getString(R.string.ime_no_mic)
                else -> message ?: getString(R.string.ime_error)
            },
        )
    }

    private fun sendEnter() {
        val conn = ic() ?: return
        conn.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
    }

    private fun switchAway() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    private fun ic() = currentInputConnection
    private fun setStatus(s: String) { status?.text = s }

    private fun key(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(matchW: Boolean = false, heightDp: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(
            if (matchW) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (heightDp > 0) dp(heightDp) else heightDp,
        )

    private fun rowLp(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            gravity = Gravity.CENTER
            marginStart = dp(3)
            marginEnd = dp(3)
        }
}
