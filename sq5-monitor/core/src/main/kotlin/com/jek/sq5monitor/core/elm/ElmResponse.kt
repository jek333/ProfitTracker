package com.jek.sq5monitor.core.elm

/**
 * A cleaned adapter reply: echo, prompt, `SEARCHING...` and blank lines removed.
 */
data class ElmResponse(val command: String, val lines: List<String>) {

    /** `NO DATA` means the bus is fine but no ECU answered (e.g. an unsupported PID). */
    val isNoData: Boolean get() = lines.any { it.replace(" ", "") == "NODATA" }

    /** The first adapter error line, if any. `NO DATA` is not considered an error. */
    val error: String? get() = lines.firstOrNull { line -> ERRORS.any { line.contains(it) } || line == "?" }

    val text: String get() = lines.joinToString("\n")

    fun requireOk(): ElmResponse {
        error?.let { throw ElmErrorException(command, it) }
        return this
    }

    companion object {
        private val ERRORS = listOf(
            "CAN ERROR", "BUS ERROR", "BUS BUSY", "BUS INIT", "UNABLE TO CONNECT", "FB ERROR",
            "DATA ERROR", "BUFFER FULL", "STOPPED", "ACT ALERT", "LV RESET", "LP ALERT", "RX ERROR",
            "ERR",
        )

        /** Splits a raw reply (without the `>` prompt) into meaningful lines. */
        fun parse(command: String, raw: String): ElmResponse {
            val lines = raw.replace("\u0000", "")
                .split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.removePrefix("SEARCHING...").trim() }
                .filter { it.isNotEmpty() }
                // Echo is disabled during init, but the first commands after a reset still echo.
                .filterNot { it.replace(" ", "").equals(command.replace(" ", ""), ignoreCase = true) }
            return ElmResponse(command, lines)
        }
    }
}
