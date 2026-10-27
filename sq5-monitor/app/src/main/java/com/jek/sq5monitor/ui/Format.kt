package com.jek.sq5monitor.ui

import com.jek.sq5monitor.core.obd.DisplayValue
import com.jek.sq5monitor.core.obd.Quantity
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.core.obd.Units
import com.jek.sq5monitor.ui.components.GaugeModel

fun Signal.display(value: Double, units: UnitSystem): DisplayValue = Units.display(value, quantity, units, decimals)

fun Signal.unitLabel(units: UnitSystem): String = Units.display(0.0, quantity, units, decimals).unit

/** Builds a gauge in display units. [value] is canonical (as decoded), null while waiting for data. */
fun Signal.gauge(value: Double?, units: UnitSystem, alert: Boolean): GaugeModel {
    // mpg is the inverse of L/100 km, so it gets its own linear scale (more is better).
    if (quantity == Quantity.FUEL_ECONOMY && units == UnitSystem.IMPERIAL) {
        val mpg = value?.let { display(it, units) }
        return GaugeModel(id, shortName, mpg?.text, "mpg", ((mpg?.value ?: 0.0) / MAX_MPG).toFloat().coerceIn(0f, 1f), "0", MAX_MPG.toInt().toString(), alert)
    }
    val shown = value?.let { display(it, units) }
    return GaugeModel(
        id = id,
        label = shortName,
        valueText = shown?.text,
        unit = unitLabel(units),
        fraction = value?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f,
        minText = Units.display(min, quantity, units, 0).text,
        maxText = Units.display(max, quantity, units, 0).text,
        alert = alert,
    )
}

private const val MAX_MPG = 40.0
