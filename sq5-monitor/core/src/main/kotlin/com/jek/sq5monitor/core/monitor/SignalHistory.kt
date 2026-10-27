package com.jek.sq5monitor.core.monitor

/** Session statistics and a short history (for sparklines) of one signal. */
class SignalStats(capacity: Int) {
    private val buffer = DoubleArray(capacity)
    private var start = 0
    var size = 0
        private set
    var min = Double.NaN
        private set
    var max = Double.NaN
        private set
    var latest = Double.NaN
        private set

    fun add(value: Double) {
        latest = value
        if (min.isNaN() || value < min) min = value
        if (max.isNaN() || value > max) max = value
        if (size < buffer.size) {
            buffer[(start + size) % buffer.size] = value
            size++
        } else {
            buffer[start] = value
            start = (start + 1) % buffer.size
        }
    }

    /** Oldest first. */
    fun history(): List<Double> = List(size) { buffer[(start + it) % buffer.size] }
}

/** Accumulates [LiveSample]s into per-signal [SignalStats]. Not thread safe; feed from one collector. */
class SignalHistory(private val capacity: Int = 120) {
    private val stats = mutableMapOf<String, SignalStats>()

    fun record(sample: LiveSample) {
        sample.values.forEach { (id, value) -> stats.getOrPut(id) { SignalStats(capacity) }.add(value) }
    }

    operator fun get(id: String): SignalStats? = stats[id]

    fun reset() = stats.clear()

    /** Immutable copy for publishing to UI state. */
    fun snapshot(): Map<String, StatsSnapshot> =
        stats.mapValues { (_, s) -> StatsSnapshot(s.latest, s.min, s.max, s.history()) }
}

data class StatsSnapshot(val latest: Double, val min: Double, val max: Double, val history: List<Double>)
