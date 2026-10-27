package com.jek.sq5monitor.core.sim

import com.jek.sq5monitor.core.obd.Pids
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A plausible 2018 SQ5 (3.0 TFSI) driving loop used by demo mode and tests: idle, a hard pull
 * through the gears, motorway cruise, coast down, idle - repeating every 60 seconds.
 */
internal class SimulatedCar(private val startMs: Long) {

    /** Engine snapshot in canonical units. */
    data class State(
        val rpm: Double,
        val speedKmh: Double,
        val throttlePct: Double,
        val mapKpa: Double,
        val baroKpa: Double,
        val coolantC: Double,
        val oilC: Double,
        val intakeAirC: Double,
        val ambientC: Double,
        val timingDeg: Double,
        val mafGs: Double,
        val loadPct: Double,
        val fuelLevelPct: Double,
        val batteryV: Double,
        val runTimeS: Double,
        val stft: Double,
        val ltft: Double,
        val catalystC: Double,
    )

    fun state(nowMs: Long): State {
        val t = max(0.0, (nowMs - startMs) / 1000.0)
        val p = t % CYCLE_S
        val wobble = sin(t * 2.3) * 0.5 + sin(t * 5.1) * 0.5 // deterministic "noise" in [-1, 1]

        val speed: Double
        val rpm: Double
        val throttle: Double
        val map: Double
        when {
            p < 8 -> { speed = 0.0; rpm = 680 + wobble * 15; throttle = 0.0; map = 33 + wobble }
            p < 16 -> {
                val f = (p - 8) / 8 // 0..1 through the pull
                speed = 105 * (1 - (1 - f) * (1 - f))
                val gearPhase = (f * 3.0) % 1.0 // three upshifts
                rpm = 2800 + gearPhase * 3400
                throttle = 88 + wobble * 2
                map = 101 + min(1.0, f * 4) * 115 + wobble * 3 // ~16 psi of boost
            }
            p < 40 -> { speed = 105 + wobble * 2; rpm = 1950 + wobble * 30; throttle = 17 + wobble * 2; map = 58 + wobble * 3 }
            p < 50 -> {
                val f = (p - 40) / 10
                speed = 105 * (1 - f)
                rpm = 700 + (1950 - 700) * (1 - f)
                throttle = 0.0
                map = 26.0
            }
            else -> { speed = 0.0; rpm = 690 + wobble * 15; throttle = 0.0; map = 33 + wobble }
        }
        val baro = 101.0
        val coolant = 62 + 30 * (1 - exp(-t / 150))
        val oil = 55 + 45 * (1 - exp(-t / 300)) + (if (p in 8.0..16.0) 3.0 else 0.0)
        val ambient = 21.0
        val intakeAir = ambient + 9 + (if (p in 8.0..20.0) 14.0 * sin((p - 8) / 12 * PI) else 0.0)
        val maf = DISPLACEMENT_L * rpm / 120.0 * (map / baro) * AIR_DENSITY_G_PER_L * VOLUMETRIC_EFFICIENCY
        val load = min(100.0, map / baro * 100 * 0.85)
        val timing = when {
            throttle > 50 -> 14 + wobble * 2
            speed > 0 && throttle > 0 -> 30 + wobble * 2
            else -> 11 + wobble
        }
        return State(
            rpm = rpm,
            speedKmh = max(0.0, speed),
            throttlePct = max(0.0, throttle),
            mapKpa = map,
            baroKpa = baro,
            coolantC = coolant,
            oilC = oil,
            intakeAirC = intakeAir,
            ambientC = ambient,
            timingDeg = timing,
            mafGs = maf,
            loadPct = load,
            fuelLevelPct = max(5.0, 63 - t / 600),
            batteryV = 14.1 + wobble * 0.05,
            runTimeS = t,
            stft = wobble * 3,
            ltft = 2.3,
            catalystC = 380 + 200 * (1 - exp(-t / 120)) + throttle,
        )
    }

