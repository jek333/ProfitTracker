package com.jek.sq5monitor.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.core.elm.ElmTimeoutException
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.uds.VagModule
import com.jek.sq5monitor.core.uds.VagModules
import com.jek.sq5monitor.core.vehicle.Vin
import com.jek.sq5monitor.core.vehicle.VinInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class DidReadResult(val module: VagModule, val did: Int, val hex: String?, val ascii: String?, val error: String?)

data class VehicleUiState(
    val vin: VinInfo? = null,
    val adapter: String = "",
    val adapterVersion: String = "",
    val protocol: String = "",
    val supportedSensors: Int = 0,
    val batteryVoltage: String? = null,
    val isDemo: Boolean = false,
    val traffic: List<String> = emptyList(),
)

class VehicleViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<VehicleUiState> = combine(
        container.connection.state,
        container.connection.traffic,
        container.hub.latest,
    ) { connection, traffic, sample ->
        val session = (connection as? ConnectionState.Connected)?.session ?: return@combine VehicleUiState()
        VehicleUiState(
            vin = session.vin?.let(Vin::decode),
            adapter = session.target.label,
            adapterVersion = session.info.version,
            protocol = session.info.protocol,
            supportedSensors = session.supportedPids.size,
            batteryVoltage = sample?.values?.get(Signals.BATTERY)?.let { String.format(Locale.US, "%.1f V", it) },
            isDemo = session.isDemo,
            traffic = traffic,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleUiState())

    private val _didResult = MutableStateFlow<DidReadResult?>(null)
    val didResult: StateFlow<DidReadResult?> = _didResult.asStateFlow()

    private val _didBusy = MutableStateFlow(false)
    val didBusy: StateFlow<Boolean> = _didBusy.asStateFlow()

    val modules: List<VagModule> = VagModules.all

    /** Reads any data identifier (hex, e.g. "F187") from a module and shows it as hex and text. */
    fun readDid(module: VagModule, didHex: String) {
        val did = didHex.trim().removePrefix("0x").toIntOrNull(16)
        if (did == null || did !in 0..0xFFFF) {
            _didResult.value = DidReadResult(module, 0, null, null, "Enter a 4-digit hex identifier, e.g. F187")
            return
        }
        val session = container.connection.session ?: return
        _didBusy.value = true
        viewModelScope.launch {
            _didResult.value = try {
                val bytes = session.uds.withModule(module) { readDid(did) }
                DidReadResult(
                    module, did,
                    hex = bytes.joinToString(" ") { "%02X".format(it) },
                    ascii = bytes.map { if (it in 0x20..0x7E) it.toChar() else '·' }.joinToString(""),
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ElmTimeoutException) {
                container.connection.reportLinkFailure(session, e)
                null
            } catch (e: Exception) {
                DidReadResult(module, did, null, null, e.message ?: "Read failed")
            }
            _didBusy.value = false
        }
    }

    fun disconnect() = container.connection.disconnect()
}
