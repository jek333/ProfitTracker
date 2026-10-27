package com.jek.sq5monitor.connection

import android.content.Context
import com.jek.sq5monitor.core.elm.Elm327
import com.jek.sq5monitor.core.elm.ElmInfo
import com.jek.sq5monitor.core.link.ElmLink
import com.jek.sq5monitor.core.link.TcpElmLink
import com.jek.sq5monitor.core.obd.ObdClient
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.sim.SimulatedElm327
import com.jek.sq5monitor.core.uds.UdsClient
import com.jek.sq5monitor.data.ConnectionTarget
import com.jek.sq5monitor.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Everything known about the car once connected. */
class VehicleSession(
    val target: ConnectionTarget,
    val elm: Elm327,
    val info: ElmInfo,
    val vin: String?,
    val supportedPids: Set<Int>,
) {
    val obd = ObdClient(elm)
    val uds = UdsClient(elm)
    val availableSignals: List<Signal> = Signals.available(supportedPids)
    val isDemo: Boolean get() = target == ConnectionTarget.Demo
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val target: ConnectionTarget, val step: String) : ConnectionState
    data class Connected(val session: VehicleSession) : ConnectionState
    data class Failed(val target: ConnectionTarget?, val message: String) : ConnectionState
}

/** Owns the single adapter connection for the whole app. */
class ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bluetooth: BluetoothDevices,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow<List<String>>(emptyList())

    /** Recent adapter traffic, newest last, for troubleshooting from the Vehicle screen. */
    val traffic: StateFlow<List<String>> = _traffic.asStateFlow()

    private var connectJob: Job? = null

    val session: VehicleSession? get() = (state.value as? ConnectionState.Connected)?.session

    fun connect(target: ConnectionTarget) {
        if (_state.value is ConnectionState.Connecting) return
        disconnect()
        connectJob = scope.launch {
            var link: ElmLink? = null
            try {
                step(target, "Opening connection to ${target.label}…")
                link = openLink(target)
                val elm = Elm327(link)
                elm.trafficListener = ::recordTraffic
                step(target, "Initialising adapter…")
                val info = elm.initialize()
                step(target, "Reading vehicle identification…")
                val obd = ObdClient(elm)
                val vin = runCatching { obd.readVin() }.getOrNull()
                step(target, "Discovering supported sensors…")
                val supported = obd.supportedPids()
                _state.value = ConnectionState.Connected(VehicleSession(target, elm, info, vin, supported))
                settings.setLastTarget(target)
            } catch (e: CancellationException) {
                link?.let { runCatching { it.close() } }
                throw e
            } catch (e: Exception) {
                link?.let { runCatching { it.close() } }
                _state.value = ConnectionState.Failed(target, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        if (_state.value is ConnectionState.Connecting) _state.value = ConnectionState.Disconnected
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        session?.let { runCatching { it.elm.close() } }
        _state.value = ConnectionState.Disconnected
    }

    /** Called by anything that sees the link die mid-session (timeouts, socket errors). */
    fun reportLinkFailure(session: VehicleSession, error: Throwable) {
        if (this.session !== session) return
        runCatching { session.elm.close() }
        _state.value = ConnectionState.Failed(session.target, "Connection lost: ${error.message ?: error.javaClass.simpleName}")
    }

    fun clearError() {
        if (_state.value is ConnectionState.Failed) _state.value = ConnectionState.Disconnected
    }

    private fun step(target: ConnectionTarget, message: String) {
        _state.value = ConnectionState.Connecting(target, message)
    }

    private suspend fun openLink(target: ConnectionTarget): ElmLink = when (target) {
        ConnectionTarget.Demo -> SimulatedElm327()
        is ConnectionTarget.Wifi -> withContext(Dispatchers.IO) { TcpElmLink.connect(target.host, target.port) }
        is ConnectionTarget.Bluetooth -> {
            if (!bluetooth.isEnabled) throw IllegalStateException("Bluetooth is turned off")
            if (!bluetooth.hasConnectPermission()) throw IllegalStateException("Bluetooth permission is required")
            val device = bluetooth.remoteDevice(target.address)
            if (target.ble) {
                BleLink.connect(context, device, target.name)
            } else {
                withContext(Dispatchers.IO) { ClassicBluetoothLink.connect(device, target.name) }
            }
        }
    }

    private fun recordTraffic(sent: String, received: String) {
        val time = LocalTime.now().format(TIME)
        val reply = received.replace('\r', ' ').replace('\n', ' ').trim()
        _traffic.update { (it + "$time > $sent" + "$time < $reply").takeLast(TRAFFIC_LINES) }
    }

    private companion object {
        const val TRAFFIC_LINES = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
