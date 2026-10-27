package com.jek.sq5monitor.core.obd

import java.util.Locale

/** Physical quantity of a signal; decides how values are converted for display. */
enum class Quantity {
    TEMPERATURE, SPEED, PRESSURE, PERCENT, ROTATION, VOLTAGE, ANGLE, MASS_FLOW, FUEL_RATE,
    FUEL_ECONOMY, DURATION, DISTANCE, RATIO,
}

enum class UnitSystem { METRIC, IMPERIAL }

/** A value converted into the user's unit system, ready for display. */
data class DisplayValue(val value: Double, val unit: String, val decimals: Int) {
    val text: String get() = String.format(Locale.US, "%.${decimals}f", value)
    override fun toString(): String = if (unit.isEmpty()) text else "$text $unit"
}

/**
 * Converts canonical (SI-ish, as decoded from the car) values into display units.
 * Canonical units: °C, km/h, kPa, %, rpm, V, °, g/s, L/h, L/100 km, s, km.
 */
object Units {
    private const val KPA_PER_PSI = 6.894757
    private const val KM_PER_MILE = 1.609344
    private const val LITRES_PER_US_GALLON = 3.785411784
    private const val L100KM_TO_MPG = 235.214583

    fun display(value: Double, quantity: Quantity, system: UnitSystem, decimals: Int): DisplayValue {
        val imperial = system == UnitSystem.IMPERIAL
        return when (quantity) {
            Quantity.TEMPERATURE ->
                if (imperial) DisplayValue(value * 9.0 / 5.0 + 32.0, "°F", decimals) else DisplayValue(value, "°C", decimals)
            Quantity.SPEED ->
                if (imperial) DisplayValue(value / KM_PER_MILE, "mph", decimals) else DisplayValue(value, "km/h", decimals)
            Quantity.PRESSURE ->
                if (imperial) DisplayValue(value / KPA_PER_PSI, "psi", maxOf(decimals, 1)) else DisplayValue(value, "kPa", decimals)
            Quantity.DISTANCE ->
                if (imperial) DisplayValue(value / KM_PER_MILE, "mi", decimals) else DisplayValue(value, "km", decimals)
            Quantity.FUEL_RATE ->
                if (imperial) DisplayValue(value / LITRES_PER_US_GALLON, "gal/h", maxOf(decimals, 2)) else DisplayValue(value, "L/h", decimals)
            Quantity.FUEL_ECONOMY ->
                if (imperial) DisplayValue(if (value > 0) L100KM_TO_MPG / value else 0.0, "mpg", decimals)
                else DisplayValue(value, "L/100km", decimals)
            Quantity.PERCENT -> DisplayValue(value, "%", decimals)
            Quantity.ROTATION -> DisplayValue(value, "rpm", decimals)
            Quantity.VOLTAGE -> DisplayValue(value, "V", decimals)
            Quantity.ANGLE -> DisplayValue(value, "°", decimals)
            Quantity.MASS_FLOW -> DisplayValue(value, "g/s", decimals)
            Quantity.DURATION -> DisplayValue(value, "s", decimals)
            Quantity.RATIO -> DisplayValue(value, "λ", decimals)
        }
    }
}
