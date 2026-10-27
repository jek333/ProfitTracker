package com.jek.sq5monitor.core.obd

import com.jek.sq5monitor.core.elm.Elm327
import com.jek.sq5monitor.core.elm.ElmResponse
import com.jek.sq5monitor.core.isotp.EcuMessage
import com.jek.sq5monitor.core.isotp.IsoTp

/** Trouble codes reported by one ECU for one [DtcKind]. */
data class EcuDtcs(val ecuId: Int, val kind: DtcKind, val codes: List<Dtc>) {
    val ecuName: String get() = ObdClient.ecuName(ecuId)
}

/**
 * Generic (SAE J1979) OBD-II services over an initialised [Elm327]: live PIDs, trouble codes,
 * readiness and VIN. These are answered by the engine (7E8) and transmission (7E9) ECUs.
 */
class ObdClient(private val elm: Elm327) {

    /** Learned at runtime: some ECUs reject multi-PID requests, after which we go one at a time. */
    @Volatile
    private var multiPidSupported = true

    /** Discovers supported mode 01 PIDs by walking the 0x00/0x20/0x40... bitmaps. */
    suspend fun supportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val data = readPids(listOf(base))[base] ?: break
            val chunk = Pids.decodeSupported(base, data)
            supported += chunk
            val next = base + 0x20
            if (next !in chunk) break
            base = next
        }
        return supported.filterNot { it % 0x20 == 0 }.toSet()
    }

    /**
     * Reads mode 01 PIDs and returns their raw data bytes. Up to six PIDs are requested per message
     * (allowed on CAN), which makes polling several times faster on Bluetooth adapters.
     * PIDs the car does not answer are simply absent from the result.
     */
    suspend fun readPids(pids: List<Int>): Map<Int, IntArray> {
        val result = mutableMapOf<Int, IntArray>()
        val batchable = pids.filter { Pids.byteCount(it) != null }
        val batches = if (multiPidSupported) batchable.chunked(MAX_PIDS_PER_REQUEST) else batchable.map { listOf(it) }
        for (batch in batches) {
            val response = elm.send("01" + batch.joinToString("") { "%02X".format(it) })
            if (response.isNoData) continue
            response.requireOk()
            val decoded = parseMode01(messagesFor(response, 0x41))
            if (batch.size > 1 && decoded.isEmpty()) {
                multiPidSupported = false
                result += readPids(batch)
            } else {
                result += decoded
            }
        }
        return result
    }

    /** Reads and decodes PIDs into canonical units. */
    suspend fun readValues(pids: Collection<Int>): Map<Int, Double> =
        readPids(pids.toList()).mapNotNull { (pid, data) ->
            val definition = Pids[pid] ?: return@mapNotNull null
            if (data.size < definition.byteCount) null else pid to definition.decode(data)
        }.toMap()

    suspend fun monitorStatus(): MonitorStatus? =
        readPids(listOf(Pids.MONITOR_STATUS))[Pids.MONITOR_STATUS]?.let(MonitorStatus::decode)

    suspend fun readDtcs(kind: DtcKind): List<EcuDtcs> {
        val response = elm.send("%02X".format(kind.mode), DTC_TIMEOUT_MS)
        if (response.isNoData) return emptyList()
        response.requireOk()
        return messagesFor(response, kind.mode + 0x40)
            .groupBy { it.ecuId }
            .map { (ecu, messages) -> EcuDtcs(ecu, kind, messages.flatMap { Dtc.parseResponse(it.payload) }.distinct()) }
            .sortedBy { it.ecuId }
    }

    /**
     * Mode 04: clears stored/pending codes and freeze frames and turns off the check engine light.
     * Also resets readiness monitors, so the car will not pass an emissions inspection until it
     * has been driven through its monitor cycles again.
     */
    suspend fun clearDtcs(): Boolean {
        val response = elm.send("04", DTC_TIMEOUT_MS)
        return messagesFor(response, 0x44).isNotEmpty()
    }

    /** Mode 09 PID 02. */
    suspend fun readVin(): String? {
        val response = elm.send("0902", DTC_TIMEOUT_MS)
        if (response.isNoData || response.error != null) return null
        val message = messagesFor(response, 0x49).firstOrNull { it.payload.size > 2 && it.payload[1] == 0x02 } ?: return null
        // 49 02 <count> <17 ASCII bytes>; keep only printable VIN characters.
        return message.payload.drop(3)
            .map { it.toChar() }
            .filter { it.isLetterOrDigit() }
            .joinToString("")
            .takeLast(17)
            .takeIf { it.length == 17 }
    }

    private fun messagesFor(response: ElmResponse, positiveSid: Int): List<EcuMessage> =
        IsoTp.assemble(response.lines).filter { it.payload.isNotEmpty() && it.payload[0] == positiveSid }

    /** Prefers the engine ECU (7E8) when several ECUs answer the same PID. */
    private fun parseMode01(messages: List<EcuMessage>): Map<Int, IntArray> {
        val result = mutableMapOf<Int, IntArray>()
        for (message in messages.sortedByDescending { it.ecuId == ENGINE_ECU }) {
            val payload = message.payload
            var index = 1
            while (index < payload.size) {
                val pid = payload[index]
                val length = Pids.byteCount(pid) ?: break
                if (index + 1 + length > payload.size) break
                result.putIfAbsent(pid, payload.copyOfRange(index + 1, index + 1 + length))
                index += 1 + length
            }
        }
        return result
    }

    companion object {
        const val ENGINE_ECU = 0x7E8
        const val TRANSMISSION_ECU = 0x7E9
        private const val MAX_PIDS_PER_REQUEST = 6
        private const val DTC_TIMEOUT_MS = 5_000L

        fun ecuName(id: Int): String = when (id) {
            ENGINE_ECU -> "Engine"
            TRANSMISSION_ECU -> "Transmission"
            else -> "ECU %03X".format(id)
        }
    }
}