    /** Encodes [pid] as J1979 data bytes (the inverse of [Pids] decoding). */
    fun encode(pid: Int, s: State, milOn: Boolean, dtcCount: Int, readinessIncomplete: Int): IntArray? {
        fun byte(v: Double) = v.toInt().coerceIn(0, 255)
        fun word(v: Double): IntArray { val w = v.toInt().coerceIn(0, 0xFFFF); return intArrayOf(w shr 8, w and 0xFF) }
        fun pct(v: Double) = intArrayOf(byte(v * 255 / 100 + 0.5))
        fun temp(v: Double) = intArrayOf(byte(v + 40 + 0.5))
        fun trim(v: Double) = intArrayOf(byte(v * 128 / 100 + 128 + 0.5))
        return when (pid) {
            Pids.MONITOR_STATUS -> intArrayOf((if (milOn) 0x80 else 0) or dtcCount.coerceAtMost(0x7F), 0x07, READINESS_SUPPORTED, readinessIncomplete)
            Pids.ENGINE_LOAD -> pct(s.loadPct)
            Pids.COOLANT_TEMP -> temp(s.coolantC)
            Pids.STFT_B1, Pids.STFT_B2 -> trim(s.stft)
            Pids.LTFT_B1, Pids.LTFT_B2 -> trim(s.ltft)
            Pids.INTAKE_MAP -> intArrayOf(byte(s.mapKpa + 0.5))
            Pids.RPM -> word(s.rpm * 4)
            Pids.SPEED -> intArrayOf(byte(s.speedKmh + 0.5))
            Pids.TIMING_ADVANCE -> intArrayOf(byte((s.timingDeg + 64) * 2))
            Pids.INTAKE_AIR_TEMP -> temp(s.intakeAirC)
            Pids.MAF -> word(s.mafGs * 100)
            Pids.THROTTLE -> pct(s.throttlePct * 0.82 + 14) // throttle plate never reads 0 %
            Pids.RUN_TIME -> word(s.runTimeS)
            Pids.DISTANCE_WITH_MIL -> word(if (milOn) 37.0 else 0.0)
            Pids.FUEL_LEVEL -> pct(s.fuelLevelPct)
            Pids.WARMUPS_SINCE_CLEAR -> intArrayOf(if (readinessIncomplete == READINESS_SUPPORTED) 0 else 41)
            Pids.DISTANCE_SINCE_CLEAR -> word(if (readinessIncomplete == READINESS_SUPPORTED) 0.0 else 2_418.0)
            Pids.BAROMETRIC -> intArrayOf(byte(s.baroKpa))
            Pids.CAT_TEMP_B1S1 -> word((s.catalystC + 40) * 10)
            Pids.CAT_TEMP_B2S1 -> word((s.catalystC - 6 + 40) * 10)
            Pids.MODULE_VOLTAGE -> word((s.batteryV - 0.2) * 1000)
            Pids.ABSOLUTE_LOAD -> word(s.loadPct * 0.9 * 255 / 100)
            Pids.LAMBDA_COMMANDED -> word((if (s.throttlePct > 80) 0.86 else 1.0) * 32768)
            Pids.RELATIVE_THROTTLE -> pct(s.throttlePct)
            Pids.AMBIENT_TEMP -> temp(s.ambientC)
            Pids.PEDAL_D -> pct(s.throttlePct * 0.8 + 15)
            Pids.PEDAL_E -> pct(s.throttlePct * 0.4 + 7)
            Pids.THROTTLE_COMMANDED -> pct(s.throttlePct)
            Pids.OIL_TEMP -> temp(s.oilC)
            else -> null
        }
    }

    companion object {
        private const val CYCLE_S = 60.0
        private const val DISPLACEMENT_L = 3.0
        private const val AIR_DENSITY_G_PER_L = 1.18
        private const val VOLUMETRIC_EFFICIENCY = 0.9

        /** Catalyst, evaporative system, O2 sensor, O2 sensor heater. */
        const val READINESS_SUPPORTED = 0x65

        /** Mode 01 PIDs the simulated engine ECU supports (besides the bitmap PIDs). */
        val ENGINE_PIDS: Set<Int> = setOf(
            Pids.MONITOR_STATUS, Pids.ENGINE_LOAD, Pids.COOLANT_TEMP, Pids.STFT_B1, Pids.LTFT_B1, Pids.STFT_B2,
            Pids.LTFT_B2, Pids.INTAKE_MAP, Pids.RPM, Pids.SPEED, Pids.TIMING_ADVANCE, Pids.INTAKE_AIR_TEMP,
            Pids.MAF, Pids.THROTTLE, Pids.RUN_TIME, Pids.DISTANCE_WITH_MIL, Pids.FUEL_LEVEL,
            Pids.WARMUPS_SINCE_CLEAR, Pids.DISTANCE_SINCE_CLEAR, Pids.BAROMETRIC, Pids.CAT_TEMP_B1S1,
            Pids.CAT_TEMP_B2S1, Pids.MODULE_VOLTAGE, Pids.ABSOLUTE_LOAD, Pids.LAMBDA_COMMANDED,
            Pids.RELATIVE_THROTTLE, Pids.AMBIENT_TEMP, Pids.PEDAL_D, Pids.PEDAL_E, Pids.THROTTLE_COMMANDED,
            Pids.OIL_TEMP,
        )
    }
}
