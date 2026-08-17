package com.vanta.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.data.GemmaAiAnalysis
import com.vanta.app.data.GemmaCallout
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.viewmodel.VantaAiUiState

/**
 * Editorial, Cardless AI Coach Section.
 * Renders a single 2–3 sentence daily coaching overview (personalized, actionable)
 * followed by 3–5 cardless callout items with vertical colored accent bars.
 */
@Composable
fun VantaAiCard(
    uiState: VantaAiUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // ── Header & Re-Analyze Action ─────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NeonCyan)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "VANTA COACH",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        fontSize = 10.sp
                    )
                )
            }

            val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        haptics.click()
                        onRefresh()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "⟳ RE-SYNC & ANALYZE",
                    color = NeonCyan,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        letterSpacing = 0.6.sp
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Crossfade(targetState = uiState, label = "editorial_gemma_state") { state ->
            when (state) {
                is VantaAiUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Reading today's numbers...",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                is VantaAiUiState.Error -> {
                    Text(
                        text = "Engine Notice: ${state.message}",
                        color = HeartRateRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is VantaAiUiState.Success -> {
                    val data = state.analysis

                    // Adaptive spacing based on text volume to prevent awkward empty gaps
                    val overviewLen = data.overview.length
                    val adaptiveGap = when {
                        overviewLen < 100 -> 10.dp
                        overviewLen < 220 -> 12.dp
                        else -> 14.dp
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // ── 1. Single Daily Overview Summary (2–4 Sentences, Word-by-Word Streaming) ──
                        StreamingTextEffect(
                            targetText = data.overview,
                            isGenerating = false,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White.copy(alpha = 0.92f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp,
                                letterSpacing = (-0.1).sp
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Reading today's numbers...", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(Modifier.height(adaptiveGap))

                        // ── 2. Editorial Cardless Callouts (exactly 2, always live) ──
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            data.callouts.take(2).forEach { callout ->
                                EditorialCalloutItem(callout = callout)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Editorial Cardless Callout Item:
 * - Colored vertical accent bar (3.dp width)
 * - No card background
 * - Max 2 lines of text (Inter Medium/Regular)
 */
@Composable
private fun EditorialCalloutItem(
    callout: GemmaCallout,
    modifier: Modifier = Modifier
) {
    val accentColor = remember(callout.colorHex) {
        parseColorHex(callout.colorHex)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical Accent Bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )

        Spacer(Modifier.width(14.dp))

        // Callout Text (Max 2 lines)
        Text(
            text = callout.text,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 2,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        )
    }
}

private fun parseColorHex(hex: String): Color {
    return try {
        val colorInt = android.graphics.Color.parseColor(hex)
        Color(colorInt)
    } catch (e: Exception) {
        NeonCyan
    }
}
