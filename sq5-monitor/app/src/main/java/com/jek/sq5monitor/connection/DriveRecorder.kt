package com.jek.sq5monitor.connection

import com.jek.sq5monitor.core.log.CsvLog
import com.jek.sq5monitor.data.AppSettings
import com.jek.sq5monitor.data.DriveLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.Writer

data class RecordingState(val fileName: String, val rows: Int, val startedAtMs: Long)

/** Records the dashboard signals to a CSV file while enabled. Stops automatically on disconnect. */
class DriveRecorder(
    private val scope: CoroutineScope,
    private val hub: LiveDataHub,
    private val logs: DriveLogRepository,
    private val settings: StateFlow<AppSettings>,
    private val connection: ConnectionManager,
) {
    private val _state = MutableStateFlow<RecordingState?>(null)
    val state: StateFlow<RecordingState?> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            connection.state.collect { if (it !is ConnectionState.Connected) stop() }
        }
    }

    @Synchronized
    fun start() {
        if (job != null) return
        val session = connection.session ?: return
        val available = session.availableSignals.associateBy { it.id }
        val signals = settings.value.dashboard.mapNotNull { available[it] }
        if (signals.isEmpty()) return

        val file = logs.newLogFile()
        val startedAt = System.currentTimeMillis()
        _state.value = RecordingState(file.name, 0, startedAt)
        hub.demand(this, signals.map { it.id })
        job = scope.launch(Dispatchers.IO) {
            var writer: Writer? = null
            try {
                writer = file.bufferedWriter()
                val log = CsvLog(writer, signals, settings.value.units)
                log.writeHeader()
                var rows = 0
                var lastTimestamp = 0L
                hub.latest.filterNotNull().collect { sample ->
                    if (sample.timestampMs == lastTimestamp) return@collect
                    lastTimestamp = sample.timestampMs
                    log.append(sample)
                    rows++
                    if (rows % FLUSH_EVERY == 0) log.flush()
                    _state.value = RecordingState(file.name, rows, startedAt)
                }
            } finally {
                runCatching { writer?.close() }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        hub.release(this)
        _state.value = null
    }

    private companion object {
        const val FLUSH_EVERY = 20
    }
}
