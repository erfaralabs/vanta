package com.vanta.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.*

/**
 * Premium high-performance glass-morphism card with hardware layer offloading.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = NeonCyan,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val bgBrush = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.02f),
            )
        )
    }
    val borderBrush = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                accentColor.copy(alpha = 0.2f),
                Color.White.copy(alpha = 0.05f),
            )
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer()
            .clip(shape)
            .background(bgBrush)
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * Premium AMOLED metric card — WHOOP / Oura / Linear-grade component.
 *
 * Design language: pure #111111 surface, thin hairline border, a very subtle
 * shadow, 22dp corners, and a strict typographic hierarchy (tiny letterspaced
 * label → large thin value → quiet supporting line). A small accent dot, a
 * trend/comparison detail, and an optional hairline progress bar carry the
 * "alive" feeling — no gradients, no oversized icons, no dashboard clutter.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    isMeasured: Boolean = true,
    showBadge: Boolean = true,
    valueColor: Color? = null,
    supporting: String = "",
    progress: Float? = null,
    progressLabel: String = "",
    onClick: (() -> Unit)? = null
) {
    val shape = remember { RoundedCornerShape(18.dp) }
    // Gradient hairline border = 1px-ish depth: bright top edge (light catch),
    // dark bottom edge (inner shadow). Combined with the subtle outer elevation
    // this reads as a machined card, not a Flutter-default flat box.
    val borderBrush = remember {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.5f to Color.White.copy(alpha = 0.05f),
            1f to Color.Black.copy(alpha = 0.38f)
        )
    }
    val clickModifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(shape)
            .background(Color(0xFF111111))
            .border(1.dp, borderBrush, shape)
            .then(clickModifier)
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 16.dp)
    ) {
        Column {
            // ── Label row + status dot ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label.uppercase(),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (showBadge) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isMeasured) accentColor.copy(alpha = 0.9f)
                                else EnergyAmber.copy(alpha = 0.9f)
                            )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Value + inline unit ───────────────────────────────────────────
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = valueColor ?: Color.White,
                    fontSize = if (value.length > 5) 24.sp else 34.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1.2).sp,
                    maxLines = 1
                )
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = unit,
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }

            // ── Quiet supporting detail (trend / comparison / progress) ──────
            if (supporting.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = supporting,
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 10.5.sp,
                    letterSpacing = 0.2.sp,
                    maxLines = 1
                )
            }

            // ── Hairline progress bar + tiny inline label ─────────────────────
            if (progress != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.85f))
                        )
                    }
                    if (progressLabel.isNotBlank()) {
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = progressLabel,
                            color = Color.White.copy(alpha = 0.32f),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        color = TextPrimary,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = modifier
    )
}
