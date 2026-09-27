package app.parley.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Rings an emulator: its console (host port 5554, reached from the guest as 10.0.2.2) takes `gsm call <number>`. The
 * console wants the token in the host's ~/.emulator_console_auth_token, passed as the instrumentation argument
 * `consoleToken`. Only the benchmark APK talks to the console; Parley has no network access.
 */
object EmulatorConsole {
    private const val HOST = "10.0.2.2"

    private val port: Int get() = InstrumentationRegistry.getArguments().getString("consolePort")?.toIntOrNull() ?: 5554
    private val token: String? get() = InstrumentationRegistry.getArguments().getString("consoleToken")

    val available: Boolean get() = token != null

    fun ring(number: String) = send("gsm call $number")

    fun hangUp(number: String) = send("gsm cancel $number")

    private fun send(command: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, port), 2_000)
            s.soTimeout = 2_000
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = PrintWriter(s.getOutputStream(), true)
            fun drain() {
                while (true) {
                    val line = runCatching { input.readLine() }.getOrNull() ?: return
                    if (line.startsWith("OK") || line.startsWith("KO")) return
                }
            }
            drain()
            out.println("auth $token")
            drain()
            out.println(command)
            drain()
            out.println("quit")
        }
    }
}
