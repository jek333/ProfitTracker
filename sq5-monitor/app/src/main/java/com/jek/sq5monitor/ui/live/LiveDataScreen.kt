package com.jek.sq5monitor.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.Sparkline
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDataScreen(topBarActions: @Composable () -> Unit) {
    val viewModel = appViewModel { LiveDataViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.onVisible()
        onDispose { viewModel.onHidden() }
    }

    Column {
        TopAppBar(
            title = {
                Column {
                    Text("Live data")
                    Text(
                        String.format(Locale.US, "%d sensors · %.1f Hz", state.rows.size, state.ratePerSecond),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = viewModel::resetMinMax) { Icon(Icons.Filled.RestartAlt, contentDescription = "Reset min/max") }
                topBarActions()
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.id }) { row ->
                LiveRowCard(row, onToggleDashboard = { viewModel.toggleDashboard(row.id) })
            }
        }
    }
}

@Composable
private fun LiveRowCard(row: LiveRow, onToggleDashboard: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(row.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Text(row.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "min ${row.min} · max ${row.max}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Sparkline(row.history, MaterialTheme.colorScheme.primary, Modifier.width(96.dp).height(36.dp))
            IconButton(onClick = onToggleDashboard) {
                Icon(
                    if (row.onDashboard) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (row.onDashboard) "Remove from dashboard" else "Add to dashboard",
                    tint = if (row.onDashboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
