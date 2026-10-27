package com.jek.sq5monitor.data

import com.jek.sq5monitor.core.monitor.AlertThresholds
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.UnitSystem

/** How often live data is requested. "Fast" polls back-to-back, as fast as the adapter allows. */
enum class RefreshRate(val label: String, val minIntervalMs: Long) {
    FAST("Fast", 0),
    BALANCED("Balanced", 150),
    ECO("Battery saver", 500),
}

/** The adapter to reconnect to. */
sealed interface ConnectionTarget {
    val label: String

    data class Bluetooth(val address: String, val name: String, val ble: Boolean) : ConnectionTarget {
        override val label: String get() = name
    }

    data class Wifi(val host: String, val port: Int) : ConnectionTarget {
        override val label: String get() = "Wi-Fi $host:$port"
    }

    data object Demo : ConnectionTarget {
        override val label: String get() = "Demo car"
    }
}

data class AppSettings(
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val refreshRate: RefreshRate = RefreshRate.FAST,
    val keepScreenOn: Boolean = true,
    val dashboard: List<String> = Signals.defaultDashboard,
    val thresholds: AlertThresholds = AlertThresholds(),
    val lastTarget: ConnectionTarget? = null,
)
