package com.jek.sq5monitor.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.ui.display
import com.jek.sq5monitor.ui.unitLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

data class LiveRow(
    val id: String,
    val name: String,
    val value: String,
    val unit: String,
    val min: String,
    val max: String,
    val history: List<Double>,
    val onDashboard: Boolean,
)

data class LiveUiState(val rows: List<LiveRow> = emptyList(), val ratePerSecond: Double = 0.0)

class LiveDataViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<LiveUiState> = combine(
        container.connection.state,
        container.hub.stats,
        container.hub.latest,
        container.settings,
    ) { connection, stats, latest, settings ->
        val session = (connection as? ConnectionState.Connected)?.session ?: return@combine LiveUiState()
        val rows = session.availableSignals.sortedBy { it.name }.map { signal ->
            val s = stats[signal.id]
            fun fmt(v: Double?) = v?.takeUnless { it.isNaN() }?.let { signal.display(it, settings.units).text } ?: "—"
            LiveRow(
                id = signal.id,
                name = signal.name,
                value = fmt(latest?.values?.get(signal.id)),
                unit = signal.unitLabel(settings.units),
                min = fmt(s?.min),
                max = fmt(s?.max),
                history = s?.history.orEmpty(),
                onDashboard = signal.id in settings.dashboard,
            )
        }
        LiveUiState(rows, latest?.ratePerSecond ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    private var visibleJob: Job? = null

    /** Polls every signal the car supports while this screen is visible. */
    fun onVisible() {
        visibleJob?.cancel()
        visibleJob = viewModelScope.launch {
            container.connection.state
                .map { (it as? ConnectionState.Connected)?.session?.availableSignals?.map { s -> s.id }.orEmpty() }
                .distinctUntilChanged()
                .collect { container.hub.demand(this@LiveDataViewModel, it) }
        }
    }

    fun onHidden() {
        visibleJob?.cancel()
        visibleJob = null
        container.hub.release(this)
    }

    fun toggleDashboard(id: String) {
        val current = container.settings.value.dashboard
        val updated = if (id in current) current - id else current + id
        if (updated.isEmpty()) return
        viewModelScope.launch { container.settingsRepository.setDashboard(updated) }
    }

    fun resetMinMax() = container.hub.resetStats()

    override fun onCleared() = container.hub.release(this)
}
