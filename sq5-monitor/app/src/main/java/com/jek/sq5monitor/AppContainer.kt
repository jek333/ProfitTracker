package com.jek.sq5monitor

import android.content.Context
import com.jek.sq5monitor.connection.BluetoothDevices
import com.jek.sq5monitor.connection.ConnectionManager
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.connection.DriveRecorder
import com.jek.sq5monitor.connection.LiveDataHub
import com.jek.sq5monitor.connection.MonitorService
import com.jek.sq5monitor.data.AppSettings
import com.jek.sq5monitor.data.DriveLogRepository
import com.jek.sq5monitor.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Manual dependency container; one instance per process, owned by [Sq5MonitorApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(appContext)
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val bluetooth = BluetoothDevices(appContext)
    val connection = ConnectionManager(appContext, scope, bluetooth, settingsRepository)
    val hub = LiveDataHub(scope, connection, settings).also { it.start() }
    val logs = DriveLogRepository(appContext)
    val recorder = DriveRecorder(scope, hub, logs, settings, connection)

    init {
        MonitorService.createChannels(appContext)
        scope.launch {
            var wasConnected = false
            connection.state.collect { state ->
                val connected = state is ConnectionState.Connected
                if (connected && !wasConnected) MonitorService.start(appContext)
                wasConnected = connected
            }
        }
    }
}
