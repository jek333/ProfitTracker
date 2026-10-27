package com.jek.sq5monitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jek.sq5monitor.ui.theme.StatusColors

/** Everything a gauge needs, already converted to display units. */
data class GaugeModel(
    val id: String,
    val label: String,
    /** Formatted value, or null while waiting for data. */
    val valueText: String?,
    val unit: String,
    /** Needle position 0..1 across the gauge range. */
    val fraction: Float,
    val minText: String,
    val maxText: String,
    val alert: Boolean,
)

private const val START_ANGLE = 135f
private const val SWEEP = 270f

@Composable
fun GaugeCard(model: GaugeModel, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(model.fraction.coerceIn(0f, 1f), tween(180), label = "gauge")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val arcColor = if (model.alert) StatusColors.critical else MaterialTheme.colorScheme.primary
    val description = "${model.label}: ${model.valueText ?: "no data"} ${model.unit}"

    Card(
        modifier = modifier.semantics { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                model.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val stroke = size.minDimension * 0.08f
                    val diameter = size.minDimension - stroke
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2 + stroke * 0.8f)
                    val arcSize = Size(diameter, diameter)
                    drawArc(trackColor, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (model.valueText != null) {
                        drawArc(arcColor, START_ANGLE, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        model.valueText ?: "—",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (model.alert) StatusColors.critical else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(model.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                Text(model.minText, Modifier.align(Alignment.CenterStart), fontSize = 11.sp, color = Color.Gray)
                Text(model.maxText, Modifier.align(Alignment.CenterEnd), fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
