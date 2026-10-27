package com.jek.sq5monitor.core.monitor

import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals

enum class AlertSeverity { WARNING, CRITICAL }

data class Alert(val id: String, val severity: AlertSeverity, val title: String, val detail: String)

/**
 * User adjustable alert limits, in canonical units (°C, V).
 * Defaults suit the SQ5's 3.0 TFSI: coolant normally sits at 90-105 °C, oil up to ~120 °C.
 */
data class AlertThresholds(
    val coolantMaxC: Double = 112.0,
    val oilMaxC: Double = 130.0,
    val intakeAirMaxC: Double = 65.0,
    /** Resting battery voltage below this (engine off) means the battery is weak or discharged. */
    val batteryMinEngineOff: Double = 12.0,
    /** Voltage below this with the engine running means the alternator is not charging. */
    val batteryMinCharging: Double = 12.8,
    val batteryMax: Double = 15.2,
)

/** Turns a live snapshot into the alerts the dashboard should show. Pure, so it is easy to test. */
class AlertEvaluator(private val thresholds: AlertThresholds = AlertThresholds()) {

    fun evaluate(values: Map<String, Double>, milOn: Boolean?): List<Alert> = buildList {
        if (milOn == true) {
            add(Alert("mil", AlertSeverity.WARNING, "Check engine light is on", "Open Diagnose to read the stored codes."))
        }
        values[Signals.pidId(Pids.COOLANT_TEMP)]?.let { coolant ->
            if (coolant >= thresholds.coolantMaxC) {
                add(Alert("coolant", AlertSeverity.CRITICAL, "Coolant temperature high",
                    "%.0f °C. Pull over safely and let the engine idle or switch off.".format(coolant)))
            }
        }
        values[Signals.pidId(Pids.OIL_TEMP)]?.let { oil ->
            if (oil >= thresholds.oilMaxC) {
                add(Alert("oil", AlertSeverity.CRITICAL, "Oil temperature high", "%.0f °C. Reduce load.".format(oil)))
            }
        }
        values[Signals.pidId(Pids.INTAKE_AIR_TEMP)]?.let { iat ->
            if (iat >= thresholds.intakeAirMaxC) {
                add(Alert("iat", AlertSeverity.WARNING, "Intake air very hot",
                    "%.0f °C. Expect reduced power (heat soak).".format(iat)))
            }
        }
        values[Signals.BATTERY]?.let { volts ->
            val rpm = values[Signals.pidId(Pids.RPM)]
            val running = rpm != null && rpm > 400
            when {
                volts >= thresholds.batteryMax -> add(Alert("battery_high", AlertSeverity.CRITICAL,
                    "Charging voltage too high", "%.1f V. Possible voltage regulator fault.".format(volts)))
                running && volts < thresholds.batteryMinCharging -> add(Alert("battery_charge", AlertSeverity.WARNING,
                    "Battery not charging", "%.1f V with the engine running. Check the alternator.".format(volts)))
                !running && rpm != null && volts < thresholds.batteryMinEngineOff -> add(Alert("battery_low",
                    AlertSeverity.WARNING, "Battery voltage low", "%.1f V with the engine off.".format(volts)))
            }
        }
    }
}
