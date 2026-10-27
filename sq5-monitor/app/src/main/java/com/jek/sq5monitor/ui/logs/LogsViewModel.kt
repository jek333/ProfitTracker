package com.jek.sq5monitor.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.RecordingState
import com.jek.sq5monitor.data.DriveLogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsViewModel(private val container: AppContainer) : ViewModel() {
    private val _logs = MutableStateFlow<List<DriveLogFile>>(emptyList())
    val logs: StateFlow<List<DriveLogFile>> = _logs.asStateFlow()

    val recording: StateFlow<RecordingState?> = container.recorder.state

    init {
        refresh()
        // Refresh when a recording starts or stops so the list reflects the file on disk.
        viewModelScope.launch {
            container.recorder.state.map { it?.fileName }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch { _logs.value = withContext(Dispatchers.IO) { container.logs.list() } }
    }

    fun delete(log: DriveLogFile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.logs.delete(log) }
            refresh()
        }
    }
}
