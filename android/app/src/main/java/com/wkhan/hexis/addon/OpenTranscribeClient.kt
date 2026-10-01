package com.wkhan.hexis.addon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor

import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest

import java.util.concurrent.Executors

/**
 * Phase 3 — the core as a CLIENT of the vendor-neutral Open Transcribe contract. It discovers any
 * installed transcriber (Scrib, our own addon, …), binds the one the user picks, hands it an audio
 * file descriptor and receives text back. The core needs NO microphone permission for this (the user
 * brings a file); the transcriber does the privileged work. Audio crosses only as a dup'd FD.
 */
class OpenTranscribeClient(private val appContext: Context) {

    data class Provider(val packageName: String, val className: String, val label: String)

    interface Listener {
        fun onProgress(cumulativeText: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var connection: ServiceConnection? = null

    /** Installed Open Transcribe providers. Present these in a chooser — never auto-bind. */
    fun providers(): List<Provider> {
        val pm = appContext.packageManager
        val intent = Intent(ACTION)
        val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        return resolves.mapNotNull { ri ->
            val svc = ri.serviceInfo ?: return@mapNotNull null
            Provider(svc.packageName, svc.name, svc.loadLabel(pm)?.toString().orEmpty().ifBlank { svc.packageName })
        }
    }

    /**
     * Transcribe [audio] via [provider]. The client owns [audio] and closes it once [transcribe]
     * returns — Binder has dup'd the descriptor into the provider by then.
     */
    // LongParameterList: a faithful mirror of the Open Transcribe request fields. TooGenericExceptionCaught:
    // an IPC boundary must never crash the app on any remote failure (RemoteException, SecurityException, …).
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    fun transcribeFile(
        provider: Provider,
        audio: ParcelFileDescriptor,
        fileName: String?,
        mimeType: String?,
        languageHint: String?,
        listener: Listener,
    ) {
        close()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                io.execute {
                    try {
                        val service = ITranscriptionService.Stub.asInterface(binder)
                        val caps = service.capabilities
                        if (caps != null && !caps.modelReady) {
                            listener.onError("The transcriber's model isn't downloaded yet.")
                            close()
                            return@execute
                        }
                        val request = TranscriptionRequest().apply {
                            this.fileName = fileName
                            this.mimeType = mimeType
                            this.languageHint = languageHint
                        }
                        service.transcribe(audio, request, callback(listener))
                    } catch (t: Throwable) {
                        listener.onError(t.message ?: "Transcription failed.")
                        close()
                    } finally {
                        runCatching { audio.close() }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = conn
        val intent = Intent(ACTION).setClassName(provider.packageName, provider.className)
        if (!appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            listener.onError("Couldn't connect to the transcriber.")
            runCatching { audio.close() }
        }
    }

    fun close() {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
    }

    private fun callback(listener: Listener) = object : ITranscriptionCallback.Stub() {
        override fun onTranscriptionProgress(text: String?) {
            listener.onProgress(text.orEmpty())
        }
        override fun onTranscriptionResult(text: String?) {
            listener.onResult(text.orEmpty())
            close()
        }
        override fun onTranscriptionError(error: TranscriptionError?) {
            // AIDL enums are int-backed constants, so there's no .name — fall back to a message.
            listener.onError(error?.message ?: "Transcription error.")
            close()
        }
    }

    private companion object {
        const val ACTION = "org.opentranscribe.api.ITranscriptionService"
    }
}
