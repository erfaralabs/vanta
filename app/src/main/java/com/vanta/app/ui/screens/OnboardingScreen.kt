package com.vanta.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.ui.components.AvatarImage
import com.vanta.app.ui.components.AvatarOption
import com.vanta.app.ui.components.GlassCard
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.AvatarHelper
import com.vanta.app.ui.utils.rememberVantaHaptics
import com.vanta.app.ui.viewmodel.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val haptics = rememberVantaHaptics()
    val healthConnectManager = remember { HealthConnectManager(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) {
        kotlinx.coroutines.MainScope().launch {
            viewModel.checkHealthConnectPermissions()
        }
    }

    LaunchedEffect(uiState.step) {
        if (uiState.step == 2 && !uiState.isHealthConnectGranted && healthConnectManager.isAvailable) {
            runCatching {
                permissionLauncher.launch(healthConnectManager.permissions)
            }
        }
    }

    var showAdvancedOptions by remember { mutableStateOf(false) }
    var supabaseUrl by remember { mutableStateOf(com.vanta.app.data.backup.SupabaseBackupManager.getUrl(context)) }
    var supabaseApiKey by remember { mutableStateOf(com.vanta.app.data.backup.SupabaseBackupManager.getApiKey(context)) }
    var isRestoringSupabase by remember { mutableStateOf(false) }
    var restoreErrorMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                viewModel.restoreFromLocalFile(
                    bytes = bytes,
                    onSuccess = { summary ->
                        android.widget.Toast.makeText(context, summary, android.widget.Toast.LENGTH_LONG).show()
                        onOnboardingComplete()
                    },
                    onError = { err ->
                        restoreErrorMessage = err
                    }
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VantaBlack)
    ) {
        // Dynamic Glowing Background Accents
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .offset(x = 100.dp, y = (-80).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(NeonCyan.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-120).dp, y = 100.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(StepsViolet.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Top Header & Branding ──────────────────────────────────────────
            Text(
                text = "👋 Welcome to Vanta",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    letterSpacing = (-0.5).sp
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Set up your biometrics and birthdate to initialize your 7-day adaptive physiological baseline.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
            )

            Spacer(Modifier.height(12.dp))

            Spacer(Modifier.height(20.dp))

            // ── 3-Step Progress Indicator ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepPill(stepIndex = 0, currentStep = uiState.step, label = "Profile", modifier = Modifier.weight(1f))
                StepPill(stepIndex = 1, currentStep = uiState.step, label = "Goal", modifier = Modifier.weight(1f))
                StepPill(stepIndex = 2, currentStep = uiState.step, label = "Baseline", modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(28.dp))

            // ── Animated Step Contents ───────────────────────────────────────
            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "onboarding_step_animation"
            ) { step ->
                when (step) {
                    0 -> Step1PersonalDetails(uiState = uiState, viewModel = viewModel)
                    1 -> Step2FitnessGoal(uiState = uiState, viewModel = viewModel)
                    2 -> Step3HealthConnectAndBaseline(
                        uiState = uiState,
                        onRequestPermissions = {
                            if (healthConnectManager.isAvailable) {
                                permissionLauncher.launch(healthConnectManager.permissions)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            // ── Bottom Navigation Controls ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.step > 0) {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            viewModel.previousStep()
                        }
                    ) {
                        Text("Back", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                if (uiState.step < 2) {
                    Button(
                        onClick = {
                            haptics.click()
                            viewModel.nextStep()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Continue", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (!uiState.isSaving) {
                                haptics.click()
                                viewModel.completeOnboarding {
                                    onOnboardingComplete()
                                }
                            }
                        },
                        enabled = !uiState.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RecoveryGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("START VANTA", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Advanced Options: Pull from Supabase Cloud / File Restore ───────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0E0E0E))
                    .border(1.dp, if (showAdvancedOptions) NeonCyan.copy(alpha = 0.35f) else Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            showAdvancedOptions = !showAdvancedOptions
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚙️", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Advanced Options (Restore from Backup)",
                            color = if (showAdvancedOptions) NeonCyan else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                    Text(
                        text = if (showAdvancedOptions) "▲ Hide" else "▼ Show",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                AnimatedVisibility(
                    visible = showAdvancedOptions,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Have an existing Vanta backup? Pull your entire profile, baseline, and 7-day history from Supabase Cloud or import a .vanta file.",
                            color = TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )

                        OutlinedTextField(
                            value = supabaseUrl,
                            onValueChange = { supabaseUrl = it; restoreErrorMessage = null },
                            label = { Text("Supabase Project URL", fontSize = 11.sp) },
                            placeholder = { Text("https://your-project.supabase.co", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = supabaseApiKey,
                            onValueChange = { supabaseApiKey = it; restoreErrorMessage = null },
                            label = { Text("Supabase Anon Public Key", fontSize = 11.sp) },
                            placeholder = { Text("eyJhbGciOiJIUzI1NiIsIn...", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (!restoreErrorMessage.isNullOrBlank()) {
                            Text(
                                text = "⚠️ $restoreErrorMessage",
                                color = HeartRateRed,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (supabaseUrl.isBlank() || supabaseApiKey.isBlank()) {
                                        restoreErrorMessage = "Please enter both Supabase URL and Anon API key."
                                        return@Button
                                    }
                                    haptics.click()
                                    isRestoringSupabase = true
                                    restoreErrorMessage = null
                                    viewModel.restoreFromSupabase(
                                        url = supabaseUrl,
                                        apiKey = supabaseApiKey,
                                        onSuccess = { msg ->
                                            isRestoringSupabase = false
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                            onOnboardingComplete()
                                        },
                                        onError = { err ->
                                            isRestoringSupabase = false
                                            restoreErrorMessage = err
                                        }
                                    )
                                },
                                enabled = !isRestoringSupabase,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan.copy(alpha = 0.2f),
                                    contentColor = NeonCyan
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isRestoringSupabase) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = NeonCyan,
                                        strokeWidth = 1.8.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("RESTORING...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("☁️ PULL SUPABASE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    haptics.tick()
                                    importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = TextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📂 IMPORT .VANTA", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepPill(
    stepIndex: Int,
    currentStep: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    val isActive = stepIndex == currentStep
    val isCompleted = stepIndex < currentStep

    val barColor = when {
        isCompleted -> RecoveryGreen
        isActive -> NeonCyan
        else -> Color.White.copy(alpha = 0.15f)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive || isCompleted) Color.White else TextTertiary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
        )
    }
}

// ── STEP 1: Personal Details & Birthdate ──────────────────────────────────────
@Composable
private fun Step1PersonalDetails(
    uiState: com.vanta.app.ui.viewmodel.OnboardingUiState,
    viewModel: OnboardingViewModel
) {
    val haptics = rememberVantaHaptics()
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val context = LocalContext.current
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) cropUri = uri
    }

    // Full-screen crop when a custom photo was picked.
    if (cropUri != null) {
        AvatarCropScreen(
            imageUri = cropUri!!,
            onDone = { bmp ->
                AvatarHelper.saveCustomAvatar(context, bmp)
                viewModel.updateAvatar(AvatarHelper.KEY_CUSTOM)
                cropUri = null
            },
            onCancel = { cropUri = null }
        )
        return
    }

    GlassCard(accentColor = NeonCyan) {
        Column {
            Text(
                text = "PERSONAL BIOMETRICS & BIRTHDATE",
                color = NeonCyan,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
            )
            Spacer(Modifier.height(16.dp))

            // ── Avatar selection ───────────────────────────────────────────────
            Text(
                text = "CHOOSE YOUR AVATAR",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarOption(
                    avatarKey = AvatarHelper.KEY_AVATAR_1,
                    selected = uiState.avatarKey == AvatarHelper.KEY_AVATAR_1,
                    onClick = { viewModel.updateAvatar(AvatarHelper.KEY_AVATAR_1) }
                )
                AvatarOption(
                    avatarKey = AvatarHelper.KEY_AVATAR_2,
                    selected = uiState.avatarKey == AvatarHelper.KEY_AVATAR_2,
                    onClick = { viewModel.updateAvatar(AvatarHelper.KEY_AVATAR_2) }
                )
                // Custom upload option
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable {
                            haptics.click()
                            photoPicker.launch("image/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+", color = NeonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Custom", color = TextSecondary, fontSize = 8.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Live preview of the chosen avatar
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(VantaSurface2),
                    contentAlignment = Alignment.Center
                ) {
                    AvatarImage(avatarKey = uiState.avatarKey, modifier = Modifier.size(64.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Name
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Full Name") },
                singleLine = true,
                colors = textFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // Birthdate Selection Section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DateRange, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Date of Birth (Tracks age automatically)",
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Year
                OutlinedTextField(
                    value = uiState.birthdateYear,
                    onValueChange = { y ->
                        if (y.length <= 4 && y.all { it.isDigit() }) {
                            viewModel.updateBirthdateYear(y)
                        }
                    },
                    label = { Text("Year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = textFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.1f)
                )

                // Month Dropdown / Selector Button
                var monthExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1.1f)) {
                    OutlinedTextField(
                        value = monthNames.getOrElse(uiState.birthdateMonth - 1) { "May" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Month") },
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { monthExpanded = true },
                        trailingIcon = {
                            Text("▼", color = TextTertiary, fontSize = 10.sp, modifier = Modifier.clickable { monthExpanded = true })
                        }
                    )

                    DropdownMenu(
                        expanded = monthExpanded,
                        onDismissRequest = { monthExpanded = false },
                        modifier = Modifier.background(Color(0xFF1A1A1A))
                    ) {
                        monthNames.forEachIndexed { idx, m ->
                            DropdownMenuItem(
                                text = { Text(m, color = Color.White) },
                                onClick = {
                                    haptics.tick()
                                    viewModel.updateBirthdateMonth(idx + 1)
                                    monthExpanded = false
                                }
                            )
                        }
                    }
                }

                // Day
                OutlinedTextField(
                    value = uiState.birthdateDay,
                    onValueChange = { d ->
                        if (d.length <= 2 && d.all { it.isDigit() }) {
                            viewModel.updateBirthdateDay(d)
                        }
                    },
                    label = { Text("Day") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = textFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(0.9f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Calculated Age Auto-Display Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeonCyan.copy(alpha = 0.12f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎂 Calculated Age: ${uiState.calculatedAge} yrs",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Max HR: ${220 - uiState.calculatedAge} bpm",
                        color = NeonCyan,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "✨ Vanta will track your birthday and update your age & Max HR automatically every year.",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )

            Spacer(Modifier.height(16.dp))

            // Height & Weight Row
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uiState.heightCm,
                    onValueChange = { viewModel.updateHeight(it) },
                    label = { Text("Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = textFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = uiState.weightKg,
                    onValueChange = { viewModel.updateWeight(it) },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = textFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Sex Selection (Optional)
            Text(
                text = "Biological Sex (Optional for HR/Calorie estimates)",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Male", "Female", "Other").forEach { sexOption ->
                    val isSelected = uiState.sex.equals(sexOption, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(
                                1.dp,
                                if (isSelected) NeonCyan else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                haptics.tick()
                                viewModel.updateSex(sexOption)
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sexOption,
                            color = if (isSelected) Color.White else TextTertiary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        )
                    }
                }
            }
        }
    }
}

// ── STEP 2: Fitness Goals ─────────────────────────────────────────────────────
@Composable
private fun Step2FitnessGoal(
    uiState: com.vanta.app.ui.viewmodel.OnboardingUiState,
    viewModel: OnboardingViewModel
) {
    val haptics = rememberVantaHaptics()

    val goals = listOf(
        GoalOption("Build Muscle", "Hypertrophy & Strength Training", Icons.Filled.FitnessCenter, StepsViolet),
        GoalOption("Lose Fat", "Caloric Efficiency & High Strain", Icons.Filled.LocalFireDepartment, CaloriesOrange),
        GoalOption("Improve Cardio", "VO2 Max & Heart Rate Stamina", Icons.Filled.Favorite, HeartRateRed),
        GoalOption("General Fitness", "Balanced Energy & Recovery Optimization", Icons.Filled.Speed, NeonCyan)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "SELECT YOUR PRIMARY FITNESS GOAL",
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp)
        )

        goals.forEach { item ->
            val isSelected = uiState.selectedGoal == item.title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) item.color.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) item.color else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        haptics.click()
                        viewModel.updateGoal(item.title)
                    }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(item.color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(item.icon, contentDescription = null, tint = item.color, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = item.description,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                        )
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(item.color),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ── Daily steps goal ──────────────────────────────────────────────────
        Spacer(Modifier.height(6.dp))
        Text(
            text = "DAILY STEPS GOAL",
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(5000, 8000, 10000, 12000, 15000)
            presets.forEach { preset ->
                val selected = uiState.stepsGoal == preset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) NeonCyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (selected) NeonCyan.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.click()
                            viewModel.updateStepsGoal(preset)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (preset >= 1000) "%,d".format(preset) else "$preset",
                        color = if (selected) NeonCyan else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    viewModel.updateStepsGoal(uiState.stepsGoal - 1000)
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
            ) {
                Text("−", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(
                text = "%,d steps / day".format(uiState.stepsGoal),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Button(
                onClick = {
                    haptics.tick()
                    viewModel.updateStepsGoal(uiState.stepsGoal + 1000)
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan.copy(alpha = 0.2f),
                    contentColor = NeonCyan
                )
            ) {
                Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        Text(
            text = "Vanta tracks your progress against this target on Home and the Steps page.",
            color = TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private data class GoalOption(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val color: Color
)

// ── STEP 3: Health Connect & 7-Day Baseline Setup ────────────────────────────
@Composable
private fun Step3HealthConnectAndBaseline(
    uiState: com.vanta.app.ui.viewmodel.OnboardingUiState,
    onRequestPermissions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Health Connect Permissions Card
        GlassCard(accentColor = NeonCyan) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HEALTH CONNECT SYNC",
                        color = NeonCyan,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (uiState.isHealthConnectGranted) RecoveryGreen.copy(alpha = 0.2f)
                                else EnergyAmber.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (uiState.isHealthConnectGranted) "GRANTED" else "PENDING",
                            color = if (uiState.isHealthConnectGranted) RecoveryGreen else EnergyAmber,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 8.sp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Grant Health Connect permissions so Vanta can read resting HR, average HR, steps, calories, and distance offline on-device.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
                )

                Spacer(Modifier.height(12.dp))

                if (!uiState.isHealthConnectGranted) {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.2f), contentColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Health Connect Permissions", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // 7-Day Baseline Setup Announcement Card (Requested Text)
        GlassCard(accentColor = RecoveryGreen) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RecoveryGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🌱 LEARNING PHASE STARTED (DAY 1/7)",
                            color = RecoveryGreen,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 9.sp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "\"Your personal baseline is now being built. After 7 days, Vanta will personalize your Strain, Recovery, and Energy scores.\"",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "All set, ${uiState.name.ifBlank { "friend" }} — your profile is ready. Vanta will build your personal baseline from your first 7 days of training.",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                )
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = TextTertiary,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)
