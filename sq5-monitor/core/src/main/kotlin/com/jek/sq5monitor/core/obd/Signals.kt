package com.jek.sq5monitor.core.obd

/**
 * Something that can be shown on a gauge: either a raw PID or a value derived from several sources.
 *
 * @property fast whether the signal changes quickly enough to need polling every cycle. Slow
 *   signals (temperatures, fuel level, battery) are refreshed every few cycles to keep the
 *   fast ones (rpm, boost) responsive on slow Bluetooth adapters.
 */
class Signal(
    val id: String,
    val name: String,
    val shortName: String,
    val quantity: Quantity,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val fast: Boolean,
    /** Mode 01 PIDs needed to compute this signal. */
    val pids: Set<Int>,
    /** Whether the adapter voltage (`ATRV`) is needed. */
    val needsBattery: Boolean = false,
    val compute: (SignalInputs) -> Double?,
)

/** Raw values available to [Signal.compute] in canonical units. */
class SignalInputs(val pids: Map<Int, Double>, val batteryVoltage: Double?)

/** All gauge-able signals. Ids are stable and persisted in settings, so never rename them. */
object Signals {
    const val BOOST = "boost"
    const val BATTERY = "battery"
    const val FUEL_ECONOMY = "economy"

    /** Approximate stoichiometric air/fuel ratio and density of pump gasoline. */
    private const val GASOLINE_AFR = 14.7
    private const val GASOLINE_DENSITY_G_PER_L = 745.0

    fun pidId(pid: Int) = "pid:%02X".format(pid)

    private val fastPids = setOf(
        Pids.ENGINE_LOAD, Pids.STFT_B1, Pids.STFT_B2, Pids.INTAKE_MAP, Pids.RPM, Pids.SPEED,
        Pids.TIMING_ADVANCE, Pids.MAF, Pids.THROTTLE, Pids.FUEL_RAIL_PRESSURE, Pids.ABSOLUTE_LOAD,
        Pids.LAMBDA_COMMANDED, Pids.RELATIVE_THROTTLE, Pids.PEDAL_D, Pids.PEDAL_E,
        Pids.THROTTLE_COMMANDED, Pids.FUEL_RATE,
    )

    private val ranges: Map<Int, Pair<Double, Double>> = mapOf(
        Pids.RPM to (0.0 to 7000.0),
        Pids.SPEED to (0.0 to 260.0),
        Pids.INTAKE_MAP to (0.0 to 255.0),
        Pids.TIMING_ADVANCE to (-20.0 to 50.0),
        Pids.MAF to (0.0 to 300.0),
        Pids.FUEL_RAIL_PRESSURE to (0.0 to 25000.0),
        Pids.BAROMETRIC to (60.0 to 110.0),
        Pids.CAT_TEMP_B1S1 to (0.0 to 1000.0),
        Pids.CAT_TEMP_B2S1 to (0.0 to 1000.0),
        Pids.MODULE_VOLTAGE to (10.0 to 16.0),
        Pids.LAMBDA_COMMANDED to (0.6 to 1.4),
        Pids.RUN_TIME to (0.0 to 65535.0),
        Pids.DISTANCE_WITH_MIL to (0.0 to 65535.0),
        Pids.DISTANCE_SINCE_CLEAR to (0.0 to 65535.0),
        Pids.WARMUPS_SINCE_CLEAR to (0.0 to 255.0),
        Pids.TIME_WITH_MIL to (0.0 to 3_932_100.0),
        Pids.TIME_SINCE_CLEAR to (0.0 to 3_932_100.0),
        Pids.FUEL_RATE to (0.0 to 60.0),
        Pids.STFT_B1 to (-25.0 to 25.0),
        Pids.LTFT_B1 to (-25.0 to 25.0),
        Pids.STFT_B2 to (-25.0 to 25.0),
        Pids.LTFT_B2 to (-25.0 to 25.0),
    )

    private fun defaultRange(quantity: Quantity): Pair<Double, Double> = when (quantity) {
        Quantity.TEMPERATURE -> -40.0 to 150.0
        Quantity.PERCENT -> 0.0 to 100.0
        else -> 0.0 to 100.0
    }

    private fun decimalsFor(pid: Pid): Int = when (pid.quantity) {
        Quantity.VOLTAGE, Quantity.RATIO -> 2
        Quantity.MASS_FLOW, Quantity.FUEL_RATE, Quantity.ANGLE -> 1
        Quantity.PERCENT -> if (pid.pid in fastPids) 1 else 0
        else -> 0
    }

