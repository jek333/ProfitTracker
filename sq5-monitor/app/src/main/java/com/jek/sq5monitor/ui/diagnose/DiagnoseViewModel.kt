package com.jek.sq5monitor.ui.diagnose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.VehicleSession
import com.jek.sq5monitor.core.elm.ElmTimeoutException
import com.jek.sq5monitor.core.obd.DtcKind
import com.jek.sq5monitor.core.obd.EcuDtcs
import com.jek.sq5monitor.core.obd.MonitorStatus
import com.jek.sq5monitor.core.uds.DiagnosticReport
import com.jek.sq5monitor.core.uds.ModuleScanResult
import com.jek.sq5monitor.core.uds.ModuleScanner
import com.jek.sq5monitor.core.uds.VagModule
import com.jek.sq5monitor.core.uds.VagModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ObdCodesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val codes: List<EcuDtcs> = emptyList(),
    val monitorStatus: MonitorStatus? = null,
)

data class ScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Keyed by module address; in scan order. */
    val results: Map<String, ModuleScanResult> = emptyMap(),
    val current: VagModule? = null,
)

data class DiagnoseUiState(
    val obd: ObdCodesState = ObdCodesState(),
    val scan: ScanState = ScanState(),
    /** One-off message for a snackbar (e.g. "Codes cleared"). */
    val message: String? = null,
    val busyModule: String? = null,
)

class DiagnoseViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DiagnoseUiState())
    val state: StateFlow<DiagnoseUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    private val session: VehicleSession? get() = container.connection.session

    fun readObdCodes() {
        val session = session ?: return
        _state.update { it.copy(obd = it.obd.copy(loading = true)) }
        viewModelScope.launch {
            guarded(session) {
                val codes = DtcKind.entries.flatMap { session.obd.readDtcs(it) }.filter { it.codes.isNotEmpty() }
                val status = session.obd.monitorStatus()
                _state.update { it.copy(obd = ObdCodesState(loading = false, loaded = true, codes = codes, monitorStatus = status)) }
            }
            _state.update { it.copy(obd = it.obd.copy(loading = false)) }
        }
    }

    fun clearObdCodes() {
        val session = session ?: return
        _state.update { it.copy(obd = it.obd.copy(loading = true)) }
        viewModelScope.launch {
            guarded(session) {
                val ok = session.obd.clearDtcs()
                message(if (ok) "Engine codes cleared" else "The car did not confirm clearing the codes")
            }
            readObdCodes()
        }
    }

    fun startScan() {
        val session = session ?: return
        if (scanJob?.isActive == true) return
        val modules = VagModules.all
        _state.update { it.copy(scan = ScanState(running = true, total = modules.size, current = modules.first())) }
        scanJob = viewModelScope.launch {
            guarded(session) {
                ModuleScanner(session.uds).scan(modules).collect { progress ->
                    _state.update {
                        it.copy(
                            scan = it.scan.copy(
                                done = progress.done,
                                results = it.scan.results + (progress.result.module.address to progress.result),
                                current = modules.getOrNull(progress.done),
                            ),
                        )
                    }
                }
            }
            _state.update { it.copy(scan = it.scan.copy(running = false, current = null)) }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.update { it.copy(scan = it.scan.copy(running = false, current = null)) }
    }

    fun clearModule(module: VagModule) {
        val session = session ?: return
        _state.update { it.copy(busyModule = module.address) }
        viewModelScope.launch {
            guarded(session) {
                session.uds.withModule(module) { clearDtcs() }
                val rescanned = ModuleScanner(session.uds).scanModule(module)
                _state.update { it.copy(scan = it.scan.copy(results = it.scan.results + (module.address to rescanned))) }
                val remaining = (rescanned as? ModuleScanResult.Found)?.dtcs?.size ?: 0
                message(
                    if (remaining == 0) "${module.label}: faults cleared"
                    else "${module.label}: $remaining fault(s) came straight back - they are still active",
                )
            }
            _state.update { it.copy(busyModule = null) }
        }
    }

    fun buildReport(): String {
        val session = session
        val current = _state.value
        return DiagnosticReport.build(
            vin = session?.vin,
            adapter = session?.target?.label,
            modules = VagModules.all.mapNotNull { current.scan.results[it.address] },
            obdCodes = current.obd.codes,
            monitorStatus = current.obd.monitorStatus,
        )
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun message(text: String) = _state.update { it.copy(message = text) }

    /** Runs [block], turning failures into a message and dead links into a disconnect. */
    private suspend fun guarded(session: VehicleSession, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ElmTimeoutException) {
            container.connection.reportLinkFailure(session, e)
        } catch (e: Exception) {
            message(e.message ?: "Something went wrong")
        }
    }
}
