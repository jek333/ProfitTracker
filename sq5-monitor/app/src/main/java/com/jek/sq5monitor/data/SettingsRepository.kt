package com.jek.sq5monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jek.sq5monitor.core.monitor.AlertThresholds
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val UNITS = stringPreferencesKey("units")
        val REFRESH = stringPreferencesKey("refresh_rate")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DASHBOARD = stringPreferencesKey("dashboard")
        val COOLANT_MAX = doublePreferencesKey("coolant_max_c")
        val OIL_MAX = doublePreferencesKey("oil_max_c")
        val IAT_MAX = doublePreferencesKey("iat_max_c")
        val BATTERY_MIN_OFF = doublePreferencesKey("battery_min_off")
        val BATTERY_MIN_CHARGING = doublePreferencesKey("battery_min_charging")
        val TARGET_TYPE = stringPreferencesKey("target_type")
        val TARGET_ADDRESS = stringPreferencesKey("target_address")
        val TARGET_NAME = stringPreferencesKey("target_name")
        val TARGET_PORT = intPreferencesKey("target_port")
        val TARGET_BLE = booleanPreferencesKey("target_ble")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        val defaultThresholds = defaults.thresholds
        AppSettings(
            units = prefs[Keys.UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: defaults.units,
            refreshRate = prefs[Keys.REFRESH]?.let { runCatching { RefreshRate.valueOf(it) }.getOrNull() } ?: defaults.refreshRate,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            dashboard = prefs[Keys.DASHBOARD]?.split(',')?.filter { Signals[it] != null }?.takeIf { it.isNotEmpty() }
                ?: defaults.dashboard,
            thresholds = AlertThresholds(
                coolantMaxC = prefs[Keys.COOLANT_MAX] ?: defaultThresholds.coolantMaxC,
                oilMaxC = prefs[Keys.OIL_MAX] ?: defaultThresholds.oilMaxC,
                intakeAirMaxC = prefs[Keys.IAT_MAX] ?: defaultThresholds.intakeAirMaxC,
                batteryMinEngineOff = prefs[Keys.BATTERY_MIN_OFF] ?: defaultThresholds.batteryMinEngineOff,
                batteryMinCharging = prefs[Keys.BATTERY_MIN_CHARGING] ?: defaultThresholds.batteryMinCharging,
            ),
            lastTarget = readTarget(prefs),
        )
    }

    suspend fun setUnits(units: UnitSystem) = context.dataStore.edit { it[Keys.UNITS] = units.name }

    suspend fun setRefreshRate(rate: RefreshRate) = context.dataStore.edit { it[Keys.REFRESH] = rate.name }

    suspend fun setKeepScreenOn(enabled: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }

    suspend fun setDashboard(signalIds: List<String>) =
        context.dataStore.edit { it[Keys.DASHBOARD] = signalIds.joinToString(",") }

    suspend fun setThresholds(thresholds: AlertThresholds) = context.dataStore.edit {
        it[Keys.COOLANT_MAX] = thresholds.coolantMaxC
        it[Keys.OIL_MAX] = thresholds.oilMaxC
        it[Keys.IAT_MAX] = thresholds.intakeAirMaxC
        it[Keys.BATTERY_MIN_OFF] = thresholds.batteryMinEngineOff
        it[Keys.BATTERY_MIN_CHARGING] = thresholds.batteryMinCharging
    }

    suspend fun setLastTarget(target: ConnectionTarget) = context.dataStore.edit {
        when (target) {
            is ConnectionTarget.Bluetooth -> {
                it[Keys.TARGET_TYPE] = "bluetooth"
                it[Keys.TARGET_ADDRESS] = target.address
                it[Keys.TARGET_NAME] = target.name
                it[Keys.TARGET_BLE] = target.ble
            }
            is ConnectionTarget.Wifi -> {
                it[Keys.TARGET_TYPE] = "wifi"
                it[Keys.TARGET_ADDRESS] = target.host
                it[Keys.TARGET_PORT] = target.port
            }
            ConnectionTarget.Demo -> it[Keys.TARGET_TYPE] = "demo"
        }
    }

    private fun readTarget(prefs: Preferences): ConnectionTarget? = when (prefs[Keys.TARGET_TYPE]) {
        "bluetooth" -> prefs[Keys.TARGET_ADDRESS]?.let { address ->
            ConnectionTarget.Bluetooth(address, prefs[Keys.TARGET_NAME] ?: address, prefs[Keys.TARGET_BLE] ?: false)
        }
        "wifi" -> prefs[Keys.TARGET_ADDRESS]?.let { host -> ConnectionTarget.Wifi(host, prefs[Keys.TARGET_PORT] ?: 35000) }
        "demo" -> ConnectionTarget.Demo
        else -> null
    }
}
