package com.jek.sq5monitor.core.log

import com.jek.sq5monitor.core.monitor.LiveSample
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.core.obd.Units
import java.io.Writer
import java.time.Instant

/**
 * Writes live samples as CSV (one column per signal, values in the user's units) so drives can be
 * graphed in a spreadsheet or tools such as Datazap / MegaLogViewer.
 */
class CsvLog(
    private val writer: Writer,
    private val signals: List<Signal>,
    private val units: UnitSystem,
) {
    private var startMs: Long? = null

    fun writeHeader() {
        val columns = listOf("timestamp", "elapsed_s") + signals.map { signal ->
            val unit = Units.display(0.0, signal.quantity, units, signal.decimals).unit
            escape(if (unit.isEmpty()) signal.shortName else "${signal.shortName} ($unit)")
        }
        writer.write(columns.joinToString(",") + "\n")
    }

    fun append(sample: LiveSample) {
        val start = startMs ?: sample.timestampMs.also { startMs = it }
        val cells = mutableListOf(
            Instant.ofEpochMilli(sample.timestampMs).toString(),
            "%.3f".format(java.util.Locale.US, (sample.timestampMs - start) / 1000.0),
        )
        signals.forEach { signal ->
            cells += sample.values[signal.id]?.let { Units.display(it, signal.quantity, units, signal.decimals).text } ?: ""
        }
        writer.write(cells.joinToString(",") + "\n")
    }

    fun flush() = writer.flush()

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
