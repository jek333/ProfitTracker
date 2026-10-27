package com.jek.sq5monitor.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.core.obd.Signal
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.AlertBanner
import com.jek.sq5monitor.ui.components.GaugeCard
import com.jek.sq5monitor.ui.components.StatusDot
import com.jek.sq5monitor.ui.theme.StatusColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(topBarActions: @Composable () -> Unit) {
    val viewModel = appViewModel { DashboardViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.onVisible()
        onDispose { viewModel.onHidden() }
    }

    Column {
        TopAppBar(
            title = { Text("Dashboard") },
            actions = {
                IconButton(onClick = viewModel::toggleRecording) {
                    if (state.recording != null) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = StatusColors.critical)
                    } else {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record drive log")
                    }
                }
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Choose gauges") }
                topBarActions()
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { StatusStrip(state) }
            items(state.alerts, key = { "alert-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { AlertBanner(it) }
            items(state.gauges, key = { it.id }) { GaugeCard(it) }
        }
    }

    if (editing) {
        GaugePicker(
            available = state.available,
            selected = state.selected,
            onDismiss = { editing = false },
            onSave = { viewModel.setDashboard(it); editing = false },
        )
    }
}

@Composable
private fun StatusStrip(state: DashboardUiState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = {},
            label = { Text(if (state.isDemo) "Demo mode" else state.adapterLabel, maxLines = 1) },
            leadingIcon = { StatusDot(if (state.isDemo) StatusColors.warning else StatusColors.ok) },
        )
        val mil = state.milOn
        AssistChip(
            onClick = {},
            label = { Text(if (mil == true) "Check engine ON" else if (mil == false) "No MIL" else "MIL …") },
            leadingIcon = { StatusDot(if (mil == true) StatusColors.critical else if (mil == false) StatusColors.ok else StatusColors.inactive) },
        )
        Spacer(Modifier.weight(1f))
        state.recording?.let {
            StatusDot(StatusColors.critical)
            Spacer(Modifier.width(4.dp))
            Text("REC ${it.rows}", style = MaterialTheme.typography.labelMedium, color = StatusColors.critical)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            String.format(Locale.US, "%.1f Hz", state.ratePerSecond),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GaugePicker(
    available: List<Signal>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected.filter { id -> available.any { it.id == id } }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dashboard gauges") },
        text = {
            Column {
                Text(
                    "Gauges appear in the order you tick them. Fewer gauges means faster updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(available, key = { it.id }) { signal ->
                        val checked = signal.id in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (checked) chosen.remove(signal.id) else chosen.add(signal.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(signal.shortName)
                                Text(signal.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
