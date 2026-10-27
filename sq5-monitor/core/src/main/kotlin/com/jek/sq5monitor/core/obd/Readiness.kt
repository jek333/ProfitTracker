package com.jek.sq5monitor.core.obd

/** State of one emissions readiness monitor (what an inspection station checks). */
data class ReadinessMonitor(val name: String, val complete: Boolean)

/** Decoded mode 01 PID 01 ("monitor status since DTCs cleared"). */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<ReadinessMonitor>,
) {
    val incomplete: List<ReadinessMonitor> get() = monitors.filterNot { it.complete }

    companion object {
        private val SPARK_MONITORS = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system",
        )
        private val DIESEL_MONITORS = listOf(
            "NMHC catalyst", "NOx/SCR aftertreatment", null, "Boost pressure",
            null, "Exhaust gas sensor", "PM filter", "EGR / VVT system",
        )
        private val CONTINUOUS = listOf("Misfire", "Fuel system", "Components")

        /** Decodes the four data bytes A-D per SAE J1979. Only monitors the car supports are listed. */
        fun decode(data: IntArray): MonitorStatus {
            require(data.size >= 4) { "Monitor status needs 4 bytes" }
            val (a, b, c, d) = data
            val diesel = b and 0x08 != 0
            val monitors = mutableListOf<ReadinessMonitor>()
            CONTINUOUS.forEachIndexed { bit, name ->
                if (b and (1 shl bit) != 0) monitors += ReadinessMonitor(name, complete = b and (1 shl (bit + 4)) == 0)
            }
            (if (diesel) DIESEL_MONITORS else SPARK_MONITORS).forEachIndexed { bit, name ->
                if (name != null && c and (1 shl bit) != 0) {
                    monitors += ReadinessMonitor(name, complete = d and (1 shl bit) == 0)
                }
            }
            return MonitorStatus(
                milOn = a and 0x80 != 0,
                dtcCount = a and 0x7F,
                compressionIgnition = diesel,
                monitors = monitors,
            )
        }
    }
}
