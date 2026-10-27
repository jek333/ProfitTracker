package com.jek.sq5monitor.ui.vehicle

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.core.uds.VagModules
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.InfoRow
import com.jek.sq5monitor.ui.components.SectionCard
import com.jek.sq5monitor.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleScreen(topBarActions: @Composable () -> Unit) {
    val viewModel = appViewModel { VehicleViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTraffic by rememberSaveable { mutableStateOf(false) }

    Column {
        TopAppBar(title = { Text("Vehicle") }, actions = { topBarActions() })
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = state.vin?.model ?: "Vehicle", subtitle = if (state.isDemo) "Simulated car (demo mode)" else null) {
                val vin = state.vin
                if (vin == null) {
                    Text("The car did not report its VIN over OBD-II.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    InfoRow("VIN", vin.vin)
                    vin.manufacturer?.let { InfoRow("Manufacturer", it) }
                    vin.modelYear?.let { InfoRow("Model year", it.toString()) }
                    InfoRow("VIN check digit", if (vin.checkDigitValid) "Valid" else "Not valid / not used")
                }
                state.batteryVoltage?.let { InfoRow("Battery", it) }
            }

            SectionCard(title = "Adapter") {
                InfoRow("Connection", state.adapter)
                InfoRow("Firmware", state.adapterVersion)
                InfoRow("Protocol", state.protocol)
                InfoRow("OBD-II sensors", "${state.supportedSensors} supported")
                OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
            }

            DidReader(viewModel)

            SectionCard(title = "Adapter log", subtitle = "Raw commands and replies, useful when an adapter misbehaves") {
                TextButton(onClick = { showTraffic = !showTraffic }) { Text(if (showTraffic) "Hide" else "Show last ${state.traffic.size} lines") }
                if (showTraffic) {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        state.traffic.takeLast(80).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DidReader(viewModel: VehicleViewModel) {
    val result by viewModel.didResult.collectAsStateWithLifecycle()
    val busy by viewModel.didBusy.collectAsStateWithLifecycle()
    var moduleAddress by rememberSaveable { mutableStateOf("01") }
    var did by rememberSaveable { mutableStateOf("F187") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val module = VagModules.byAddress(moduleAddress) ?: VagModules.ENGINE

    SectionCard(
        title = "Read data identifier",
        subtitle = "Advanced: read any UDS identifier from a module (read-only). F187 part number, F189 software version, F197 system name.",
    ) {
        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = module.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Module") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                viewModel.modules.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { moduleAddress = option.address; menuOpen = false })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = did,
                onValueChange = { did = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(4) },
                label = { Text("Identifier (hex)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.readDid(module, did) }, enabled = !busy) { Text("Read") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        result?.let { r ->
            if (r.error != null) {
                Text(r.error, color = StatusColors.warning)
            } else {
                InfoRow("Hex", r.hex.orEmpty())
                InfoRow("Text", r.ascii.orEmpty())
            }
        }
    }
}
