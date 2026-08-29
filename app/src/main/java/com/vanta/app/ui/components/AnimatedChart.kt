package com.vanta.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vanta.app.data.ChartDataPoint

/**
 * Hardware-accelerated zero-allocation glowing line chart.
 * All Path operations and primitive Float calculations run with 0 object creations inside Canvas.
 */
@Composable
fun GlowLineChart(
    data: List<ChartDataPoint>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    showDots: Boolean = true,
) {
    if (data.size < 2) return

    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    val fillBrush = remember(accentColor) {
        Brush.verticalGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.25f),
                Color.Transparent
            )
        )
    }

    Canvas(modifier = modifier.graphicsLayer()) {
        val count = data.size
        if (count < 2) return@Canvas

        val minY   = data.minOf { it.y }
        val maxY   = data.maxOf { it.y }
        val rangeY = (maxY - minY).coerceAtLeast(0.1f)
        val stepX  = size.width / (count - 1).toFloat()

        // Subtle horizontal grid hairlines (Bevel-style reference lines).
        val plotTop = size.height * 0.06f
        val plotBottom = size.height * 0.94f
        val gridColor = Color.White.copy(alpha = 0.05f)
        for (g in 0..3) {
            val y = plotTop + (plotBottom - plotTop) * g / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        linePath.reset()
        
        var firstX = 0f
        var firstY = size.height - ((data[0].y - minY) / rangeY * size.height * 0.88f) - size.height * 0.06f
        linePath.moveTo(firstX, firstY)

        var prevX = firstX
        var prevY = firstY
        var lastX = firstX
        var lastY = firstY

        for (i in 1 until count) {
            val currX = i * stepX
            val currY = size.height - ((data[i].y - minY) / rangeY * size.height * 0.88f) - size.height * 0.06f
            val cx = (prevX + currX) / 2f
            linePath.cubicTo(cx, prevY, cx, currY, currX, currY)
            prevX = currX
            prevY = currY
            if (i == count - 1) {
                lastX = currX
                lastY = currY
            }
        }

        fillPath.reset()
        fillPath.addPath(linePath)
        fillPath.lineTo(lastX, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()

        drawPath(path = fillPath, brush = fillBrush)

        drawPath(
            path  = linePath,
            color = accentColor.copy(alpha = 0.35f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        drawPath(
            path  = linePath,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        if (showDots) {
            drawCircle(
                color  = accentColor.copy(alpha = 0.3f),
                radius = 8.dp.toPx(),
                center = Offset(lastX, lastY)
            )
            drawCircle(color = accentColor, radius = 3.5.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}

/**
 * Vertical bar chart with zero-allocation gradient draw passes.
 */
@Composable
fun GlowBarChart(
    data: List<ChartDataPoint>,
    accentColor: Color,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) return

    val barBrush = remember(accentColor) {
        Brush.verticalGradient(
            colors = listOf(accentColor, accentColor.copy(alpha = 0.3f))
        )
    }

    Canvas(modifier = modifier.graphicsLayer()) {
        val count = data.size
        if (count == 0) return@Canvas

        val maxY    = data.maxOf { it.y }.coerceAtLeast(1f)
        val barW    = size.width / (count * 2f)
        val spacing = barW

        for (i in 0 until count) {
            val pt = data[i]
            val barH   = ((pt.y / maxY) * size.height * 0.85f)
            val left   = i * (barW + spacing) + spacing / 2f
            val top    = size.height - barH

            val corner = CornerRadius(barW * 0.5f, barW * 0.5f)
            drawRoundRect(
                brush       = barBrush,
                topLeft     = Offset(left, top),
                size        = Size(barW, barH),
                cornerRadius = corner,
                alpha       = 0.85f
            )
        }
    }
}

@Composable
fun HrZoneBar(
    zones: List<com.vanta.app.data.HrZone>,
    modifier: Modifier = Modifier,
) {
    val totalMinutes = remember(zones) { zones.sumOf { it.minutes }.toFloat() }
    val safeTotal = if (totalMinutes > 0f) totalMinutes else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .graphicsLayer()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            .background(Color(0xFF1E1E1E))
    ) {
        zones.forEach { zone ->
            val weight = if (totalMinutes > 0f) (zone.minutes / safeTotal).coerceAtLeast(0.01f) else (1f / zones.size)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(zone.color)
            )
        }
    }
}
