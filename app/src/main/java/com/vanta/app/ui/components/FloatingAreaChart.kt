package com.vanta.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.TextSecondary
import com.vanta.app.ui.theme.TextTertiary
import com.vanta.app.ui.theme.VantaSurface2

/**
 * WHOOP-style floating area chart.
 *
 * A gradient-filled, glow-lined smooth curve that reveals left-to-right when the
 * data changes. The global peak and low points are auto-detected and highlighted
 * with a glowing dot + hairline, and two floating chips above the canvas answer
 * "when was it high / low?" at a glance.
 */
@Composable
fun FloatingAreaChart(
    values: List<Float>,
    labels: List<String>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    valueFormat: (Float) -> String = { v -> "%.0f".format(v) },
) {
    if (values.isEmpty()) return
    val data = values
    val count = data.size

    // Peak / low detection across the complete 7-day visible range.
    val peakIdx = data.indices.maxByOrNull { data[it] }
    val lowIdx = data.indices.minByOrNull { data[it] }
    val same = peakIdx == lowIdx

    // Animated reveal — re-runs when the dataset length changes (e.g. a new day).
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(count) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(850, easing = LinearOutSlowInEasing))
    }

    val fillBrush = remember(accentColor) {
        Brush.verticalGradient(
            colors = listOf(accentColor.copy(alpha = 0.28f), accentColor.copy(alpha = 0.02f), Color.Transparent)
        )
    }

    Column(modifier = modifier) {
        // ── Floating peak / low chips ─────────────────────────────────────────
        if (peakIdx != null && lowIdx != null && count >= 3) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!same) {
                    FloatingChartChip(
                        label = "PEAK",
                        day = labels.getOrElse(peakIdx) { "" },
                        value = valueFormat(data[peakIdx]),
                        color = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    FloatingChartChip(
                        label = "LOW",
                        day = labels.getOrElse(lowIdx) { "" },
                        value = valueFormat(data[lowIdx]),
                        color = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    FloatingChartChip(
                        label = "AVG RANGE",
                        day = labels.getOrElse(peakIdx) { "" },
                        value = valueFormat(data[peakIdx]),
                        color = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Chart canvas + markers ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(VantaSurface2.copy(alpha = 0.5f))
        ) {
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
                val padTop = 14.dp.toPx()
                val padBottom = 18.dp.toPx()
                val padH = 14.dp.toPx()
                val innerH = (size.height - padTop - padBottom).coerceAtLeast(1f)
                val innerW = (size.width - 2 * padH).coerceAtLeast(1f)

                val minY = data.minOrNull() ?: 0f
                val maxY = data.maxOrNull() ?: 0f
                val range = (maxY - minY).coerceAtLeast(0.1f)

                fun xFor(i: Int): Float =
                    if (count == 1) size.width / 2f
                    else padH + (i.toFloat() / (count - 1)) * innerW
                fun yFor(v: Float): Float = padTop + (1f - (v - minY) / range) * innerH

                // Subtle horizontal grid hairlines
                val grid = accentColor.copy(alpha = 0.08f)
                for (g in 0..3) {
                    val y = padTop + g * innerH / 3f
                    drawLine(grid, Offset(padH, y), Offset(size.width - padH, y), strokeWidth = 1f)
                }

                // Reveal clip
                val revealW = size.width * reveal.value
                clipRect(right = revealW) {
                    val line = Path()
                    val firstX = xFor(0)
                    val firstY = yFor(data[0])
                    line.moveTo(firstX, firstY)
                    var prevX = firstX
                    var prevY = firstY
                    var lastX = firstX
                    var lastY = firstY
                    for (i in 1 until count) {
                        val cx = xFor(i)
                        val cy = yFor(data[i])
                        val midX = (prevX + cx) / 2f
                        line.cubicTo(midX, prevY, midX, cy, cx, cy)
                        if (i == count - 1) { lastX = cx; lastY = cy }
                        prevX = cx; prevY = cy
                    }

                    val fill = Path().apply {
                        addPath(line)
                        lineTo(lastX, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(fill, brush = fillBrush)

                    // Glow pass + crisp line
                    drawPath(line, color = accentColor.copy(alpha = 0.30f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(line, color = accentColor, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))

                    // Peak / low glowing markers
                    if (peakIdx != null && !same) {
                        val px = xFor(peakIdx); val py = yFor(data[peakIdx])
                        drawLine(accentColor.copy(alpha = 0.25f), Offset(px, py), Offset(px, size.height - 2.dp.toPx()), strokeWidth = 1.4f)
                        drawCircle(accentColor.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = Offset(px, py))
                        drawCircle(accentColor, radius = 4.dp.toPx(), center = Offset(px, py))
                    }
                    if (lowIdx != null && !same) {
                        val lx = xFor(lowIdx); val ly = yFor(data[lowIdx])
                        drawLine(accentColor.copy(alpha = 0.14f), Offset(lx, ly), Offset(lx, size.height - 2.dp.toPx()), strokeWidth = 1.2f)
                        drawCircle(accentColor.copy(alpha = 0.14f), radius = 7.dp.toPx(), center = Offset(lx, ly))
                        drawCircle(accentColor.copy(alpha = 0.6f), radius = 3.dp.toPx(), center = Offset(lx, ly))
                    }

                    // Live "now" dot at the right edge
                    drawCircle(accentColor.copy(alpha = 0.18f), radius = 12.dp.toPx(), center = Offset(lastX, lastY))
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
                }
            }
        }


        // ── Day labels ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labels.forEachIndexed { i, lbl ->
                Text(
                    text = lbl,
                    color = if (i == count - 1) accentColor.copy(alpha = 0.9f) else TextTertiary,
                    fontSize = 9.sp,
                    fontWeight = if (i == count - 1) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (i < count - 1) Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** Small floating glass chip used for the peak/low summary. */
@Composable
private fun FloatingChartChip(
    label: String,
    day: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(color.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))
                )
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = color.copy(alpha = 0.9f),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.1.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (day.isEmpty()) value else "$day · $value",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

