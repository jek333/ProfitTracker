package com.jek.sq5monitor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.core.monitor.AlertThresholds
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.data.AppSettings
import com.jek.sq5monitor.data.RefreshRate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings

    fun setUnits(units: UnitSystem) = launch { container.settingsRepository.setUnits(units) }

    fun setRefreshRate(rate: RefreshRate) = launch { container.settingsRepository.setRefreshRate(rate) }

    fun setKeepScreenOn(enabled: Boolean) = launch { container.settingsRepository.setKeepScreenOn(enabled) }

    fun setThresholds(thresholds: AlertThresholds) = launch { container.settingsRepository.setThresholds(thresholds) }

    fun resetThresholds() = setThresholds(AlertThresholds())

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
