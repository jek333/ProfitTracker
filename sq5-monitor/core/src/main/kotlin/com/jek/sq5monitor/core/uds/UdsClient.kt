package com.jek.sq5monitor.core.uds

import com.jek.sq5monitor.core.elm.Elm327
import com.jek.sq5monitor.core.elm.ElmTimeoutException
import com.jek.sq5monitor.core.isotp.IsoTp

/**
 * Read-only UDS (ISO 14229) access to individual control modules through an ELM327.
 *
 * Only diagnostic *read* services plus "clear DTCs" are exposed. Coding, adaptations and security
 * access are deliberately not implemented: a failed write over a cheap ELM327 clone can leave a
 * module unconfigured, and this app is a monitoring tool.
 */
class UdsClient(private val elm: Elm327) {

    /**
     * Points the adapter at [module], runs [block], then restores functional OBD-II addressing
     * (7DF broadcast) so live data keeps working. The adapter is locked for the duration.
     */
    suspend fun <T> withModule(module: VagModule, block: suspend Session.() -> T): T = elm.exclusive {
        val adapter = this
        try {
            for (command in listOf(
                "ATSH%03X".format(module.requestId),
                "ATCRA%03X".format(module.responseId),
                "ATFCSH%03X".format(module.requestId),
                "ATFCSD300000", // flow control: continue, no block limit, no separation time
                "ATFCSM1",
                "ATAT0", // adaptive timing can cut off slow modules
                "ATST%02X".format(RESPONSE_TIMEOUT_UNITS),
            )) adapter.send(command).requireOk()
            Session(module, adapter).block()
        } finally {
            for (command in RESTORE_COMMANDS) runCatching { adapter.send(command) }
        }
    }

    class Session internal constructor(val module: VagModule, private val adapter: Elm327.Exclusive) {

        /**
         * Sends a raw UDS request and returns the positive response payload (starting with SID + 0x40).
         * Handles "response pending" (NRC 0x78) by re-requesting a few times.
         */
        suspend fun request(vararg bytes: Int): IntArray {
            val hex = bytes.joinToString("") { "%02X".format(it) }
            repeat(PENDING_RETRIES) {
                val response = try {
                    adapter.send(hex, REQUEST_TIMEOUT_MS)
                } catch (e: ElmTimeoutException) {
                    throw UdsNoResponseException(module.label)
                }
                if (response.isNoData) throw UdsNoResponseException(module.label)
                response.requireOk()
                val messages = IsoTp.assemble(response.lines).filter { it.ecuId == module.responseId }
                if (messages.isEmpty()) throw UdsNoResponseException(module.label)

                val positive = messages.lastOrNull { it.payload[0] == bytes[0] + 0x40 }
                if (positive != null) return positive.payload
                val negative = messages.lastOrNull { it.payload[0] == 0x7F }?.payload
                    ?: throw UdsNoResponseException(module.label)
                val code = negative.getOrElse(2) { 0x10 }
                if (code != 0x78 && code != 0x21) throw UdsNegativeResponseException(bytes[0], code)
            }
            throw UdsNegativeResponseException(bytes[0], 0x78)
        }

        /** Service 0x22 ReadDataByIdentifier; returns the record bytes. */
        suspend fun readDid(did: Int): IntArray {
            val response = request(0x22, did shr 8, did and 0xFF)
            return response.copyOfRange(3, response.size)
        }

        /** Reads an identification DID as text (VAG pads them with spaces). Null if unsupported. */
        suspend fun readDidText(did: Int): String? = try {
            readDid(did).map { it.toChar() }.filter { it.code in 0x20..0x7E }.joinToString("").trim().ifEmpty { null }
        } catch (e: UdsNegativeResponseException) {
            null
        }

        /** Service 0x19 sub-function 0x02: all DTCs matching any status bit. */
        suspend fun readDtcs(): List<UdsDtc> {
            val response = request(0x19, 0x02, 0xFF)
            // 59 02 <availability mask> then 4-byte records.
            return (3 until response.size - 3 step 4).map { i ->
                UdsDtc(
                    bytes = (response[i] shl 16) or (response[i + 1] shl 8) or response[i + 2],
                    status = response[i + 3],
                )
            }.filter { it.bytes != 0 }
        }

        /** Service 0x14 ClearDiagnosticInformation for all groups. */
        suspend fun clearDtcs() {
            request(0x14, 0xFF, 0xFF, 0xFF)
        }
    }

    companion object {
        /** ATST is in units of 4 ms: 0x64 = 400 ms, enough for gateway-routed modules. */
        private const val RESPONSE_TIMEOUT_UNITS = 0x64
        private const val REQUEST_TIMEOUT_MS = 4_000L
        private const val PENDING_RETRIES = 4

        private val RESTORE_COMMANDS = listOf("ATSH7DF", "ATAR", "ATFCSM0", "ATST32", "ATAT1")
    }
}
