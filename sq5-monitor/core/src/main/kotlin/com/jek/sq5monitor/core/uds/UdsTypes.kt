package com.jek.sq5monitor.core.uds

import com.jek.sq5monitor.core.obd.DtcDescriptions
import java.io.IOException

/** Standard UDS data identifiers (ISO 14229-1 annex C) that VAG modules populate. */
object UdsDids {
    const val SPARE_PART_NUMBER = 0xF187
    const val SOFTWARE_VERSION = 0xF189
    const val SERIAL_NUMBER = 0xF18C
    const val VIN = 0xF190
    const val HARDWARE_NUMBER = 0xF191
    const val SYSTEM_NAME = 0xF197
    const val HARDWARE_VERSION = 0xF1A3

    val identification = linkedMapOf(
        SYSTEM_NAME to "System name",
        SPARE_PART_NUMBER to "Part number",
        SOFTWARE_VERSION to "Software version",
        HARDWARE_NUMBER to "Hardware number",
        HARDWARE_VERSION to "Hardware version",
        SERIAL_NUMBER to "Serial number",
    )
}

/** A module answered with a negative response (service 0x7F). */
class UdsNegativeResponseException(val service: Int, val code: Int) :
    IOException("Module rejected service %02X: %s".format(service, nrcName(code))) {
    companion object {
        fun nrcName(code: Int): String = when (code) {
            0x10 -> "general reject"
            0x11 -> "service not supported"
            0x12 -> "sub-function not supported"
            0x13 -> "incorrect message length or format"
            0x14 -> "response too long"
            0x21 -> "busy, repeat request"
            0x22 -> "conditions not correct (is the ignition on?)"
            0x24 -> "request sequence error"
            0x31 -> "request out of range (identifier not supported)"
            0x33 -> "security access denied"
            0x35 -> "invalid key"
            0x72 -> "general programming failure"
            0x78 -> "response pending"
            0x7E -> "sub-function not supported in active session"
            0x7F -> "service not supported in active session"
            else -> "error 0x%02X".format(code)
        }
    }
}

/** The module did not answer at all (not fitted, or asleep). */
class UdsNoResponseException(module: String) : IOException("No response from $module")

/**
 * A UDS (ISO 14229) trouble code: three bytes plus a status byte.
 * The first two bytes use the SAE J2012 encoding, the third is the failure type byte, which VAG
 * tools print after the code (e.g. `P0456 00`).
 */
data class UdsDtc(val bytes: Int, val status: Int) {
    val code: String
        get() {
            val a = bytes shr 16 and 0xFF
            val b = bytes shr 8 and 0xFF
            return "%c%d%X%02X".format("PCBU"[a shr 6], (a shr 4) and 0x03, a and 0x0F, b)
        }
    val failureType: Int get() = bytes and 0xFF
    val display: String get() = "%s %02X".format(code, failureType)
    val description: String get() = DtcDescriptions.describe(code)

    val testFailed: Boolean get() = status and 0x01 != 0
    val pending: Boolean get() = status and 0x04 != 0
    val confirmed: Boolean get() = status and 0x08 != 0
    val warningLamp: Boolean get() = status and 0x80 != 0

    /** Active right now vs. stored from an earlier occurrence (how VCDS/OBDeleven word it). */
    val active: Boolean get() = testFailed

    val statusText: String
        get() = buildList {
            add(if (active) "Active" else "Intermittent / stored")
            if (confirmed) add("confirmed")
            if (pending) add("pending")
            if (warningLamp) add("warning lamp on")
        }.joinToString(", ")
}
