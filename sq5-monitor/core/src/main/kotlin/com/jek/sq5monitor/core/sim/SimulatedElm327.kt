package com.jek.sq5monitor.core.sim

import com.jek.sq5monitor.core.link.ElmLink
import com.jek.sq5monitor.core.link.QueueInputStream
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.uds.UdsDids
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory ELM327 v1.5 connected to a simulated SQ5. It implements the AT commands this app uses,
 * OBD-II modes 01/03/04/07/09/0A on functional addressing (7DF), and UDS services 0x10/0x14/0x19/0x22/0x3E
 * on the physical module addresses, producing real ISO-TP framing. Powers the app's demo mode and the
 * protocol integration tests, so the full stack can be exercised without a car.
 */
class SimulatedElm327(
    private val clock: () -> Long = System::currentTimeMillis,
    /** VIN reported by the demo car (a 2018 SQ5 pattern with a valid check digit). */
    val vin: String = DEMO_VIN,
) : ElmLink {

    override val description: String = "Demo car (simulated 2018 SQ5)"

    private val lock = Object()
    private val toHost = QueueInputStream()
    private val command = StringBuilder()
    private var closed = false

    private val car = SimulatedCar(clock())

    // Adapter settings
    private var echo = true
    private var headers = false
    private var spaces = true
    private var header = FUNCTIONAL_ID
    private var receiveFilter: Int? = null

    // Engine fault memory: two-byte SAE codes; P0456 (EVAP very small leak) is the classic loose fuel cap.
    private val engineDtcs = mutableListOf(0x0456)
    private var readinessIncomplete = 0x04 // EVAP monitor not yet complete

    private class Module(val responseId: Int, val name: String, val dtcs: MutableList<Pair<Int, Int>>)

    private val modules: Map<Int, Module> = mapOf(
        0x7E0 to Module(0x7E8, "J623 ENGINE", mutableListOf()),
        0x7E1 to Module(0x7E9, "J217 TRANSMISSION", mutableListOf()),
        0x713 to Module(0x77D, "J104 BRAKES", mutableListOf()),
        0x746 to Module(0x7B0, "J255 CLIMATE", mutableListOf()),
        0x70E to Module(0x778, "J519 CENTRAL ELEC", mutableListOf()),
        0x715 to Module(0x77F, "J234 AIRBAG", mutableListOf()),
        0x714 to Module(0x77E, "J285 INSTRUMENTS", mutableListOf()),
        0x710 to Module(0x77A, "J533 GATEWAY", mutableListOf(0xC10100 to 0x28)), // U0101 00, stored
        0x712 to Module(0x77C, "J500 STEERING", mutableListOf()),
        0x773 to Module(0x7DD, "J794 MMI", mutableListOf()),
    )

    /** Ignition state; when off the car does not answer (only the adapter does). */
    @Volatile
    var ignitionOn: Boolean = true

    override val input: InputStream = toHost

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            synchronized(lock) {
                if (closed) throw IOException("Link closed")
                when (val c = b.toChar()) {
                    '\r' -> {
                        val cmd = command.toString()
                        command.clear()
                        reply(cmd, handle(cmd.replace(" ", "").uppercase()))
                    }
                    '\n' -> Unit
                    else -> command.append(c)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        toHost.close()
    }

    private fun reply(cmd: String, lines: List<String>) {
        val text = buildString {
            if (echo) append(cmd).append('\r')
            lines.forEach { append(it).append('\r') }
            append("\r>")
        }
        toHost.push(text.toByteArray(Charsets.US_ASCII))
    }

    private fun handle(cmd: String): List<String> {
        if (cmd.isEmpty()) return emptyList()
        if (cmd.startsWith("AT")) return handleAt(cmd.substring(2))
        if (cmd.length % 2 != 0 || !cmd.all { it in HEX }) return listOf("?")
        if (!ignitionOn) return listOf("NO DATA")
        val request = cmd.chunked(2).map { it.toInt(16) }.toIntArray()

        val responses: List<Pair<Int, IntArray>> = if (header == FUNCTIONAL_ID) {
            listOfNotNull(
                obdResponse(ENGINE_ID, request)?.let { ENGINE_ID to it },
                obdResponse(TRANSMISSION_ID, request)?.let { TRANSMISSION_ID to it },
            )
        } else {
            val module = modules[header] ?: return listOf("NO DATA")
            listOfNotNull(udsResponse(module, request)?.let { module.responseId to it })
        }
        val visible = responses.filter { receiveFilter == null || it.first == receiveFilter }
        if (visible.isEmpty()) return listOf("NO DATA")
        return visible.flatMap { (id, payload) -> format(id, payload) }
    }

    private fun handleAt(at: String): List<String> = when {
        at == "Z" -> { resetAdapter(); listOf("", "ELM327 v1.5") }
        at == "I" -> listOf("ELM327 v1.5")
        at == "RV" -> listOf("%.1fV".format(java.util.Locale.US, if (ignitionOn) car.state(clock()).batteryV else 12.4))
        at == "DP" -> listOf("ISO 15765-4 (CAN 11/500)")
        at == "DPN" -> listOf("6")
        at == "E0" -> { echo = false; ok() }
        at == "E1" -> { echo = true; ok() }
        at == "H0" -> { headers = false; ok() }
        at == "H1" -> { headers = true; ok() }
        at == "S0" -> { spaces = false; ok() }
        at == "S1" -> { spaces = true; ok() }
        at == "AR" || at == "CRA" -> { receiveFilter = null; ok() }
        at.startsWith("SH") && at.length == 5 -> { header = at.substring(2).toInt(16); ok() }
        at.startsWith("CRA") && at.length == 6 -> { receiveFilter = at.substring(3).toInt(16); ok() }
        at.matches(Regex("L[01]|AT[012]|SP[0-9A-C]|ST[0-9A-F]{2}|D|FCSM[01]|FCSH[0-9A-F]{3}|FCSD[0-9A-F]{2,10}")) -> ok()
        else -> listOf("?")
    }

    private fun ok() = listOf("OK")

    private fun resetAdapter() {
        echo = true; headers = false; spaces = true; header = FUNCTIONAL_ID; receiveFilter = null
    }

    private fun obdResponse(ecu: Int, request: IntArray): IntArray? {
        val mode = request[0]
        val isEngine = ecu == ENGINE_ID
        return when (mode) {
            0x01 -> {
                val supported = if (isEngine) SimulatedCar.ENGINE_PIDS else setOf(Pids.MONITOR_STATUS)
                val state = car.state(clock())
                val body = mutableListOf(0x41)
                for (pid in request.drop(1)) {
                    val data = if (pid % 0x20 == 0) bitmap(pid, supported) else if (pid in supported) {
                        car.encode(pid, state, milOn = isEngine && engineDtcs.isNotEmpty(),
                            dtcCount = if (isEngine) engineDtcs.size else 0,
                            readinessIncomplete = if (isEngine) readinessIncomplete else 0)
                    } else null
                    if (data != null) { body += pid; body += data.toList() }
                }
                if (body.size > 1) body.toIntArray() else null
            }
            0x03 -> dtcList(0x43, if (isEngine) engineDtcs else emptyList())
            0x07, 0x0A -> dtcList(mode + 0x40, emptyList())
            0x04 -> {
                if (isEngine) { engineDtcs.clear(); readinessIncomplete = SimulatedCar.READINESS_SUPPORTED }
                intArrayOf(0x44)
            }
            0x09 -> if (isEngine && request.getOrNull(1) == 0x02) {
                intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray()
            } else null
            else -> null
        }
    }

    private fun bitmap(base: Int, supported: Set<Int>): IntArray? {
        val inRange = supported.filter { it in base + 1..base + 0x20 }
        val more = supported.any { it > base + 0x20 }
        if (base != 0 && inRange.isEmpty()) return null
        var bits = 0L
        inRange.forEach { bits = bits or (1L shl (31 - (it - base - 1))) }
        if (more) bits = bits or 1L
        return intArrayOf((bits shr 24).toInt() and 0xFF, (bits shr 16).toInt() and 0xFF, (bits shr 8).toInt() and 0xFF, bits.toInt() and 0xFF)
    }

    private fun dtcList(sid: Int, codes: List<Int>): IntArray =
        intArrayOf(sid, codes.size) + codes.flatMap { listOf(it shr 8, it and 0xFF) }

    private fun udsResponse(module: Module, request: IntArray): IntArray? {
        val sid = request[0]
        fun negative(code: Int) = intArrayOf(0x7F, sid, code)
        val isEngine = module.responseId == ENGINE_ID
        return when (sid) {
            0x10 -> if (request.size == 2) intArrayOf(0x50, request[1], 0x00, 0x32, 0x01, 0xF4) else negative(0x13)
            0x3E -> intArrayOf(0x7E, 0x00)
            0x22 -> {
                if (request.size != 3) return negative(0x13)
                val did = request[1] shl 8 or request[2]
                val text = when (did) {
                    UdsDids.SYSTEM_NAME -> module.name.padEnd(13)
                    UdsDids.SPARE_PART_NUMBER -> "DEMO%03X    ".format(module.responseId)
                    UdsDids.SOFTWARE_VERSION -> "D001"
                    UdsDids.HARDWARE_NUMBER -> "DEMO-HW"
                    UdsDids.VIN -> vin
                    else -> null
                } ?: return negative(0x31)
                intArrayOf(0x62, request[1], request[2]) + text.map { it.code }.toIntArray()
            }
            0x19 -> {
                if (request.size < 3 || request[1] != 0x02) return negative(0x12)
                val mask = request[2]
                val records = if (isEngine) engineDtcs.map { (it shl 8) to 0x28 } else module.dtcs
                intArrayOf(0x59, 0x02, 0xFF) + records.filter { it.second and mask != 0 }
                    .flatMap { (code, status) -> listOf(code shr 16 and 0xFF, code shr 8 and 0xFF, code and 0xFF, status) }
            }
            0x14 -> {
                module.dtcs.clear()
                if (isEngine) { engineDtcs.clear(); readinessIncomplete = SimulatedCar.READINESS_SUPPORTED }
                intArrayOf(0x54)
            }
            else -> negative(0x11)
        }
    }

    /** Formats a message the way an ELM327 prints it, honouring the H/S settings. */
    private fun format(id: Int, payload: IntArray): List<String> {
        val sep = if (spaces) " " else ""
        fun hex(bytes: List<Int>) = bytes.joinToString(sep) { "%02X".format(it) }
        val frames = mutableListOf<List<Int>>()
        if (payload.size <= 7) {
            frames += (listOf(payload.size) + payload.toList()).padded()
        } else {
            frames += (listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
            payload.drop(6).chunked(7).forEachIndexed { i, chunk -> frames += (listOf(0x20 or ((i + 1) and 0x0F)) + chunk).padded() }
        }
        if (headers) return frames.map { "%03X".format(id) + sep + hex(it) }
        // Headers off: single frames lose the PCI byte; multi-frame uses the ELM's numbered layout.
        if (payload.size <= 7) return listOf(hex(payload.toList()))
        return listOf("%03X".format(payload.size)) + frames.mapIndexed { i, frame ->
            "${i.toString(16).uppercase()}:$sep" + hex(if (i == 0) frame.drop(2) else frame.drop(1))
        }
    }

    private fun List<Int>.padded(): List<Int> = this + List(8 - size) { PADDING }

    companion object {
        /** Not a real car: WA1 (Audi SUV) + C4AFY (SQ5) + check digit + J (2018) + plant + serial. */
        const val DEMO_VIN = "WA1C4AFY5J2000001"

        private const val FUNCTIONAL_ID = 0x7DF
        private const val ENGINE_ID = 0x7E8
        private const val TRANSMISSION_ID = 0x7E9
        private const val PADDING = 0x55
        private const val HEX = "0123456789ABCDEF"
    }
}
