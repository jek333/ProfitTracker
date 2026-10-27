package com.jek.sq5monitor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.connection.RecordingState
import com.jek.sq5monitor.core.monitor.Alert
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.ui.components.GaugeModel
import com.jek.sq5monitor.ui.gauge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val gauges: List<GaugeModel> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val milOn: Boolean? = null,
    val ratePerSecond: Double = 0.0,
    val adapterLabel: String = "",
    val isDemo: Boolean = false,
    val recording: RecordingState? = null,
    /** Signals the car supports, for the gauge picker. */
    val available: List<Signal> = emptyList(),
    val selected: List<String> = emptyList(),
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val alertSignalIds = mapOf(
        "coolant" to Signals.pidId(Pids.COOLANT_TEMP),
        "oil" to Signals.pidId(Pids.OIL_TEMP),
        "iat" to Signals.pidId(Pids.INTAKE_AIR_TEMP),
        "battery_high" to Signals.BATTERY,
        "battery_charge" to Signals.BATTERY,
        "battery_low" to Signals.BATTERY,
    )

    val state: StateFlow<DashboardUiState> = combine(
        container.connection.state,
        container.hub.latest,
        container.hub.alerts,
        container.settings,
        container.recorder.state,
    ) { connection, sample, alerts, settings, recording ->
        val session = (connection as? ConnectionState.Connected)?.session
            ?: return@combine DashboardUiState()
        val available = session.availableSignals.associateBy { it.id }
        val alerting = alerts.mapNotNull { alertSignalIds[it.id] }.toSet()
        DashboardUiState(
            gauges = settings.dashboard.mapNotNull { available[it] }.map { signal ->
                signal.gauge(sample?.values?.get(signal.id), settings.units, signal.id in alerting)
            },
            alerts = alerts,
            milOn = sample?.milOn,
            ratePerSecond = sample?.ratePerSecond ?: 0.0,
            adapterLabel = session.target.label,
            isDemo = session.isDemo,
            recording = recording,
            available = session.availableSignals,
            selected = settings.dashboard,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private var visibleJob: Job? = null

    /** Called while the dashboard is on screen, so only its signals are polled. */
    fun onVisible() {
        visibleJob?.cancel()
        visibleJob = viewModelScope.launch {
            container.settings.map { it.dashboard }.distinctUntilChanged().collect { ids ->
                container.hub.demand(this@DashboardViewModel, ids)
            }
        }
    }

    fun onHidden() {
        visibleJob?.cancel()
        visibleJob = null
        container.hub.release(this)
    }

    fun setDashboard(ids: List<String>) {
        viewModelScope.launch { container.settingsRepository.setDashboard(ids) }
    }

    fun toggleRecording() {
        if (container.recorder.state.value == null) container.recorder.start() else container.recorder.stop()
    }

    override fun onCleared() = container.hub.release(this)
}
