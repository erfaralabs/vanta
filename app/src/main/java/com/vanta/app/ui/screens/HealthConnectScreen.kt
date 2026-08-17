package com.vanta.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.R
import com.vanta.app.ui.components.GlassCard
import com.vanta.app.ui.theme.*

@Composable
fun HealthConnectScreen(
    onBackClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isSyncing by remember { mutableStateOf(false) }

    // Remembered shapes and brushes to avoid allocations on scroll
    val circleShape = remember { CircleShape }
    val roundedCardShape = remember { RoundedCornerShape(20.dp) }
    val permissionBadgeShape = remember { RoundedCornerShape(20.dp) }
    val actionButtonBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(NeonCyan, NeonBlue)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(VantaBlack),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = 100.dp
        )
    ) {
        // ── Top Bar ───────────────────────────────────────────────────────────
        item(key = "top_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(circleShape)
                        .background(Color(0xFF141414))
                        .border(1.dp, Color(0x26FFFFFF), circleShape)
                        .clickable { onBackClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column {
                    Text(
                        text = "App Access",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    Text(
                        text = "Health Connect Permissions",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.weight(1f))

                // Active Badge
                Box(
                    modifier = Modifier
                        .clip(permissionBadgeShape)
                        .background(RecoveryGreen.copy(alpha = 0.15f))
                        .border(1.dp, RecoveryGreen.copy(alpha = 0.4f), permissionBadgeShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(circleShape)
                                .background(RecoveryGreen)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "8 PERMISSIONS",
                            color = RecoveryGreen,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Health Connect System Action Card ───────────────────────────────
        item(key = "action_card") {
            val logoBorderShape = remember { RoundedCornerShape(14.dp) }
            val activeIconBgShape = remember { CircleShape }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                GlassCard(accentColor = NeonCyan) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(logoBorderShape)
                                    .background(VantaBlack)
                                    .border(1.dp, NeonCyan.copy(alpha = 0.5f), logoBorderShape)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_logo),
                                    contentDescription = "Vanta Logo",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(logoBorderShape)
                                    .background(HeartRateRed.copy(alpha = 0.15f))
                                    .border(1.dp, HeartRateRed.copy(alpha = 0.4f), logoBorderShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Health Connect",
                                    tint = HeartRateRed,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(activeIconBgShape)
                                .background(RecoveryGreen.copy(alpha = 0.2f))
                                .padding(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active",
                                tint = RecoveryGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Vanta ↔ Health Connect Permissions",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Vanta requests read access for Activity, Sleep, and Vitals. Tap below to manage permissions in System Settings.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // ── Action Button: Open System Health Connect Permissions ────────────
        item(key = "action_button") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(roundedCardShape)
                        .background(actionButtonBrush)
                        .clickable {
                            openHealthConnectPermissions(context)
                        }
                        .padding(vertical = 16.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Permissions",
                            tint = VantaBlack,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "MANAGE SYSTEM PERMISSIONS",
                            color = VantaBlack,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // ── Category 1: Activity (3 of 3) ────────────────────────────────────
        item(key = "cat_activity") {
            PermissionCategorySection(
                categoryTitle = "Activity",
                selectedCount = "3 of 3 selected",
                items = listOf(
                    PermissionItem("🔥", "Distance", "Read distance walked & run (km)"),
                    PermissionItem("👣", "Steps", "Read daily & hourly step counts"),
                    PermissionItem("⚡", "Total calories burned", "Read active & total energy expenditure")
                )
            )
            Spacer(Modifier.height(20.dp))
        }

        // ── Category 2: Sleep (1 of 1) ───────────────────────────────────────
        item(key = "cat_sleep") {
            PermissionCategorySection(
                categoryTitle = "Sleep",
                selectedCount = "1 of 1 selected",
                items = listOf(
                    PermissionItem("🌙", "Sleep", "Read sleep duration & sleep stages")
                )
            )
            Spacer(Modifier.height(20.dp))
        }

        // ── Category 3: Vitals (4 of 4) ──────────────────────────────────────
        item(key = "cat_vitals") {
            PermissionCategorySection(
                categoryTitle = "Vitals",
                selectedCount = "4 of 4 selected",
                items = listOf(
                    PermissionItem("🩺", "Blood pressure", "Read systolic & diastolic blood pressure"),
                    PermissionItem("🌡️", "Body temperature", "Read basal & skin body temperature"),
                    PermissionItem("💓", "Heart rate", "Read continuous BPM & resting heart rate"),
                    PermissionItem("🫁", "Oxygen saturation", "Read SpO2 blood oxygen percentages")
                )
            )
        }

        // ── Privacy Policy footer ─────────────────────────────────────────────
        item(key = "privacy_footer") {
            Text(
                text = "Privacy Policy — how your data is handled",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPrivacyPolicyClick() }
                    .padding(vertical = 12.dp)
            )
        }
    }
}

private data class PermissionItem(
    val icon: String,
    val title: String,
    val description: String
)

@Composable
private fun PermissionCategorySection(
    categoryTitle: String,
    selectedCount: String,
    items: List<PermissionItem>
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = categoryTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )
            Text(
                text = selectedCount,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(Modifier.height(10.dp))

        val cardBgColor = remember { Color(0xFF101010) }
        val borderColor = remember { Color(0x1FFFFFFF) }
        val itemShape = remember { RoundedCornerShape(16.dp) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                var isChecked by remember { mutableStateOf(true) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .background(cardBgColor)
                        .border(1.dp, borderColor, itemShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = item.icon, fontSize = 18.sp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    text = item.description,
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Switch(
                            checked = isChecked,
                            onCheckedChange = { isChecked = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = Color(0xFF222222)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun openHealthConnectPermissions(context: Context) {
    try {
        val intent = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
