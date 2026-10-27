package com.jek.sq5monitor.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.AdapterDevice
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.core.link.TcpElmLink
import com.jek.sq5monitor.data.ConnectionTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdapterKind { BLUETOOTH, WIFI, DEMO }

data class ConnectUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val lastTarget: ConnectionTarget? = null,
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val hasConnectPermission: Boolean = false,
    val hasScanPermission: Boolean = false,
    val paired: List<AdapterDevice> = emptyList(),
    val nearby: List<AdapterDevice> = emptyList(),
    val scanning: Boolean = false,
    val scanError: String? = null,
)

class ConnectViewModel(private val container: AppContainer) : ViewModel() {
    private val bluetooth = container.bluetooth
    private val devices = MutableStateFlow(DeviceState())
    private var scanJob: Job? = null

    private data class DeviceState(
        val enabled: Boolean = true,
        val permission: Boolean = false,
        val scanPermission: Boolean = false,
        val paired: List<AdapterDevice> = emptyList(),
        val nearby: List<AdapterDevice> = emptyList(),
        val scanning: Boolean = false,
        val scanError: String? = null,
    )

    val state: StateFlow<ConnectUiState> = combine(container.connection.state, container.settings, devices) { connection, settings, d ->
        ConnectUiState(
            connection = connection,
            lastTarget = settings.lastTarget,
            bluetoothSupported = bluetooth.isSupported,
            bluetoothEnabled = d.enabled,
            hasConnectPermission = d.permission,
            hasScanPermission = d.scanPermission,
            paired = d.paired,
            nearby = d.nearby.filter { n -> d.paired.none { it.address == n.address } },
            scanning = d.scanning,
            scanError = d.scanError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectUiState())

    /** Runtime permissions to request for listing, connecting and scanning. */
    val bluetoothPermissions: Array<String> get() = bluetooth.requiredPermissions

    val wifiDefaults = ConnectionTarget.Wifi(TcpElmLink.DEFAULT_HOST, TcpElmLink.DEFAULT_PORT)

    /** Re-reads Bluetooth state; call after permission grants or returning from system settings. */
    fun refreshDevices() {
        devices.update {
            it.copy(
                enabled = bluetooth.isEnabled,
                permission = bluetooth.hasConnectPermission(),
                scanPermission = bluetooth.hasScanPermission(),
                paired = bluetooth.bondedDevices(),
            )
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        devices.update { it.copy(scanning = true, scanError = null, nearby = emptyList()) }
        scanJob = viewModelScope.launch {
            val collector = launch {
                bluetooth.scanLe()
                    .catch { e -> devices.update { it.copy(scanError = e.message) } }
                    .collect { found ->
                        devices.update { s ->
                            if (s.nearby.any { it.address == found.address }) s
                            else s.copy(nearby = (s.nearby + found).sortedByDescending { it.likelyObd })
                        }
                    }
            }
            delay(SCAN_DURATION_MS)
            collector.cancel()
            devices.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        devices.update { it.copy(scanning = false) }
    }

    fun connect(target: ConnectionTarget) {
        stopScan()
        container.connection.connect(target)
    }

    fun connect(device: AdapterDevice) = connect(ConnectionTarget.Bluetooth(device.address, device.name, device.ble))

    fun cancel() = container.connection.cancelConnect()

    fun dismissError() = container.connection.clearError()

    override fun onCleared() = stopScan()

    private companion object {
        const val SCAN_DURATION_MS = 12_000L
    }
}
