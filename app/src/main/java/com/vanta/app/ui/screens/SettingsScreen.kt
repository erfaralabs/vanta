package com.vanta.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanta.app.data.AiProvider
import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.data.notification.CheckInScheduler
import com.vanta.app.data.backup.SupabaseBackupManager
import com.vanta.app.data.notification.NotificationPoster
import com.vanta.app.data.notification.NotificationSettings
import com.vanta.app.data.profile.ProfileBackup
import com.vanta.app.ui.components.AvatarImage
import com.vanta.app.ui.components.AvatarOption
import com.vanta.app.ui.components.GlassCard
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.AvatarHelper
import com.vanta.app.ui.viewmodel.AiApiUiState
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings — currently focused on AI Coach notification control.
 *
 * Every event category can be toggled independently. The Heart Rate toggle
 * defaults OFF because the user doesn't wear the watch daily: it gates the
 * HR-driven notifications (strain spikes, recovery changes) and keeps heart
 * rate out of every message.
 */
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()
    val settings = remember { NotificationSettings(context) }

    val aiViewModel: VantaAiViewModel = viewModel()
    // Dev-tool state (used by the commented-out developer section below).
    // val userBaseline by aiViewModel.userBaseline.collectAsState()
    // val historicalRecords by aiViewModel.historicalRecords.collectAsState()
    val userProfile by aiViewModel.userProfile.collectAsState()
    val apiState by aiViewModel.apiUiState.collectAsState()
    var keyCheckResult by remember { mutableStateOf<VantaGemmaEngine.ApiKeyCheckResult?>(null) }
    var isCheckingKey by remember { mutableStateOf(false) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var morningRecovery by remember { mutableStateOf(settings.morningRecovery) }
    var workout by remember { mutableStateOf(settings.workout) }
    var strain by remember { mutableStateOf(settings.strain) }
    var achievement by remember { mutableStateOf(settings.achievement) }
    var weekly by remember { mutableStateOf(settings.weekly) }
    var goals by remember { mutableStateOf(settings.goals) }
    var heartRate by remember { mutableStateOf(settings.heartRate) }
    var morningCheckIn by remember { mutableStateOf(settings.morningCheckIn) }
    var nightCheckIn by remember { mutableStateOf(settings.nightCheckIn) }
    var aiLimit by remember { mutableStateOf(settings.aiLimitPerDay) }

    // Reload the toggle UI after an import restores prefs from a .vanta file.
    fun reloadNotificationState() {
        enabled = settings.enabled
        morningRecovery = settings.morningRecovery
        workout = settings.workout
        strain = settings.strain
        achievement = settings.achievement
        weekly = settings.weekly
        goals = settings.goals
        heartRate = settings.heartRate
        morningCheckIn = settings.morningCheckIn
        nightCheckIn = settings.nightCheckIn
    }

    val scope = rememberCoroutineScope()

    // ── Supabase Cloud Backup State ──────────────────────────────────────────
    var supabaseUrlInput by remember { mutableStateOf(SupabaseBackupManager.getUrl(context)) }
    var supabaseKeyInput by remember { mutableStateOf(SupabaseBackupManager.getApiKey(context)) }
    var supabaseIsConfigured by remember { mutableStateOf(SupabaseBackupManager.isConfigured(context)) }
    var supabaseLastBackup by remember { mutableStateOf(SupabaseBackupManager.getLastBackupTime(context)) }
    var supabaseTestResult by remember { mutableStateOf<SupabaseBackupManager.TestResult?>(null) }
    var supabaseLoading by remember { mutableStateOf(false) }
    var showSupabaseRestoreConfirm by remember { mutableStateOf(false) }
    var autoBackupFrequency by remember { mutableStateOf(SupabaseBackupManager.getBackupFrequency(context)) }

    // ── Profile backup: export to a .vanta file (SAF) ─────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = ProfileBackup.export(context)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Profile exported ✓", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── Profile backup: import from a .vanta file (SAF) ───────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val message = runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    ProfileBackup.import(context, bytes)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (message != null) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        reloadNotificationState()
                        aiViewModel.runAnalysis()
                    } else {
                        Toast.makeText(
                            context,
                            "Import failed — not a valid .vanta profile",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    val circleShape = remember { CircleShape }
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    // Whether the user has whitelisted Vanta from battery optimization so the
    // background telemetry sync + AI notifications actually run while closed.
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var isBatteryWhitelisted by remember {
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
    fun openBatterySettings() {
        val target = if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            try {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
        }
        try {
            context.startActivity(target)
        } catch (_: Exception) {
            Toast.makeText(context, "Open Settings → Battery → Vanta → Allow", Toast.LENGTH_LONG).show()
        }
        isBatteryWhitelisted = pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ── Custom avatar photo picker + crop overlay ────────────────────────────
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) cropUri = uri
    }

    // Profile avatar state lifted to the root so the crop overlay can update it.
    var editAvatar by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userProfile) {
        editAvatar = userProfile?.avatarKey?.ifBlank { AvatarHelper.KEY_AVATAR_1 } ?: AvatarHelper.KEY_AVATAR_1
    }

    /** Persists the avatar choice immediately (preset tap or custom crop). */
    fun persistAvatar(key: String) {
        editAvatar = key
        val updated = (userProfile ?: com.vanta.app.data.db.UserProfileRecord(id = 1, name = "User"))
            .copy(avatarKey = key, isOnboardingCompleted = true)
        scope.launch(Dispatchers.IO) {
            com.vanta.app.data.db.VantaDatabase.getInstance(context)
                .userProfileDao().insertOrUpdateProfile(updated)
            aiViewModel.refreshUserProfile()
        }
    }

    if (cropUri != null) {
        AvatarCropScreen(
            imageUri = cropUri!!,
            onDone = { bmp ->
                AvatarHelper.saveCustomAvatar(context, bmp)
                persistAvatar(AvatarHelper.KEY_CUSTOM)
                cropUri = null
            },
            onCancel = { cropUri = null }
        )
        return
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
                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        // ── Profile ───────────────────────────────────────────────────────────
        item(key = "profile_edit") {
            val profile = userProfile
            var name by remember { mutableStateOf("") }
            var stepsGoal by remember { mutableIntStateOf(10000) }
            LaunchedEffect(profile) {
                name = profile?.name ?: ""
                stepsGoal = profile?.stepsGoal ?: 10000
            }

            GlassCard(
                accentColor = NeonCyan,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("PROFILE", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(12.dp))

                // Avatar picker
                Text("AVATAR", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AvatarOption(
                        AvatarHelper.KEY_AVATAR_1,
                        selected = editAvatar == AvatarHelper.KEY_AVATAR_1,
                        onClick = { persistAvatar(AvatarHelper.KEY_AVATAR_1) }
                    )
                    AvatarOption(
                        AvatarHelper.KEY_AVATAR_2,
                        selected = editAvatar == AvatarHelper.KEY_AVATAR_2,
                        onClick = { persistAvatar(AvatarHelper.KEY_AVATAR_2) }
                    )
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (editAvatar == AvatarHelper.KEY_CUSTOM) NeonCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (editAvatar == AvatarHelper.KEY_CUSTOM) NeonCyan else Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable { photoPicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("+", color = NeonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Custom", color = TextSecondary, fontSize = 8.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AvatarImage(avatarKey = editAvatar, modifier = Modifier.size(64.dp))
                }

                Spacer(Modifier.height(16.dp))

                Text("NAME", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = NeonCyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text("DAILY STEPS GOAL", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(
                        onClick = { stepsGoal = (stepsGoal - 1000).coerceAtLeast(1000) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                    ) { Text("−", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    Text(
                        text = "%,d steps / day".format(stepsGoal),
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    Button(
                        onClick = { stepsGoal = (stepsGoal + 1000).coerceAtMost(100000) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.2f), contentColor = NeonCyan)
                    ) { Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }

                Spacer(Modifier.height(16.dp))

                // Dynamic save: active only when something actually changed.
                val hasChanges = profile != null && (
                    name != profile.name ||
                    stepsGoal != profile.stepsGoal ||
                    (editAvatar ?: profile.avatarKey) != profile.avatarKey
                )
                Button(
                    onClick = {
                        val current = profile ?: com.vanta.app.data.db.UserProfileRecord(id = 1, name = "User")
                        val updated = current.copy(
                            name = name.ifBlank { current.name },
                            stepsGoal = stepsGoal.coerceIn(1000, 100000),
                            avatarKey = editAvatar ?: current.avatarKey,
                            isOnboardingCompleted = true
                        )
                        scope.launch(Dispatchers.IO) {
                            com.vanta.app.data.db.VantaDatabase.getInstance(context)
                                .userProfileDao().insertOrUpdateProfile(updated)
                            context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
                                .edit().putInt("steps_goal", updated.stepsGoal).apply()
                            aiViewModel.refreshUserProfile()
                            aiViewModel.refreshBaselineAndHistory()
                        }
                        Toast.makeText(context, "Profile updated ✓", Toast.LENGTH_SHORT).show()
                    },
                    enabled = hasChanges,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasChanges) NeonCyan else Color.White.copy(alpha = 0.08f),
                        contentColor = if (hasChanges) VantaBlack else TextTertiary,
                        disabledContainerColor = Color.White.copy(alpha = 0.08f),
                        disabledContentColor = TextTertiary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (hasChanges) "SAVE PROFILE" else "NO CHANGES",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item(key = "notif_section_header") {
            Text(
                text = "AI COACH NOTIFICATIONS",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "notif_master") {
            GlassCard(
                accentColor = NeonCyan,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                NotificationSwitchRow(
                    title = "AI Coach notifications",
                    subtitle = "Master switch for all coach insights.",
                    checked = enabled,
                    onCheckedChange = { enabled = it; settings.enabled = it }
                )
            }
        }

        /* ── DEV TOOL (test notification; re-enable for debugging) ──
        item(key = "test_notification") {
            GlassCard(
                accentColor = NeonCyan,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Test notification",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Send a sample AI Coach notification right now to verify delivery.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (NotificationPoster.postTest(context)) {
                                    Toast.makeText(context, "Test notification sent ✓", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Permission missing — ask for it, then the user can tap again
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.15f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Send test", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
        */

        item(key = "notif_categories") {
            GlassCard(
                accentColor = StepsViolet,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                NotificationSwitchRow(
                    title = "Morning Recovery",
                    subtitle = "Daily recovery summary, once after 5 AM.",
                    checked = morningRecovery && enabled,
                    onCheckedChange = { morningRecovery = it; settings.morningRecovery = it }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Workouts",
                    subtitle = "When a real workout session is logged.",
                    checked = workout && enabled,
                    onCheckedChange = { workout = it; settings.workout = it }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Strain spikes",
                    subtitle = "Significant strain jumps of 1.5+ during the day.",
                    checked = strain && enabled,
                    onCheckedChange = { strain = it; settings.strain = it }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Achievements",
                    subtitle = "10k steps and training-streak milestones.",
                    checked = achievement && enabled,
                    onCheckedChange = { achievement = it; settings.achievement = it }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Weekly summary",
                    subtitle = "A short recap every 7 days.",
                    checked = weekly && enabled,
                    onCheckedChange = { weekly = it; settings.weekly = it }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Morning check-in",
                    subtitle = "A warm 8:10 AM greeting with today's recovery and energy.",
                    checked = morningCheckIn && enabled,
                    onCheckedChange = {
                        morningCheckIn = it
                        settings.morningCheckIn = it
                        CheckInScheduler.scheduleAll(context)
                    }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Night check-in",
                    subtitle = "A calm 9:30 PM recap of today's strain and steps.",
                    checked = nightCheckIn && enabled,
                    onCheckedChange = {
                        nightCheckIn = it
                        settings.nightCheckIn = it
                        CheckInScheduler.scheduleAll(context)
                    }
                )
                HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                NotificationSwitchRow(
                    title = "Goals",
                    subtitle = "When a weekly training goal is reached.",
                    checked = goals && enabled,
                    onCheckedChange = { goals = it; settings.goals = it }
                )
            }
        }


        item(key = "notif_heart_rate") {
            GlassCard(
                accentColor = HeartRateRed,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                NotificationSwitchRow(
                    title = "Heart rate insights",
                    subtitle = "Strain spikes and recovery changes are driven by HR/RHR. You're not wearing your watch daily, so this is OFF — no HR data will appear in any message.",
                    checked = heartRate && enabled,
                    onCheckedChange = { heartRate = it; settings.heartRate = it }
                )
            }
        }

        item(key = "ai_engine_header") {
            Text(
                text = "AI COACH ENGINE",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "ai_provider") {
            val vm = aiViewModel
            var selectedAnalysisProv by remember { mutableStateOf(vm.selectedAnalysisProvider()) }
            var selectedChatProv by remember { mutableStateOf(vm.selectedChatProvider()) }
            var selectedCloudProvider by remember {
                mutableStateOf(vm.selectedCloudProvider())
            }
            var apiKeyInput by remember { mutableStateOf(vm.savedApiKey(selectedCloudProvider)) }
            var isCustomUrlExpanded by remember { mutableStateOf(false) }
            var customHfToken by remember { mutableStateOf("") }

            val onDeviceState by vm.onDeviceState.collectAsState()
            val downloadProgress by vm.downloadProgress.collectAsState()

            val isRamSufficient = remember { vm.isRamSufficientForOnDevice() }
            val totalRamGb = remember { vm.getTotalRamGb() }

            // On-device option is only selectable when the model is actually on disk
            // (and RAM is sufficient). Otherwise grey it out so the user knows to
            // download it first.
            val isModelOnDisk = onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.READY ||
                onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.DOWNLOADED
            val onDeviceSelectable = isRamSufficient && isModelOnDisk

            GlassCard(
                accentColor = NeonBlue,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "AI Routing & Engine Control",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Independently assign Cloud API (Gemini/DeepSeek/Mistral/OpenRouter) or On-Device Qwen3 for Home Analysis and AI Chat.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )

                // ── 1. Daily Analysis Engine Selector ─────────────────────────
                Text(
                    text = "📊 Daily Overview Analysis:",
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141414))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isCloudAnalysis = selectedAnalysisProv != AiProvider.ON_DEVICE_LITERT
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCloudAnalysis) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                selectedAnalysisProv = selectedCloudProvider
                                vm.selectAnalysisProvider(selectedCloudProvider)
                                vm.runAnalysis(forceFresh = true)
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "☁️ Cloud API (${selectedCloudProvider.label})",
                            color = if (isCloudAnalysis) NeonCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isCloudAnalysis) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isCloudAnalysis && onDeviceSelectable) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                when {
                                    !isRamSufficient -> Toast.makeText(
                                        context,
                                        "⚡ On-Device AI requires at least 8 GB RAM (${"%.1f".format(totalRamGb)} GB detected).",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    !isModelOnDisk -> Toast.makeText(
                                        context,
                                        "Download the on-device model first",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    else -> {
                                        selectedAnalysisProv = AiProvider.ON_DEVICE_LITERT
                                        vm.selectAnalysisProvider(AiProvider.ON_DEVICE_LITERT)
                                        vm.runAnalysis(forceFresh = true)
                                    }
                                }
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                !isRamSufficient -> "⚡ On-Device (< 8GB RAM)"
                                !isModelOnDisk -> "⚡ On-Device (not installed)"
                                else -> "⚡ On-Device Qwen3"
                            },
                            color = if (!onDeviceSelectable) TextSecondary.copy(alpha = 0.35f) else if (!isCloudAnalysis) NeonCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (!isCloudAnalysis && onDeviceSelectable) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── AI Daily Overview Analysis Toggle ─────────────────────────
                val isAiConfigured = vm.savedApiKey().isNotBlank() || vm.onDeviceLlmManager.isModelDownloaded()
                val isDailyAnalysisToggled by vm.isDailyAnalysisEnabled.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAiConfigured) Color(0xFF141414) else Color(0xFF0F0F0F))
                        .border(
                            width = 1.dp,
                            color = if (isAiConfigured) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .alpha(if (isAiConfigured) 1f else 0.45f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "✨ Daily Overview Analysis",
                                color = if (isAiConfigured) TextPrimary else TextTertiary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isAiConfigured) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x22FFAA00))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "LOCKED",
                                        color = EnergyAmber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (!isAiConfigured) {
                                "Set up an AI provider to enable"
                            } else if (isDailyAnalysisToggled) {
                                "Enabled · Home screen Vanta Coach briefing active"
                            } else {
                                "Disabled · Hidden from Home screen"
                            },
                            color = if (!isAiConfigured) TextTertiary else TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = isDailyAnalysisToggled && isAiConfigured,
                        onCheckedChange = { newVal ->
                            if (!isAiConfigured) {
                                Toast.makeText(context, "Set up an AI provider first", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            vm.setDailyAnalysisEnabled(newVal)
                        },
                        enabled = isAiConfigured,
                        colors = whiteSleekSwitchColors()
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── AI Coach Chat Master Toggle ──────────────────────────────
                val isChatToggled by vm.isAiChatEnabled.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAiConfigured) Color(0xFF141414) else Color(0xFF0F0F0F))
                        .border(
                            width = 1.dp,
                            color = if (isAiConfigured) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .alpha(if (isAiConfigured) 1f else 0.45f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "💬 AI Coach Chat",
                                color = if (isAiConfigured) TextPrimary else TextTertiary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isAiConfigured) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x22FFAA00))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "LOCKED",
                                        color = EnergyAmber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (!isAiConfigured) {
                                "Set up an AI provider to enable"
                            } else if (isChatToggled) {
                                "Enabled · Navigation orb & chat assistant active"
                            } else {
                                "Disabled · Hidden from navigation bar"
                            },
                            color = if (!isAiConfigured) TextTertiary else TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = isChatToggled && isAiConfigured,
                        onCheckedChange = { newVal ->
                            if (!isAiConfigured) {
                                Toast.makeText(context, "Set up an AI provider first", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            vm.setAiChatEnabled(newVal)
                        },
                        enabled = isAiConfigured,
                        colors = whiteSleekSwitchColors()
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── Detailed Coach (Detail Pages) Toggle ──────────────────────
                val isDetailedCoachToggled by vm.isDetailedCoachEnabled.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAiConfigured) Color(0xFF141414) else Color(0xFF0F0F0F))
                        .border(
                            width = 1.dp,
                            color = if (isAiConfigured) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .alpha(if (isAiConfigured) 1f else 0.45f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚡ Detail Pages AI Coach",
                                color = if (isAiConfigured) TextPrimary else TextTertiary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isAiConfigured) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x22FFAA00))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "LOCKED",
                                        color = EnergyAmber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (!isAiConfigured) {
                                "Set up an AI provider to enable"
                            } else if (isDetailedCoachToggled) {
                                "Enabled · AI deep-dive on Strain, Recovery & Energy"
                            } else {
                                "Disabled · Hidden from detail pages"
                            },
                            color = if (!isAiConfigured) TextTertiary else TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = isDetailedCoachToggled && isAiConfigured,
                        onCheckedChange = { newVal ->
                            if (!isAiConfigured) {
                                Toast.makeText(context, "Set up an AI provider first", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            vm.setDetailedCoachEnabled(newVal)
                        },
                        enabled = isAiConfigured,
                        colors = whiteSleekSwitchColors()
                    )
                }

                // ── VANTIX AI Coach Toggle ─────────────────────────────────────
                val isVantixToggled by vm.isVantixEnabled.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAiConfigured) Color(0xFF141414) else Color(0xFF0F0F0F))
                        .border(
                            width = 1.dp,
                            color = if (isAiConfigured) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .alpha(if (isAiConfigured) 1f else 0.45f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚡ Vantix AI Coach",
                                color = if (isAiConfigured) TextPrimary else TextTertiary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isAiConfigured) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x22FFAA00))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "SET UP",
                                        color = EnergyAmber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (!isAiConfigured) {
                                "Set up an AI provider to enable"
                            } else if (isVantixToggled) {
                                "Enabled · AI insights on the Vantix screen"
                            } else {
                                "Disabled · Hidden from Vantix"
                            },
                            color = if (!isAiConfigured) TextTertiary else TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = isVantixToggled && isAiConfigured,
                        onCheckedChange = { newVal ->
                            if (!isAiConfigured) {
                                Toast.makeText(context, "Set up an AI provider first", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            vm.setVantixEnabled(newVal)
                        },
                        enabled = isAiConfigured,
                        colors = whiteSleekSwitchColors()
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── 2. AI Chat Coach Engine Selector ───────────────────────────
                Text(
                    text = "💬 AI Chat Coach Engine:",
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141414))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isCloudChat = selectedChatProv != AiProvider.ON_DEVICE_LITERT
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCloudChat) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                selectedChatProv = selectedCloudProvider
                                vm.selectChatProvider(selectedCloudProvider)
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "☁️ Cloud API (${selectedCloudProvider.label})",
                            color = if (isCloudChat) NeonCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isCloudChat) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isCloudChat && onDeviceSelectable) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                when {
                                    !isRamSufficient -> Toast.makeText(
                                        context,
                                        "⚡ On-Device AI requires at least 8 GB RAM (${"%.1f".format(totalRamGb)} GB detected).",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    !isModelOnDisk -> Toast.makeText(
                                        context,
                                        "Download the on-device model first",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    else -> {
                                        selectedChatProv = AiProvider.ON_DEVICE_LITERT
                                        vm.selectChatProvider(AiProvider.ON_DEVICE_LITERT)
                                    }
                                }
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                !isRamSufficient -> "⚡ On-Device (< 8GB RAM)"
                                !isModelOnDisk -> "⚡ On-Device (not installed)"
                                else -> "⚡ On-Device Qwen3"
                            },
                            color = if (!onDeviceSelectable) TextSecondary.copy(alpha = 0.35f) else if (!isCloudChat) NeonCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (!isCloudChat && onDeviceSelectable) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Cloud API Key Configuration Sub-Card ───────────────────────
                Text(
                    text = "🔑 Cloud AI API Keys",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(AiProvider.GEMINI, AiProvider.DEEPSEEK, AiProvider.MISTRAL, AiProvider.OPENROUTER).forEach { provider ->
                        val isSelected = selectedCloudProvider == provider
                        OutlinedButton(
                            onClick = {
                                selectedCloudProvider = provider
                                apiKeyInput = vm.savedApiKey(provider)
                                if (selectedAnalysisProv != AiProvider.ON_DEVICE_LITERT) {
                                    selectedAnalysisProv = provider
                                    vm.selectAnalysisProvider(provider)
                                }
                                if (selectedChatProv != AiProvider.ON_DEVICE_LITERT) {
                                    selectedChatProv = provider
                                    vm.selectChatProvider(provider)
                                }
                            },
                            colors = if (isSelected) {
                                ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                            } else {
                                ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = provider.label,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = { Text("${selectedCloudProvider.label} API key", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            vm.saveApiKey(apiKeyInput, selectedCloudProvider)
                            vm.runAnalysis(forceFresh = true)
                            Toast.makeText(context, "${selectedCloudProvider.label} Key Saved", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.15f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("💾 SAVE KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (apiKeyInput.isBlank()) {
                                Toast.makeText(context, "Please enter an API key to check", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            scope.launch {
                                isCheckingKey = true
                                keyCheckResult = null
                                keyCheckResult = withContext(Dispatchers.IO) {
                                    val engine = com.vanta.app.data.VantaGemmaEngine(context)
                                    engine.checkApiKey(apiKeyInput.trim(), selectedCloudProvider)
                                }
                                isCheckingKey = false
                            }
                        },
                        enabled = !isCheckingKey,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isCheckingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = NeonBlue,
                                strokeWidth = 1.5.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("TESTING...", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        } else {
                            Text("🔍 CHECK KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }

                // ── AI Key Diagnostic Status Card ──────────────────────────────
                AnimatedVisibility(
                    visible = isCheckingKey || keyCheckResult != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        if (isCheckingKey) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF141414))
                                    .border(1.dp, Color(0x3300F2FE), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = NeonCyan,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "Verifying connection with ${selectedCloudProvider.label}...",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            when (val res = keyCheckResult) {
                                is com.vanta.app.data.VantaGemmaEngine.ApiKeyCheckResult.Valid -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x1A39FF80))
                                            .border(1.dp, Color(0x6639FF80), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("🟢", fontSize = 12.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Key Verified & Connected",
                                                    color = Color(0xFF39FF80),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                            Text(
                                                text = "Authenticated with ${selectedCloudProvider.label}. AI health overviews, coaching insights, and chat are fully active.",
                                                color = Color(0xCCFFFFFF),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                                is com.vanta.app.data.VantaGemmaEngine.ApiKeyCheckResult.QuotaExceeded -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x1AFFB000))
                                            .border(1.dp, Color(0x80FFB000), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("⚠️", fontSize = 12.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Quota or Rate Limit Reached (HTTP 429)",
                                                    color = Color(0xFFFFB000),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                            Text(
                                                text = "Your API key is authenticated and valid, but your free-tier rate limit (requests/min) or daily credit quota has been reached on ${selectedCloudProvider.label}.",
                                                color = Color(0xDDFFFFFF),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                            if (res.details.isNotBlank()) {
                                                Text(
                                                    text = "Provider message: ${res.details}",
                                                    color = Color(0x99FFFFFF),
                                                    fontSize = 10.sp,
                                                    lineHeight = 13.sp
                                                )
                                            }
                                            Text(
                                                text = "💡 Tip: Wait 1–2 minutes if on Gemini free tier, or check your quota on Google AI Studio / OpenRouter console.",
                                                color = Color(0xAAFFB000),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                                is com.vanta.app.data.VantaGemmaEngine.ApiKeyCheckResult.InvalidKey -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x1AFF5252))
                                            .border(1.dp, Color(0x80FF5252), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("❌", fontSize = 12.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Invalid or Unauthorized API Key",
                                                    color = Color(0xFFFF5252),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                            Text(
                                                text = "The key was rejected by ${selectedCloudProvider.label}. Please verify that you copied the complete key without spaces.",
                                                color = Color(0xDDFFFFFF),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                            if (res.details.isNotBlank()) {
                                                Text(
                                                    text = "Provider message: ${res.details}",
                                                    color = Color(0x99FFFFFF),
                                                    fontSize = 10.sp,
                                                    lineHeight = 13.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                is com.vanta.app.data.VantaGemmaEngine.ApiKeyCheckResult.NetworkError -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x1AFF9500))
                                            .border(1.dp, Color(0x80FF9500), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("📡", fontSize = 12.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Connection / Server Error",
                                                    color = Color(0xFFFF9500),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                            Text(
                                                text = "Could not establish connection with ${selectedCloudProvider.label}. Please verify your internet connection.",
                                                color = Color(0xDDFFFFFF),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                            if (res.details.isNotBlank()) {
                                                Text(
                                                    text = "Details: ${res.details}",
                                                    color = Color(0x99FFFFFF),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                null -> Unit
                            }
                        }
                    }
                }

                if (vm.savedApiKey(selectedCloudProvider).isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            vm.clearApiKey(selectedCloudProvider)
                            apiKeyInput = ""
                            keyCheckResult = null
                            Toast.makeText(context, "API Key Cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HeartRateRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text("🗑️ CLEAR KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── 3. On-Device Qwen3 Model Status & Controls ─────────────
                Text(
                    text = "📱 On-Device Qwen3 Model",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )


                    // ── On-Device Google LiteRT-LM (Gemma 4 E2B) Management Card ────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F141A))
                            .border(1.dp, Color(0x2600E5FF), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Gemma 4 E2B",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "google/gemma-4-E2B-it",
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Badges: GPU accelerated + max_sequence_length memory optimization
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(NeonCyan.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "⚡ GPU ACCELERATED",
                                    color = NeonCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x26FFFFFF))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "seq_len: 512 (Fast KV)",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x26FFFFFF))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "1.1 GB",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        val isModelOnDisk = vm.onDeviceLlmManager.isModelDownloaded()
                        val isModelInitializing = onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.INITIALIZING
                        val isModelReady = onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.READY || 
                                onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.DOWNLOADED

                        if (downloadProgress.status == com.vanta.app.data.ai.ModelDownloadManager.DownloadStatus.DOWNLOADING) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Downloading model weights...",
                                        color = NeonCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${downloadProgress.percent}%",
                                        color = NeonCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress.percent / 100f },
                                    color = NeonCyan,
                                    trackColor = Color(0xFF222222),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { vm.cancelModelDownload() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HeartRateRed),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("✕ CANCEL DOWNLOAD", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (isModelOnDisk || isModelReady) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val modelFile = com.vanta.app.data.ai.OnDeviceLlmManager.getModelFile(context)
                                val sizeMb = modelFile.length() / (1024 * 1024)
                                Text(
                                    text = "✅ Model Ready on GPU ($sizeMb MB). Vanta Coach & VANTIX run 100% locally.",
                                    color = RecoveryGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                vm.runAnalysis()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NeonCyan.copy(alpha = 0.15f),
                                            contentColor = NeonCyan
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("⟳ RUN ANALYSIS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { vm.deleteOnDeviceModel() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HeartRateRed),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("🗑️ DELETE MODEL", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            // Not downloaded yet
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Model not downloaded. Download Gemma 4 E2B for complete offline private coaching.",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                if (downloadProgress.errorMessage != null) {
                                    Text(
                                        text = "❌ ${downloadProgress.errorMessage}",
                                        color = HeartRateRed,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isCustomUrlExpanded = !isCustomUrlExpanded }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCustomUrlExpanded) "▾ Custom Download URL / Token" else "▸ Custom Download URL / Token (Optional)",
                                        color = NeonCyan,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                var customUrlInput by remember { mutableStateOf(com.vanta.app.data.ai.ModelDownloadManager.DEFAULT_MODEL_URL) }

                                if (isCustomUrlExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customUrlInput,
                                        onValueChange = { customUrlInput = it },
                                        placeholder = { Text("Model direct download URL (.litertlm / .bin)", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customHfToken,
                                        onValueChange = { customHfToken = it },
                                        placeholder = { Text("HuggingFace Access Token (if repo is gated)", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (!isRamSufficient) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(HeartRateRed.copy(alpha = 0.12f))
                                            .border(1.dp, HeartRateRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "⚠️ On-Device model execution requires at least 8 GB device RAM (${"%.1f".format(totalRamGb)} GB detected) to prevent Out-Of-Memory system aborts. Please use Cloud AI mode.",
                                            color = HeartRateRed,
                                            fontSize = 10.5.sp,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // ── Model storage location choice ─────────────
                                var modelStorage by remember {
                                    mutableStateOf(vm.getModelStorageLocation())
                                }
                                Text(
                                    text = "💾 Model storage location:",
                                    color = TextSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF141414))
                                        .padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    val appSelected =
                                        modelStorage == com.vanta.app.data.ai.OnDeviceLlmManager.ModelStorageLocation.APP_STORAGE
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (appSelected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                                            .clickable {
                                                modelStorage =
                                                    com.vanta.app.data.ai.OnDeviceLlmManager.ModelStorageLocation.APP_STORAGE
                                                vm.setModelStorageLocation(modelStorage)
                                            }
                                            .padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "App storage",
                                            color = if (appSelected) NeonCyan else TextSecondary,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (appSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (!appSelected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                                            .clickable {
                                                modelStorage =
                                                    com.vanta.app.data.ai.OnDeviceLlmManager.ModelStorageLocation.PUBLIC_DOWNLOADS
                                                vm.setModelStorageLocation(modelStorage)
                                            }
                                            .padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Downloads (SD)",
                                            color = if (!appSelected) NeonCyan else TextSecondary,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (!appSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (isRamSufficient) {
                                            vm.startModelDownload(
                                                customUrl = customUrlInput.takeIf { it.isNotBlank() },
                                                hfToken = customHfToken.takeIf { it.isNotBlank() }
                                            )
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Device has ${"%.1f".format(totalRamGb)} GB RAM. 8 GB required for on-device Gemma 4B.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    enabled = isRamSufficient,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isRamSufficient) NeonCyan else Color(0xFF222222),
                                        contentColor = if (isRamSufficient) VantaBlack else TextSecondary.copy(alpha = 0.4f),
                                        disabledContainerColor = Color(0xFF1E1E1E),
                                        disabledContentColor = TextSecondary.copy(alpha = 0.35f)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isRamSufficient) "⬇️ DOWNLOAD QWEN3-VL-2B (1.1 GB)" else "🔒 8 GB RAM REQUIRED (${"%.1f".format(totalRamGb)} GB DETECTED)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

        if (!notificationsGranted) {
            item(key = "notif_permission") {
                GlassCard(
                    accentColor = EnergyAmber,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification access",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Android 13+ requires permission before Vanta can deliver coach insights.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EnergyAmber,
                                contentColor = VantaBlack
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Enable", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item(key = "budget_info") {
            GlassCard(
                accentColor = RecoveryGreen,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = RecoveryGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Daily AI budget",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                val aiEnabled = apiState is AiApiUiState.Configured
                val dimmedAlpha = if (aiEnabled) 1f else 0.5f
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().alpha(dimmedAlpha),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI notifications per day",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { if (aiEnabled) { aiLimit = (aiLimit - 1).coerceAtLeast(3); settings.aiLimitPerDay = aiLimit } },
                        enabled = aiEnabled
                    ) {
                        Text("−", color = if (aiEnabled) RecoveryGreen else TextTertiary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(RecoveryGreen.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$aiLimit",
                            color = RecoveryGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    IconButton(
                        onClick = { if (aiEnabled) { aiLimit = (aiLimit + 1).coerceAtMost(10); settings.aiLimitPerDay = aiLimit } },
                        enabled = aiEnabled
                    ) {
                        Text("+", color = if (aiEnabled) RecoveryGreen else TextTertiary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = if (aiEnabled) {
                        "Up to $aiLimit AI-written notifications per day, then premium templates kick in automatically. Max 15 notifications total/day — no spam, no surprise API spend. Works offline too: templates fire when there's no internet."
                    } else {
                        "Up to $aiLimit AI-written notifications per day, then premium templates. Max 15 total/day. Add an API key above to enable AI notifications — offline, templates are always used."
                    },
                    color = if (aiEnabled) TextSecondary else TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        item(key = "bg_header") {
            Text(
                text = "BACKGROUND SYNC",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "bg_battery") {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val vendorHint = vendorBackgroundHint(manufacturer)
            GlassCard(
                accentColor = RecoveryGreen,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Run in the background",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isBatteryWhitelisted) {
                        "Vanta is exempt from battery optimization — the telemetry sync and AI notifications keep updating in the background."
                    } else {
                        "Android can pause Vanta in the background to save battery, so steps/telemetry and AI notifications only refresh when you open the app. Allow unrestricted background use to fix that."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                Button(
                    onClick = {
                        openBatterySettings()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecoveryGreen.copy(alpha = 0.15f),
                        contentColor = RecoveryGreen
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBatteryWhitelisted) "✅ ALLOWED — OPEN BATTERY SETTINGS" else "🔋 ALLOW UNRESTRICTED BACKGROUND USE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
                if (vendorHint.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = vendorHint,
                        color = EnergyAmber,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        item(key = "backup_header") {
            Text(
                text = "PROFILE BACKUP",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "profile_backup") {
            GlassCard(
                accentColor = NeonBlue,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Backup & transfer",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Export your profile, training history and notification preferences to a portable .vanta file — or restore them from one. API keys are never included.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportLauncher.launch(ProfileBackup.suggestedFileName()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.15f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⬆️ EXPORT .VANTA", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/json",
                                    "text/plain",
                                    "*/*"
                                )
                            )
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⬇️ IMPORT .VANTA", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        item(key = "supabase_header") {
            Text(
                text = "SUPABASE CLOUD SYNC",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "supabase_backup") {
            GlassCard(
                accentColor = NeonCyan,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Supabase Cloud Backup",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Sync your health database directly to your personal Supabase project with Row Level Security.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )

                Text("PROJECT URL", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                OutlinedTextField(
                    value = supabaseUrlInput,
                    onValueChange = { supabaseUrlInput = it },
                    placeholder = { Text("https://your-project.supabase.co", fontSize = 12.sp, color = TextTertiary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = NeonCyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PUBLIC ANON KEY ONLY", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    Text("🛡️ Never service_role", color = HeartRateRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = supabaseKeyInput,
                    onValueChange = { supabaseKeyInput = it },
                    placeholder = { Text("eyJhbGciOi... (anon key)", fontSize = 12.sp, color = TextTertiary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = NeonCyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val saveRes = SupabaseBackupManager.saveConfig(context, supabaseUrlInput, supabaseKeyInput)
                            saveRes.onSuccess {
                                supabaseIsConfigured = SupabaseBackupManager.isConfigured(context)
                                Toast.makeText(context, "Supabase credentials encrypted & saved ✓", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, err.message ?: "Invalid credentials", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.2f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("💾 SAVE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                supabaseTestResult = null
                                supabaseTestResult = SupabaseBackupManager.testConnection(supabaseUrlInput, supabaseKeyInput)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔍 TEST", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                supabaseTestResult?.let { res ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = res.message,
                        color = when (res.status) {
                            SupabaseBackupManager.TestStatus.SUCCESS -> RecoveryGreen
                            SupabaseBackupManager.TestStatus.INVALID_KEY -> HeartRateRed
                            else -> EnergyAmber
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (supabaseIsConfigured) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0x14FFFFFF))
                    Spacer(Modifier.height(12.dp))

                    // Auto-backup frequency selector
                    Text(
                        text = "AUTO-BACKUP FREQUENCY",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SupabaseBackupManager.BackupFrequency.entries.forEach { freq ->
                            val selected = autoBackupFrequency == freq
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) NeonCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (selected) NeonCyan else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        autoBackupFrequency = freq
                                        SupabaseBackupManager.setBackupFrequency(context, freq)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = freq.label,
                                    color = if (selected) NeonCyan else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (supabaseLastBackup != null) "Last Supabase Backup: $supabaseLastBackup" else "No backup recorded yet",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                supabaseLoading = true
                                scope.launch(Dispatchers.IO) {
                                    val result = SupabaseBackupManager.uploadBackup(context)
                                    withContext(Dispatchers.Main) {
                                        supabaseLoading = false
                                        result.onSuccess { msg ->
                                            supabaseLastBackup = SupabaseBackupManager.getLastBackupTime(context)
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, err.message ?: "Backup failed", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            enabled = !supabaseLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan.copy(alpha = 0.2f),
                                contentColor = NeonCyan
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (supabaseLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = NeonCyan, strokeWidth = 2.dp)
                            } else {
                                Text("☁️ BACKUP NOW", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { showSupabaseRestoreConfirm = true },
                            enabled = !supabaseLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("☁️ RESTORE", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔒 Hardware encrypted (AES-256)",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                        TextButton(
                            onClick = {
                                SupabaseBackupManager.clearConfig(context)
                                supabaseUrlInput = ""
                                supabaseKeyInput = ""
                                supabaseIsConfigured = false
                                supabaseLastBackup = null
                                supabaseTestResult = null
                                Toast.makeText(context, "Credentials cleared", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("🗑️ Clear", color = HeartRateRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ── Privacy Policy ────────────────────────────────────────────────────
        item(key = "privacy_policy") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF141414))
                    .clickable {
                        haptics.tick()
                        onPrivacyPolicyClick()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔒 Privacy Policy",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Open source · everything stays on your device",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextTertiary
                )
            }
        }

        /* ── DEV TOOLS (commented out for production; re-enable for testing) ──
        item(key = "dev_header") {
            Text(
                text = "DEVELOPER",
                color = TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        item(key = "dev_db") {
            GlassCard(
                accentColor = NeonCyan,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Room DB Telemetry Engine",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "7-Day Adaptive Baseline",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = userBaseline.phaseLabel.uppercase(),
                        color = if (userBaseline.isLearningPhase) EnergyAmber else RecoveryGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${userBaseline.savedDaysCount}/7 Days Archived",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                val progressFraction = (userBaseline.savedDaysCount / 7f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (userBaseline.isLearningPhase) EnergyAmber else RecoveryGreen,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = userBaseline.subtleStatusMessage,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "DATABASE ACTIONS",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { aiViewModel.seedDevProfileAndPast3DaysData() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StepsViolet.copy(alpha = 0.25f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧪 SEED DEV PROFILE & 3 DAYS DATA", fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { aiViewModel.simulateMidnightRollover() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.15f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚡ ROLLOVER", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { aiViewModel.resetAllHistoricalData() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HeartRateRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🗑️ RESET", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            }
        }

        item(key = "dev_records_header") {
            if (historicalRecords.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "ARCHIVED ROOM DB RECORDS",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                }
            }
        }

        items(historicalRecords.size, key = { "dev_record_${historicalRecords[it].date}" }) { idx ->
            val rec = historicalRecords[idx]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                DevRecordRow(rec = rec)
            }
        }
        */
    }

    if (showSupabaseRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showSupabaseRestoreConfirm = false },
            title = {
                Text(
                    text = "Restore from Supabase?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Restoring will replace all current health history and profile data with the backup downloaded from your Supabase project. Continue?",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSupabaseRestoreConfirm = false
                        supabaseLoading = true
                        scope.launch(Dispatchers.IO) {
                            val result = SupabaseBackupManager.restoreBackup(context)
                            withContext(Dispatchers.Main) {
                                supabaseLoading = false
                                result.onSuccess { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    reloadNotificationState()
                                    aiViewModel.refreshUserProfile()
                                    aiViewModel.refreshBaselineAndHistory()
                                    aiViewModel.runAnalysis()
                                }.onFailure { err ->
                                    Toast.makeText(context, err.message ?: "Restore failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = VantaBlack
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("RESTORE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSupabaseRestoreConfirm = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("CANCEL")
                }
            },
            containerColor = Color(0xFF141414),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/**
 * Single uniform white toggle style for every switch in Settings — a crisp black
 * thumb on a solid white track when ON, dark track with gray thumb when OFF.
 */
@Composable
private fun whiteSleekSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = Color.White,
    uncheckedThumbColor = Color(0xFF8E8E93),
    uncheckedTrackColor = Color(0xFF2C2C2E),
    uncheckedBorderColor = Color.White.copy(alpha = 0.12f),
    disabledCheckedThumbColor = Color.Black.copy(alpha = 0.5f),
    disabledCheckedTrackColor = Color.White.copy(alpha = 0.35f),
    disabledUncheckedThumbColor = Color(0xFF8E8E93).copy(alpha = 0.5f),
    disabledUncheckedTrackColor = Color(0xFF2C2C2E).copy(alpha = 0.6f)
)

@Composable
private fun NotificationSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {


        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (checked) TextPrimary else TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = whiteSleekSwitchColors()
        )
    }
}


@Composable
private fun DevRecordRow(rec: com.vanta.app.data.db.DailyMetricRecord) {
    val shape = remember { RoundedCornerShape(12.dp) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DATE: ${rec.date}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Steps: %,d | Cals: %d kcal | Dist: %.1f km".format(rec.steps, rec.calories, rec.distanceKm),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                )
                Text(
                    text = "RHR: ${if (rec.restingBpm > 0) "${rec.restingBpm} bpm" else "—"} | Avg HR: ${if (rec.avgBpm > 0) "${rec.avgBpm} bpm" else "—"} | Max HR: ${if (rec.maxBpm > 0) "${rec.maxBpm} bpm" else "—"}",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DevPill("Strain", "%.1f".format(rec.strain), StrainColor)
                DevPill("Recov", "${rec.recovery}%", RecoveryGreen)
                DevPill("Energy", "${rec.energy}%", EnergyAmber)
            }
        }
    }
}

@Composable
private fun DevPill(label: String, value: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label.uppercase(),
                color = color,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp)
            )
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp)
            )
        }
    }
}

/** Manufacturer-specific background guidance for aggressive battery managers. */
private fun vendorBackgroundHint(manufacturer: String): String = when {
    manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
        "On Xiaomi/Redmi/Poco: open Security → Autostart and allow Vanta, then set Battery → No restrictions."
    manufacturer.contains("samsung") ->
        "On Samsung: open Settings → Battery → Background usage limits and allow Vanta."
    manufacturer.contains("nothing") ->
        "On Nothing: open Settings → Battery and make sure Vanta isn't restricted."
    manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme") ->
        "On this phone: open Settings → Battery → Background app management and allow Vanta."
    manufacturer.contains("vivo") ->
        "On vivo: open iManager → App manager → Background apps and allow Vanta."
    manufacturer.contains("huawei") || manufacturer.contains("honor") ->
        "On this phone: open Settings → Battery → App launch and allow Vanta to run automatically."
    else -> ""
}

