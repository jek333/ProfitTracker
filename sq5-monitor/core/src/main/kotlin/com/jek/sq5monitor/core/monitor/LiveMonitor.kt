package com.jek.sq5monitor.core.monitor

import com.jek.sq5monitor.core.elm.Elm327
import com.jek.sq5monitor.core.obd.ObdClient
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.core.obd.SignalInputs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One polling cycle's worth of data. Values are in canonical units, keyed by [Signal.id]. */
data class LiveSample(
    val timestampMs: Long,
    val values: Map<String, Double>,
    /** Check engine light state, refreshed together with the slow signals. */
    val milOn: Boolean?,
    /** Achieved refresh rate of the fast signals, for display. */
    val ratePerSecond: Double,
)

/**
 * Polls the car for a set of signals. Fast signals are read every cycle; slow ones (temperatures,
 * fuel level, battery voltage, check engine light) every [slowEvery] cycles, which keeps rpm and
 * boost responsive over Bluetooth adapters that only manage ~10-20 requests per second.
 */
class LiveMonitor(
    private val elm: Elm327,
    private val obd: ObdClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun samples(signals: List<Signal>, minIntervalMs: Long, slowEvery: Int = 5): Flow<LiveSample> = flow {
        val fastPids = signals.filter { it.fast }.flatMap { it.pids }.toSet()
        val slowPids = signals.filterNot { it.fast }.flatMap { it.pids }.toSet() - fastPids
        val needsBattery = signals.any { it.needsBattery }

        val pidValues = mutableMapOf<Int, Double>()
        var battery: Double? = null
        var milOn: Boolean? = null
        var cycle = 0
        var lastCycleStart = clock()
        var rate = 0.0

        while (true) {
            val start = clock()
            val slowCycle = cycle % slowEvery == 0
            val wanted = if (slowCycle) fastPids + slowPids + Pids.MONITOR_STATUS else fastPids
            if (wanted.isNotEmpty()) {
                val raw = obd.readPids(wanted.toList())
                raw[Pids.MONITOR_STATUS]?.let { milOn = it[0] and 0x80 != 0 }
                raw.forEach { (pid, data) ->
                    val definition = Pids[pid] ?: return@forEach
                    if (data.size >= definition.byteCount) pidValues[pid] = definition.decode(data)
                }
            }
            if (needsBattery && slowCycle) battery = elm.batteryVoltage() ?: battery

            val inputs = SignalInputs(pidValues, battery)
            val values = signals.mapNotNull { signal -> signal.compute(inputs)?.let { signal.id to it } }.toMap()

            if (cycle > 0) {
                val elapsed = (start - lastCycleStart).coerceAtLeast(1)
                rate = if (rate == 0.0) 1000.0 / elapsed else rate * 0.8 + 0.2 * (1000.0 / elapsed)
            }
            lastCycleStart = start
            emit(LiveSample(clock(), values, milOn, rate))
            cycle++

            val spent = clock() - start
            if (spent < minIntervalMs) delay(minIntervalMs - spent)
        }
    }
}
