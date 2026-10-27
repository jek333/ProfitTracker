package com.jek.sq5monitor.ui.diagnose

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.core.obd.EcuDtcs
import com.jek.sq5monitor.core.obd.MonitorStatus
import com.jek.sq5monitor.core.uds.ModuleScanResult
import com.jek.sq5monitor.core.uds.UdsDtc
import com.jek.sq5monitor.core.uds.VagModule
import com.jek.sq5monitor.core.uds.VagModules
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.ConfirmDialog
import com.jek.sq5monitor.ui.components.InfoRow
import com.jek.sq5monitor.ui.components.SectionCard
import com.jek.sq5monitor.ui.components.StatusDot
import com.jek.sq5monitor.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(topBarActions: @Composable () -> Unit) {
    val viewModel = appViewModel { DiagnoseViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            TopAppBar(
                title = { Text("Diagnose") },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "SQ5 diagnostic report")
                            .putExtra(Intent.EXTRA_TEXT, viewModel.buildReport())
                        context.startActivity(Intent.createChooser(send, "Share report"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share report") }
                    topBarActions()
                },
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Engine (OBD-II)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All modules") })
            }
            when (tab) {
                0 -> ObdTab(state.obd, onRead = viewModel::readObdCodes, onClear = viewModel::clearObdCodes)
                else -> ModulesTab(
                    state.scan,
                    busyModule = state.busyModule,
                    onScan = viewModel::startScan,
                    onCancel = viewModel::cancelScan,
                    onClear = viewModel::clearModule,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ObdTab(state: ObdCodesState, onRead: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!state.loaded && !state.loading) onRead() }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRead, enabled = !state.loading) { Text("Read codes") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = !state.loading && state.loaded) { Text("Clear codes") }
                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        state.monitorStatus?.let { item { ReadinessCard(it) } }
        if (state.loaded && state.codes.isEmpty()) {
            item { NoFaultsCard("No stored, pending or permanent engine/transmission codes.") }
        }
        items(state.codes, key = { "${it.ecuId}-${it.kind}" }) { CodesCard(it) }
        item {
            Text(
                "Generic OBD-II codes from the engine and transmission. Use \"All modules\" to read the " +
                    "manufacturer fault memory of every control unit, like OBDeleven or VCDS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear engine codes?",
            message = "This turns off the check engine light and erases freeze-frame data. It also resets the " +
                "emissions readiness monitors, so the car will fail an inspection until it has been driven for a " +
                "few days. Do this with the engine off and ignition on. If the fault is still present the code will return.",
            confirmLabel = "Clear",
            onConfirm = onClear,
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ReadinessCard(status: MonitorStatus) {
    SectionCard(
        title = "Check engine light: " + if (status.milOn) "ON" else "off",
        subtitle = if (status.incomplete.isEmpty()) "All readiness monitors complete" else "${status.incomplete.size} readiness monitor(s) not yet complete",
    ) {
        status.monitors.forEach { monitor ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (monitor.complete) StatusColors.ok else StatusColors.warning)
                Spacer(Modifier.width(8.dp))
                Text(monitor.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (monitor.complete) "Complete" else "Incomplete",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (monitor.complete) StatusColors.ok else StatusColors.warning,
                )
            }
        }
    }
}

@Composable
private fun CodesCard(group: EcuDtcs) {
    SectionCard(title = "${group.ecuName} · ${group.kind.label}") {
        group.codes.forEach { dtc ->
            Column {
                Text(dtc.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = StatusColors.warning)
                Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NoFaultsCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.ok.copy(alpha = 0.12f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusColors.ok)
            Spacer(Modifier.width(12.dp))
            Text(text)
        }
    }
}

@Composable
private fun ModulesTab(
    state: ScanState,
    busyModule: String?,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onClear: (VagModule) -> Unit,
) {
    var showMissing by rememberSaveable { mutableStateOf(false) }
    var confirmModule by remember { mutableStateOf<VagModule?>(null) }
    val results = VagModules.all.mapNotNull { state.results[it.address] }
    val visible = results.filter { showMissing || it !is ModuleScanResult.NotFound }
    val faults = results.filterIsInstance<ModuleScanResult.Found>().sumOf { it.dtcs.size }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.running) {
                        OutlinedButton(onClick = onCancel) { Text("Stop scan") }
                    } else {
                        Button(onClick = onScan) { Text(if (results.isEmpty()) "Scan all modules" else "Scan again") }
                    }
                    FilterChip(selected = showMissing, onClick = { showMissing = !showMissing }, label = { Text("Show not fitted") })
                }
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Scanning ${state.current?.label ?: "…"} (${state.done}/${state.total})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (results.isNotEmpty()) {
                    val found = results.count { it is ModuleScanResult.Found }
                    Text("$found modules responded · $faults fault(s)", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "Reads the fault memory and part numbers of each control module through the gateway. " +
                            "Switch the ignition on (engine can be off). A full scan takes about a minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(visible, key = { it.module.address }) { result ->
            ModuleCard(result, busy = busyModule == result.module.address, onClear = { confirmModule = result.module })
        }
    }

    confirmModule?.let { module ->
        ConfirmDialog(
            title = "Clear faults in ${module.address}?",
            message = "Erases the fault memory of ${module.name}. Faults that are still present will come back immediately.",
            confirmLabel = "Clear",
            onConfirm = { onClear(module) },
            onDismiss = { confirmModule = null },
        )
    }
}

@Composable
private fun ModuleCard(result: ModuleScanResult, busy: Boolean, onClear: () -> Unit) {
    var expanded by rememberSaveable(result.module.address) { mutableStateOf(false) }
    val (statusText, color) = when (result) {
        is ModuleScanResult.NotFound -> "Not fitted / no response" to StatusColors.inactive
        is ModuleScanResult.Failed -> "Error" to StatusColors.critical
        is ModuleScanResult.Found -> when {
            result.dtcError != null -> "Faults unreadable" to StatusColors.warning
            result.dtcs.isEmpty() -> "OK" to StatusColors.ok
            else -> "${result.dtcs.size} fault(s)" to StatusColors.warning
        }
    }
    val expandable = result is ModuleScanResult.Found || result is ModuleScanResult.Failed

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = expandable) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(result.module.address, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.module.name, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color)
                        Spacer(Modifier.width(6.dp))
                        Text(statusText, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (expandable) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    when (result) {
                        is ModuleScanResult.Failed -> Text(result.error, color = StatusColors.critical)
                        is ModuleScanResult.Found -> {
                            result.identification.forEach { (label, value) -> InfoRow(label, value) }
                            result.dtcError?.let { Text("Could not read faults: $it", color = StatusColors.warning) }
                            result.dtcs.forEach { DtcRow(it) }
                            if (result.dtcs.isNotEmpty()) {
                                TextButton(onClick = onClear, enabled = !busy) { Text("Clear faults") }
                            }
                        }
                        is ModuleScanResult.NotFound -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun DtcRow(dtc: UdsDtc) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dtc.display, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = if (dtc.active) StatusColors.critical else StatusColors.warning)
            Spacer(Modifier.width(8.dp))
            Text(dtc.statusText, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
    }
}
