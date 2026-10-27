package com.jek.sq5monitor.core.obd

/**
 * A SAE J1979 mode 01 parameter. [decode] receives the data bytes (A, B, ...) as unsigned ints and
 * returns the value in the canonical unit of [quantity] (see [Units]).
 */
class Pid(
    val pid: Int,
    val name: String,
    val quantity: Quantity,
    val byteCount: Int,
    val decode: (IntArray) -> Double,
) {
    val hex: String get() = "%02X".format(pid)
}

/** The mode 01 PIDs this app understands (formulas from SAE J1979 / ISO 15031-5). */
object Pids {
    const val MONITOR_STATUS = 0x01
    const val ENGINE_LOAD = 0x04
    const val COOLANT_TEMP = 0x05
    const val STFT_B1 = 0x06
    const val LTFT_B1 = 0x07
    const val STFT_B2 = 0x08
    const val LTFT_B2 = 0x09
    const val INTAKE_MAP = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val TIMING_ADVANCE = 0x0E
    const val INTAKE_AIR_TEMP = 0x0F
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val RUN_TIME = 0x1F
    const val DISTANCE_WITH_MIL = 0x21
    const val FUEL_RAIL_PRESSURE = 0x23
    const val FUEL_LEVEL = 0x2F
    const val WARMUPS_SINCE_CLEAR = 0x30
    const val DISTANCE_SINCE_CLEAR = 0x31
    const val BAROMETRIC = 0x33
    const val CAT_TEMP_B1S1 = 0x3C
    const val CAT_TEMP_B2S1 = 0x3E
    const val MODULE_VOLTAGE = 0x42
    const val ABSOLUTE_LOAD = 0x43
    const val LAMBDA_COMMANDED = 0x44
    const val RELATIVE_THROTTLE = 0x45
    const val AMBIENT_TEMP = 0x46
    const val PEDAL_D = 0x49
    const val PEDAL_E = 0x4A
    const val THROTTLE_COMMANDED = 0x4C
    const val TIME_WITH_MIL = 0x4D
    const val TIME_SINCE_CLEAR = 0x4E
    const val OIL_TEMP = 0x5C
    const val FUEL_RATE = 0x5E

    private fun u16(d: IntArray) = (d[0] shl 8 or d[1]).toDouble()
    private fun percent(d: IntArray) = d[0] * 100.0 / 255.0
    private fun temp(d: IntArray) = d[0] - 40.0
    private fun trim(d: IntArray) = (d[0] - 128) * 100.0 / 128.0

