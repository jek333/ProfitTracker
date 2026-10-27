package com.jek.sq5monitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.core.obd.Quantity
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.core.obd.Units
import com.jek.sq5monitor.data.RefreshRate
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val units = settings.units
    val t = settings.thresholds

    Column {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Units") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UnitSystem.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = units == option,
                            onClick = { viewModel.setUnits(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, UnitSystem.entries.size),
                        ) { Text(if (option == UnitSystem.IMPERIAL) "°F · mph · psi" else "°C · km/h · kPa") }
                    }
                }
            }

            SectionCard(title = "Update speed", subtitle = "Fast polls as quickly as the adapter allows; slower settings reduce adapter and phone load.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RefreshRate.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = settings.refreshRate == option,
                            onClick = { viewModel.setRefreshRate(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, RefreshRate.entries.size),
                        ) { Text(option.label) }
                    }
                }
            }

            SectionCard(title = "Display") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep screen on while connected", Modifier.weight(1f))
                    Switch(checked = settings.keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
                }
            }

            SectionCard(title = "Alerts", subtitle = "You get a notification when a limit is crossed, even with the app in the background.") {
                Stepper("Coolant above", t.coolantMaxC, 2.0, 95.0..125.0, Quantity.TEMPERATURE, units) { viewModel.setThresholds(t.copy(coolantMaxC = it)) }
                Stepper("Oil above", t.oilMaxC, 2.0, 100.0..150.0, Quantity.TEMPERATURE, units) { viewModel.setThresholds(t.copy(oilMaxC = it)) }
                Stepper("Intake air above", t.intakeAirMaxC, 5.0, 30.0..90.0, Quantity.TEMPERATURE, units) { viewModel.setThresholds(t.copy(intakeAirMaxC = it)) }
                Stepper("Battery (engine off) below", t.batteryMinEngineOff, 0.1, 11.0..12.6, Quantity.VOLTAGE, units) { viewModel.setThresholds(t.copy(batteryMinEngineOff = it)) }
                Stepper("Charging (engine on) below", t.batteryMinCharging, 0.1, 12.0..14.0, Quantity.VOLTAGE, units) { viewModel.setThresholds(t.copy(batteryMinCharging = it)) }
                TextButton(onClick = viewModel::resetThresholds) { Text("Restore defaults") }
            }

            SectionCard(title = "Drive logs") {
                Text("CSV recordings of your dashboard gauges.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenLogs) { Text("Open drive logs") }
            }

            SectionCard(title = "About") {
                Text(
                    "SQ5 Monitor reads live data and fault memory through an ELM327-compatible OBD-II adapter. " +
                        "It is read-only apart from clearing fault codes; it does not change coding or adaptations. " +
                        "Not affiliated with Audi AG or OBDeleven. Do not operate the app while driving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Edits a canonical value (°C, V) while displaying it in the user's units. */
@Composable
private fun Stepper(
    label: String,
    value: Double,
    step: Double,
    range: ClosedFloatingPointRange<Double>,
    quantity: Quantity,
    units: UnitSystem,
    onChange: (Double) -> Unit,
) {
    val decimals = if (quantity == Quantity.VOLTAGE) 1 else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(snap(value - step, range)) }) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
        }
        Text(
            Units.display(value, quantity, units, decimals).toString(),
            Modifier.width(76.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(snap(value + step, range)) }) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label")
        }
    }
}

/** Rounds to one decimal (avoids 11.899999 from repeated 0.1 steps) and clamps to [range]. */
private fun snap(value: Double, range: ClosedFloatingPointRange<Double>): Double =
    (Math.round(value * 10) / 10.0).coerceIn(range.start, range.endInclusive)
