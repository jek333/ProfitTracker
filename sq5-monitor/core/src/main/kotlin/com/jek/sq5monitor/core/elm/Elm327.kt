package com.jek.sq5monitor.core.elm

import com.jek.sq5monitor.core.link.ElmLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the adapter reported about itself and the vehicle bus during [Elm327.initialize]. */
data class ElmInfo(
    val version: String,
    val protocol: String,
)

/**
 * Command/response driver for ELM327 (and compatible: OBDLink, vLinker, Veepeak, clones) adapters.
 *
 * Every exchange is serialised through a mutex, so the live-data poller, the diagnostics screen and
 * the module scanner can safely share one adapter.
 */
class Elm327(
    private val link: ElmLink,
    private val pollIntervalMs: Long = 4,
) {
    private val mutex = Mutex()

    /** Optional observer of raw traffic (for the in-app console / debugging). */
    @Volatile
    var trafficListener: ((sent: String, received: String) -> Unit)? = null

    val description: String get() = link.description

    /** Sends [command] and waits for the `>` prompt. */
    suspend fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResponse =
        mutex.withLock { withContext(Dispatchers.IO) { exchange(command, timeoutMs) } }

    /** Runs [block] with exclusive access to the adapter (used to keep header changes atomic). */
    suspend fun <T> exclusive(block: suspend Exclusive.() -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { Exclusive().block() } }

    inner class Exclusive internal constructor() {
        suspend fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResponse =
            exchange(command, timeoutMs)
    }

    /**
     * Resets and configures the adapter for an Audi SQ5 (8R/FY): ISO 15765-4 CAN, 11-bit IDs at 500 kbit/s,
     * with CAN headers shown so replies from different ECUs can be told apart and reassembled.
     * Falls back to automatic protocol search for other vehicles.
     */
    suspend fun initialize(): ElmInfo {
        val reset = send("ATZ", RESET_TIMEOUT_MS)
        val version = reset.lines.firstOrNull { it.contains("ELM", ignoreCase = true) }
            ?: reset.lines.lastOrNull()
            ?: "Unknown adapter"
        for (setup in SETUP_COMMANDS) send(setup).requireOk()

        send("ATSP6").requireOk()
        if (!probeBus()) {
            send("ATSP0").requireOk()
            if (!probeBus()) {
                throw ElmException(
                    "The adapter is connected but the car did not answer. Switch the ignition on and try again."
                )
            }
        }
        val protocol = send("ATDP").lines.firstOrNull()?.removePrefix("AUTO, ") ?: "Unknown"
        return ElmInfo(version = version, protocol = protocol)
    }

    /** Adapter supply voltage (OBD pin 16, i.e. the 12 V battery), via `ATRV`. */
    suspend fun batteryVoltage(): Double? =
        send("ATRV").lines.firstNotNullOfOrNull { it.trim().removeSuffix("V").toDoubleOrNull() }

    fun close() = link.close()

    private suspend fun probeBus(): Boolean {
        val response = send("0100", PROBE_TIMEOUT_MS)
        return response.error == null && !response.isNoData && response.lines.any { it.contains("41") }
    }

    private suspend fun exchange(command: String, timeoutMs: Long): ElmResponse {
        val input = link.input
        // Discard anything stale (late frames, a stray prompt) so it is not attributed to this command.
        while (input.available() > 0) input.read(ByteArray(input.available()))

        link.output.write("$command\r".toByteArray(Charsets.US_ASCII))
        link.output.flush()

        val raw = StringBuilder()
        val buffer = ByteArray(256)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val available = input.available()
            if (available > 0) {
                val read = input.read(buffer, 0, minOf(available, buffer.size))
                if (read < 0) throw IOException("Adapter connection closed")
                for (i in 0 until read) {
                    val c = (buffer[i].toInt() and 0xFF).toChar()
                    if (c == '>') {
                        val text = raw.toString()
                        trafficListener?.invoke(command, text)
                        return ElmResponse.parse(command, text)
                    }
                    raw.append(c)
                }
            } else {
                if (System.nanoTime() > deadline) throw ElmTimeoutException(command, timeoutMs)
                delay(pollIntervalMs)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val RESET_TIMEOUT_MS = 6_000L
        private const val PROBE_TIMEOUT_MS = 10_000L

        private val SETUP_COMMANDS = listOf(
            "ATE0", // echo off
            "ATL0", // no linefeeds
            "ATS1", // spaces between bytes (unambiguous parsing)
            "ATH1", // show CAN headers so multi-ECU / multi-frame replies can be reassembled
            "ATAT1", // adaptive timing
        )
    }
}