    val all: List<Pid> = listOf(
        Pid(ENGINE_LOAD, "Calculated engine load", Quantity.PERCENT, 1, ::percent),
        Pid(COOLANT_TEMP, "Coolant temperature", Quantity.TEMPERATURE, 1, ::temp),
        Pid(STFT_B1, "Short term fuel trim, bank 1", Quantity.PERCENT, 1, ::trim),
        Pid(LTFT_B1, "Long term fuel trim, bank 1", Quantity.PERCENT, 1, ::trim),
        Pid(STFT_B2, "Short term fuel trim, bank 2", Quantity.PERCENT, 1, ::trim),
        Pid(LTFT_B2, "Long term fuel trim, bank 2", Quantity.PERCENT, 1, ::trim),
        Pid(INTAKE_MAP, "Intake manifold pressure", Quantity.PRESSURE, 1) { it[0].toDouble() },
        Pid(RPM, "Engine speed", Quantity.ROTATION, 2) { u16(it) / 4.0 },
        Pid(SPEED, "Vehicle speed", Quantity.SPEED, 1) { it[0].toDouble() },
        Pid(TIMING_ADVANCE, "Timing advance", Quantity.ANGLE, 1) { it[0] / 2.0 - 64.0 },
        Pid(INTAKE_AIR_TEMP, "Intake air temperature", Quantity.TEMPERATURE, 1, ::temp),
        Pid(MAF, "Mass air flow", Quantity.MASS_FLOW, 2) { u16(it) / 100.0 },
        Pid(THROTTLE, "Throttle position", Quantity.PERCENT, 1, ::percent),
        Pid(RUN_TIME, "Run time since engine start", Quantity.DURATION, 2, ::u16),
        Pid(DISTANCE_WITH_MIL, "Distance with check engine light on", Quantity.DISTANCE, 2, ::u16),
        Pid(FUEL_RAIL_PRESSURE, "Fuel rail pressure", Quantity.PRESSURE, 2) { u16(it) * 10.0 },
        Pid(FUEL_LEVEL, "Fuel level", Quantity.PERCENT, 1, ::percent),
        Pid(WARMUPS_SINCE_CLEAR, "Warm-ups since codes cleared", Quantity.RATIO, 1) { it[0].toDouble() },
        Pid(DISTANCE_SINCE_CLEAR, "Distance since codes cleared", Quantity.DISTANCE, 2, ::u16),
        Pid(BAROMETRIC, "Barometric pressure", Quantity.PRESSURE, 1) { it[0].toDouble() },
        Pid(CAT_TEMP_B1S1, "Catalyst temperature, bank 1", Quantity.TEMPERATURE, 2) { u16(it) / 10.0 - 40.0 },
        Pid(CAT_TEMP_B2S1, "Catalyst temperature, bank 2", Quantity.TEMPERATURE, 2) { u16(it) / 10.0 - 40.0 },
        Pid(MODULE_VOLTAGE, "Control module voltage", Quantity.VOLTAGE, 2) { u16(it) / 1000.0 },
        Pid(ABSOLUTE_LOAD, "Absolute load", Quantity.PERCENT, 2) { u16(it) * 100.0 / 255.0 },
        Pid(LAMBDA_COMMANDED, "Commanded lambda", Quantity.RATIO, 2) { u16(it) * 2.0 / 65536.0 },
        Pid(RELATIVE_THROTTLE, "Relative throttle position", Quantity.PERCENT, 1, ::percent),
        Pid(AMBIENT_TEMP, "Ambient air temperature", Quantity.TEMPERATURE, 1, ::temp),
        Pid(PEDAL_D, "Accelerator pedal position D", Quantity.PERCENT, 1, ::percent),
        Pid(PEDAL_E, "Accelerator pedal position E", Quantity.PERCENT, 1, ::percent),
        Pid(THROTTLE_COMMANDED, "Commanded throttle actuator", Quantity.PERCENT, 1, ::percent),
        Pid(TIME_WITH_MIL, "Time with check engine light on", Quantity.DURATION, 2) { u16(it) * 60.0 },
        Pid(TIME_SINCE_CLEAR, "Time since codes cleared", Quantity.DURATION, 2) { u16(it) * 60.0 },
        Pid(OIL_TEMP, "Engine oil temperature", Quantity.TEMPERATURE, 1, ::temp),
        Pid(FUEL_RATE, "Engine fuel rate", Quantity.FUEL_RATE, 2) { u16(it) / 20.0 },
    )

    private val byId = all.associateBy { it.pid }

    operator fun get(pid: Int): Pid? = byId[pid]

    /** Data byte count for a mode 01 PID, including ones we read but do not display (e.g. 0x01). */
    fun byteCount(pid: Int): Int? = when {
        pid % 0x20 == 0 -> 4 // supported-PID bitmaps
        pid == MONITOR_STATUS -> 4
        else -> byId[pid]?.byteCount
    }

    /**
     * Decodes a "PIDs supported" bitmap reply. [base] is the request PID (0x00, 0x20, ...);
     * bit 31 of the bitmap corresponds to [base] + 1.
     */
    fun decodeSupported(base: Int, data: IntArray): Set<Int> {
        require(data.size >= 4) { "Supported-PID bitmap needs 4 bytes" }
        val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
        return (0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { base + it + 1 }.toSet()
    }
}
