package com.jek.sq5monitor.connection

import com.jek.sq5monitor.core.monitor.Alert
import com.jek.sq5monitor.core.monitor.AlertEvaluator
import com.jek.sq5monitor.core.monitor.LiveMonitor
import com.jek.sq5monitor.core.monitor.LiveSample
import com.jek.sq5monitor.core.monitor.SignalHistory
import com.jek.sq5monitor.core.monitor.StatsSnapshot
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the one live-data poller for the app. Screens (and the drive recorder) declare which signals
 * they need; the hub polls the union, plus a small "watchdog" set so alerts keep working even when
 * no screen is visible.
 */
class LiveDataHub(
    private val scope: CoroutineScope,
    private val connection: ConnectionManager,
    private val settings: StateFlow<AppSettings>,
) {
    private val demands = MutableStateFlow<Map<Any, Set<String>>>(emptyMap())

    private val _latest = MutableStateFlow<LiveSample?>(null)
    val latest: StateFlow<LiveSample?> = _latest.asStateFlow()

    private val _stats = MutableStateFlow<Map<String, StatsSnapshot>>(emptyMap())
    val stats: StateFlow<Map<String, StatsSnapshot>> = _stats.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val history = SignalHistory()

    /** Registers (or replaces) what [owner] wants polled. */
    fun demand(owner: Any, signalIds: Collection<String>) = demands.update { it + (owner to signalIds.toSet()) }

    fun release(owner: Any) = demands.update { it - owner }

    fun resetStats() {
        history.reset()
        _stats.value = emptyMap()
    }

    private data class PollPlan(val session: VehicleSession?, val signalIds: Set<String>, val intervalMs: Long)

    fun start() = scope.launch {
        combine(connection.state, demands, settings) { state, demands, settings ->
            val session = (state as? ConnectionState.Connected)?.session
            PollPlan(session, demands.values.flatten().toSet() + WATCHDOG, settings.refreshRate.minIntervalMs)
        }.distinctUntilChanged().collectLatest { plan ->
            val session = plan.session
            if (session == null) {
                _latest.value = null
                _alerts.value = emptyList()
                resetStats()
                return@collectLatest
            }
            val available = session.availableSignals.associateBy { it.id }
            val signals = plan.signalIds.mapNotNull { available[it] }
            try {
                LiveMonitor(session.elm, session.obd).samples(signals, plan.intervalMs).collect { sample ->
                    _latest.value = sample
                    history.record(sample)
                    _stats.value = history.snapshot()
                    _alerts.value = AlertEvaluator(settings.value.thresholds).evaluate(sample.values, sample.milOn)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connection.reportLinkFailure(session, e)
            }
        }
    }

    private companion object {
        val WATCHDOG = setOf(
            Signals.pidId(Pids.RPM),
            Signals.pidId(Pids.COOLANT_TEMP),
            Signals.pidId(Pids.OIL_TEMP),
            Signals.pidId(Pids.INTAKE_AIR_TEMP),
            Signals.BATTERY,
        )
    }
}
