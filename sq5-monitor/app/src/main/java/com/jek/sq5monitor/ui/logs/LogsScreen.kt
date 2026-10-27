package com.jek.sq5monitor.ui.logs

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jek.sq5monitor.data.DriveLogFile
import com.jek.sq5monitor.ui.appViewModel
import com.jek.sq5monitor.ui.components.ConfirmDialog
import com.jek.sq5monitor.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { LogsViewModel(it) }
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<DriveLogFile?>(null) }

    Column {
        TopAppBar(
            title = { Text("Drive logs") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        "No logs yet. Tap the record button on the dashboard to log your gauges to a CSV file " +
                            "you can open in a spreadsheet or upload to a log viewer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(logs, key = { it.name }) { log ->
                val active = recording?.fileName == log.name
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(log.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (active) "Recording… ${recording?.rows ?: 0} rows"
                                else "${Formatter.formatShortFileSize(context, log.sizeBytes)} · " +
                                    DateUtils.getRelativeTimeSpanString(log.modifiedMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) StatusColors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", log.file)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/csv")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        }) { Icon(Icons.Filled.Share, contentDescription = "Share ${log.name}") }
                        IconButton(onClick = { pendingDelete = log }, enabled = !active) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${log.name}")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { log ->
        ConfirmDialog(
            title = "Delete log?",
            message = log.name,
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(log) },
            onDismiss = { pendingDelete = null },
        )
    }
}
