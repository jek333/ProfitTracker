package com.jek.sq5monitor.ui.connect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.connection.AdapterDevice
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.data.ConnectionTarget
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.SectionCard
import com.jek.sq5monitor.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(onOpenSettings: () -> Unit) {
    val viewModel = appViewModel { ConnectViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(AdapterKind.BLUETOOTH) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshDevices() }
    LaunchedEffect(state.lastTarget) {
        // Open the tab matching the adapter used last time.
        when (state.lastTarget) {
            is ConnectionTarget.Wifi -> kind = AdapterKind.WIFI
            ConnectionTarget.Demo -> kind = AdapterKind.DEMO
            else -> Unit
        }
    }

    Column {
        TopAppBar(
            title = { Text("SQ5 Monitor") },
            actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionStatus(state, onCancel = viewModel::cancel, onDismiss = viewModel::dismissError, onRetry = viewModel::connect)

            val busy = state.connection is ConnectionState.Connecting
            state.lastTarget?.takeIf { !busy }?.let { last ->
                Button(onClick = { viewModel.connect(last) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reconnect to ${last.label}")
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    AdapterKind.BLUETOOTH to "Bluetooth",
                    AdapterKind.WIFI to "Wi-Fi",
                    AdapterKind.DEMO to "Demo",
                )
                options.forEachIndexed { index, (option, label) ->
                    SegmentedButton(
                        selected = kind == option,
                        onClick = { kind = option },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        icon = {
                            Icon(
                                when (option) {
                                    AdapterKind.BLUETOOTH -> Icons.Filled.Bluetooth
                                    AdapterKind.WIFI -> Icons.Filled.Wifi
                                    AdapterKind.DEMO -> Icons.Filled.DirectionsCar
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    ) { Text(label) }
                }
            }

            when (kind) {
                AdapterKind.BLUETOOTH -> BluetoothSection(state, viewModel, enabled = !busy)
                AdapterKind.WIFI -> WifiSection(viewModel.wifiDefaults, enabled = !busy, onConnect = viewModel::connect)
                AdapterKind.DEMO -> DemoSection(enabled = !busy, onStart = { viewModel.connect(ConnectionTarget.Demo) })
            }

            SectionCard(title = "Before you connect") {
                Tip("Plug the adapter into the OBD-II port under the dashboard on the driver's side.")
                Tip("Switch the ignition on. Live data needs the engine running; fault scans work with the engine off.")
                Tip("Bluetooth Classic adapters must be paired in Android's Bluetooth settings first (PIN is usually 1234 or 0000). Bluetooth LE adapters are found with \"Scan\".")
                Tip("Close other OBD apps; an adapter only talks to one app at a time.")
            }
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row {
        Text("•", Modifier.width(16.dp), color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionStatus(
    state: ConnectUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (ConnectionTarget) -> Unit,
) {
    when (val connection = state.connection) {
        is ConnectionState.Connecting -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Connecting to ${connection.target.label}", fontWeight = FontWeight.SemiBold)
                    Text(connection.step, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        is ConnectionState.Failed -> Card(colors = CardDefaults.cardColors(containerColor = StatusColors.critical.copy(alpha = 0.15f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not connect", fontWeight = FontWeight.SemiBold, color = StatusColors.critical)
                Text(connection.message, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connection.target?.let { target -> OutlinedButton(onClick = { onRetry(target) }) { Text("Try again") } }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun BluetoothSection(state: ConnectUiState, viewModel: ConnectViewModel, enabled: Boolean) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshDevices()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshDevices()
    }
    val pairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshDevices()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val bluetoothPermissions = viewModel.bluetoothPermissions

    when {
        !state.bluetoothSupported -> SectionCard(title = "Bluetooth unavailable") {
            Text("This device has no Bluetooth. Use a Wi-Fi adapter instead.")
        }
        !state.hasConnectPermission -> SectionCard(title = "Allow Bluetooth access") {
            Text("SQ5 Monitor needs permission to find and connect to your OBD adapter.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permissionLauncher.launch(bluetoothPermissions) }) { Text("Allow") }
        }
        !state.bluetoothEnabled -> SectionCard(title = "Bluetooth is off") {
            Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
        }
        else -> {
            SectionCard(title = "Paired adapters", subtitle = "Bluetooth Classic adapters (most ELM327, OBDLink LX/MX, vLinker MC)") {
                if (state.paired.isEmpty()) {
                    Text("No paired devices yet.", style = MaterialTheme.typography.bodyMedium)
                }
                state.paired.forEach { device ->
                    DeviceRow(device, enabled) { requestNotifications(); viewModel.connect(device) }
                }
                TextButton(onClick = { pairLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                    Text("Pair a new adapter in Bluetooth settings")
                }
            }
            SectionCard(title = "Bluetooth LE adapters", subtitle = "Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX and similar") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.scanning) {
                        OutlinedButton(onClick = viewModel::stopScan) { Text("Stop") }
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = {
                            if (state.hasScanPermission) viewModel.startScan() else permissionLauncher.launch(bluetoothPermissions)
                        }, enabled = enabled) { Text("Scan") }
                    }
                }
                state.scanError?.let { Text(it, color = StatusColors.warning, style = MaterialTheme.typography.bodySmall) }
                state.nearby.forEach { device ->
                    DeviceRow(device, enabled) { requestNotifications(); viewModel.connect(device) }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: AdapterDevice, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = if (device.likelyObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = if (device.likelyObd) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                device.address + if (device.ble) " · LE" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Connect", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun WifiSection(defaults: ConnectionTarget.Wifi, enabled: Boolean, onConnect: (ConnectionTarget) -> Unit) {
    var host by rememberSaveable { mutableStateOf(defaults.host) }
    var port by rememberSaveable { mutableStateOf(defaults.port.toString()) }
    SectionCard(
        title = "Wi-Fi adapter",
        subtitle = "Join the adapter's Wi-Fi network (often \"WiFi_OBDII\") in Android settings first. Android may warn the network has no internet; stay connected.",
    ) {
        OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            port,
            { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        val portNumber = port.toIntOrNull()
        Button(
            onClick = { onConnect(ConnectionTarget.Wifi(host, portNumber ?: defaults.port)) },
            enabled = enabled && host.isNotBlank() && portNumber != null && portNumber in 1..65535,
        ) { Text("Connect") }
    }
}

@Composable
private fun DemoSection(enabled: Boolean, onStart: () -> Unit) {
    SectionCard(title = "Demo car", subtitle = "Explore the app without an adapter") {
        Text(
            "Simulates a 2018 SQ5 on a repeating drive (idle, full-throttle pull, cruise) with a couple of stored " +
                "fault codes, so every screen can be tried, including module scans and clearing codes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onStart, enabled = enabled) { Text("Start demo") }
    }
}