    private val shortNames = mapOf(
        Pids.ENGINE_LOAD to "Load",
        Pids.COOLANT_TEMP to "Coolant",
        Pids.STFT_B1 to "STFT B1",
        Pids.LTFT_B1 to "LTFT B1",
        Pids.STFT_B2 to "STFT B2",
        Pids.LTFT_B2 to "LTFT B2",
        Pids.INTAKE_MAP to "MAP",
        Pids.RPM to "RPM",
        Pids.SPEED to "Speed",
        Pids.TIMING_ADVANCE to "Timing",
        Pids.INTAKE_AIR_TEMP to "IAT",
        Pids.MAF to "MAF",
        Pids.THROTTLE to "Throttle",
        Pids.FUEL_LEVEL to "Fuel",
        Pids.BAROMETRIC to "Baro",
        Pids.AMBIENT_TEMP to "Ambient",
        Pids.OIL_TEMP to "Oil",
        Pids.MODULE_VOLTAGE to "ECU volts",
        Pids.CAT_TEMP_B1S1 to "Cat B1",
        Pids.CAT_TEMP_B2S1 to "Cat B2",
        Pids.PEDAL_D to "Pedal",
        Pids.FUEL_RAIL_PRESSURE to "Rail",
        Pids.FUEL_RATE to "Fuel rate",
    )

    private val pidSignals: List<Signal> = Pids.all.map { pid ->
        val (min, max) = ranges[pid.pid] ?: defaultRange(pid.quantity)
        Signal(
            id = pidId(pid.pid),
            name = pid.name,
            shortName = shortNames[pid.pid] ?: pid.name,
            quantity = pid.quantity,
            min = min,
            max = max,
            decimals = decimalsFor(pid),
            fast = pid.pid in fastPids,
            pids = setOf(pid.pid),
        ) { it.pids[pid.pid] }
    }

    private val derived: List<Signal> = listOf(
        Signal(
            id = BOOST,
            name = "Boost pressure (manifold minus barometric)",
            shortName = "Boost",
            quantity = Quantity.PRESSURE,
            min = -100.0,
            max = 150.0,
            decimals = 0,
            fast = true,
            pids = setOf(Pids.INTAKE_MAP, Pids.BAROMETRIC),
        ) { inputs ->
            val map = inputs.pids[Pids.INTAKE_MAP] ?: return@Signal null
            val baro = inputs.pids[Pids.BAROMETRIC] ?: return@Signal null
            map - baro
        },
        Signal(
            id = BATTERY,
            name = "Battery voltage (OBD port)",
            shortName = "Battery",
            quantity = Quantity.VOLTAGE,
            min = 10.0,
            max = 16.0,
            decimals = 1,
            fast = false,
            pids = emptySet(),
            needsBattery = true,
        ) { it.batteryVoltage },
        Signal(
            id = FUEL_ECONOMY,
            name = "Instant fuel economy (estimated from MAF)",
            shortName = "Economy",
            quantity = Quantity.FUEL_ECONOMY,
            min = 0.0,
            max = 30.0,
            decimals = 1,
            fast = true,
            pids = setOf(Pids.MAF, Pids.SPEED),
        ) { inputs ->
            val maf = inputs.pids[Pids.MAF] ?: return@Signal null
            val speed = inputs.pids[Pids.SPEED] ?: return@Signal null
            if (speed < 5.0) return@Signal null // meaningless when (nearly) stationary
            val litresPerHour = maf / GASOLINE_AFR * 3600.0 / GASOLINE_DENSITY_G_PER_L
            litresPerHour / speed * 100.0
        },
    )

    val all: List<Signal> = derived + pidSignals

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Signal? = byId[id]

    /** Signals the connected car can provide, given its supported mode 01 PIDs. */
    fun available(supportedPids: Set<Int>): List<Signal> = all.filter { supportedPids.containsAll(it.pids) }

    /** Sensible SQ5 dashboard: boost first, as that is what most owners want to watch. */
    val defaultDashboard: List<String> = listOf(
        BOOST,
        pidId(Pids.RPM),
        pidId(Pids.SPEED),
        pidId(Pids.COOLANT_TEMP),
        pidId(Pids.OIL_TEMP),
        pidId(Pids.INTAKE_AIR_TEMP),
        BATTERY,
        pidId(Pids.THROTTLE),
        pidId(Pids.ENGINE_LOAD),
        pidId(Pids.TIMING_ADVANCE),
        pidId(Pids.FUEL_LEVEL),
        FUEL_ECONOMY,
    )
}
