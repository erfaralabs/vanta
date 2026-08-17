package com.vanta.app.ui.dev

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.rememberVantaHaptics

/**
 * Wraps root content in an interactive viewport simulator frame when active.
 */
@Composable
fun DevResolutionContainer(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()
    val isEnabled = DevResolutionManager.isSimulatorEnabled
    val currentResolution = DevResolutionManager.currentResolution

    if (!isEnabled || currentResolution == DevResolution.NATIVE || currentResolution.widthDp == null) {
        // Native full screen mode
        content()
        return
    }

    val screenConfig = LocalConfiguration.current
    val screenWidth = screenConfig.screenWidthDp.dp
    val screenHeight = screenConfig.screenHeightDp.dp

    val targetWidthDp by animateDpAsState(
        targetValue = currentResolution.widthDp.dp.coerceAtMost(screenWidth - 12.dp),
        animationSpec = tween(350),
        label = "target_width"
    )
    val targetHeightDp by animateDpAsState(
        targetValue = (currentResolution.heightDp?.dp ?: screenHeight).coerceAtMost(screenHeight - 80.dp),
        animationSpec = tween(350),
        label = "target_height"
    )
    val cornerRadius = currentResolution.cornerRadiusDp.dp

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070709)),
        contentAlignment = Alignment.TopCenter
    ) {
        // Subtle ambient backdrop glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            NeonCyan.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Device Bezel Frame
        Column(
            modifier = Modifier
                .padding(top = topInset + 6.dp)
                .width(targetWidthDp)
                .height(targetHeightDp - topInset)
                .shadow(28.dp, RoundedCornerShape(cornerRadius), spotColor = NeonCyan.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(cornerRadius))
                .border(2.5.dp, Color(0xFF2E313D), RoundedCornerShape(cornerRadius))
                .background(VantaBlack)
        ) {
            // Simulated minimal top indicator pill / status notch
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(Color(0xFF14151B)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(NeonCyan)
                    )
                    Text(
                        text = "${currentResolution.modelName} · ${currentResolution.widthDp}×${currentResolution.heightDp} dp",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }

            // Actual Application Screen
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content()
            }
        }

        // Floating Quick Switcher Bar at Bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .padding(horizontal = 12.dp)
                .shadow(20.dp, RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF111218).copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2F3D))
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(17.dp).padding(start = 4.dp)
                )

                DevResolution.entries.forEach { res ->
                    val isSelected = res == currentResolution
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) NeonCyan.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) NeonCyan.copy(alpha = 0.9f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                haptics.tick()
                                DevResolutionManager.setResolution(context, res)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = res.modelName,
                                color = if (isSelected) NeonCyan else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (res.widthDp != null) {
                                Text(
                                    text = "${res.widthDp}×${res.heightDp}",
                                    color = if (isSelected) NeonCyan.copy(alpha = 0.8f) else TextTertiary,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                // Close / Native button
                IconButton(
                    onClick = {
                        haptics.tick()
                        DevResolutionManager.setSimulatorActive(context, false)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Simulator",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
