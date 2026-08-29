package com.vanta.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.data.ai.ChatMessage
import com.vanta.app.data.ai.ChatSession
import com.vanta.app.ui.components.AiOrbCanvas
import com.vanta.app.ui.theme.InterFontFamily
import com.vanta.app.ui.theme.NeonBlue
import com.vanta.app.ui.theme.NeonCyan
import com.vanta.app.ui.theme.TextPrimary
import com.vanta.app.ui.theme.TextSecondary
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import com.vanta.app.ui.utils.rememberVantaHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.AddPhotoAlternate
import com.vanta.app.ui.utils.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VantaAiChatOverlay(
    viewModel: VantaAiViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val currentSession by viewModel.currentChatSession.collectAsState()
    val allSessions by viewModel.chatSessions.collectAsState()
    val isGenerating by viewModel.isChatGenerating.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showMediaSheet by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var showHistorySheet by remember { mutableStateOf(false) }

    val onDeviceState by viewModel.onDeviceState.collectAsState()
    val isModelInitializing = onDeviceState == com.vanta.app.data.ai.OnDeviceLlmManager.ModelState.INITIALIZING
    val chatProvider = viewModel.selectedChatProvider()
    val isOnDevice = chatProvider == com.vanta.app.data.AiProvider.ON_DEVICE_LITERT
    val isOpenRouter = chatProvider == com.vanta.app.data.AiProvider.OPENROUTER

    // Activity Result Launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            selectedImageUri = tempCameraUri
        }
    }

    fun launchCamera() {
        try {
            val photoFile = ImageUtils.createTempImageFile(context)
            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempCameraUri = photoUri
            cameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            android.util.Log.e("VantaAIChat", "Failed to launch camera", e)
        }
    }

    // Lifecycle: load model on entry if on-device mode, discard when leaving chat
    androidx.compose.runtime.DisposableEffect(Unit) {
        viewModel.enterChatSession()
        onDispose {
            viewModel.leaveChatSession()
        }
    }

    // Auto-focus keyboard on appearance
    LaunchedEffect(Unit) {
        delay(220)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Auto-scroll on new message AND follow the streamed reply so the tail is never cut off.
    val lastMsgLen = currentSession.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(currentSession.messages.size, lastMsgLen) {
        if (currentSession.messages.isNotEmpty()) {
            listState.scrollToItem(currentSession.messages.size - 1)
        }
    }

    // Light "AI is typing" haptic tick as tokens stream in.
    LaunchedEffect(Unit) {
        viewModel.aiTypingTick.collect { haptics.tick() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black) // Pure AMOLED Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding()
        ) {
            // ── Top Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF121212))
                            .border(1.dp, Color(0x22FFFFFF), CircleShape)
                            .clickable {
                                haptics.tick()
                                onClose()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "VANTA Coach",
                            color = TextPrimary,
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            letterSpacing = 0.3.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(NeonCyan)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // New Session
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF121212))
                            .border(1.dp, Color(0x22FFFFFF), CircleShape)
                            .clickable {
                                haptics.tick()
                                viewModel.startNewChatSession()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Chat",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Chat History
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF121212))
                            .border(1.dp, Color(0x22FFFFFF), CircleShape)
                            .clickable {
                                haptics.tick()
                                keyboardController?.hide()
                                showHistorySheet = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Chat History",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ── On-Device Model Initialization Progress Banner ─────────────────────
            AnimatedVisibility(
                visible = isOnDevice && isModelInitializing,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0E0E0E))
                        .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = NeonCyan,
                            strokeWidth = 2.2.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚡ Initializing GPU Engine...",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Allocating neural delegates for private local chat",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // ── Messages List or Centered Greeting ────────────────────────────────
            val hasMessages = currentSession.messages.any { it.content.isNotEmpty() }

            if (!hasMessages) {
                val userProfile by viewModel.userProfile.collectAsState()
                val athleteName = remember(userProfile) {
                    userProfile?.name?.trim()?.takeIf { it.isNotBlank() }?.split(" ")?.firstOrNull() ?: "Athlete"
                }

                val greetingTemplates = remember(athleteName) {
                    listOf(
                        "Hey $athleteName" to "How can I help you today?",
                        "Welcome back, $athleteName" to "What would you like to focus on?",
                        "Hey $athleteName" to "What's on your mind today?",
                        "Ready when you are, $athleteName" to "Ask me anything about your training or recovery."
                    )
                }
                val (greetingTitle, greetingSubtitle) = remember(currentSession.id, athleteName) {
                    greetingTemplates.random()
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(NeonCyan.copy(alpha = 0.22f), Color.Transparent)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = greetingTitle,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = InterFontFamily,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            text = greetingSubtitle,
                            color = TextSecondary.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(currentSession.messages.filter { it.content.isNotEmpty() }, key = { it.id }) { message ->
                        ChatBubbleItem(message = message)
                    }

                    if (isGenerating && (currentSession.messages.isEmpty() || currentSession.messages.last().content.isEmpty())) {
                        item(key = "generating_indicator") {
                            AiGeneratingBubble()
                        }
                    }
                }
            }

            // ── Quick Suggested Prompts (Visible at start of chat, disappears smoothly after 1-2 messages) ──
            val messageCount = currentSession.messages.count { it.content.isNotEmpty() }
            val showRecommendations = messageCount < 2 && !isGenerating

            AnimatedVisibility(
                visible = showRecommendations,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
            ) {
                val promptSuggestions = remember(currentSession.id) {
                    listOf(
                        "📊 Analyze my recovery",
                        "⚡ Explain today's strain",
                        "🏃‍♂️ Am I ready for a workout?",
                        "❤️ Why is my RHR resting?",
                        "🔋 How is my energy paced?",
                        "💤 Optimal sleep tonight?",
                        "🎯 Progress towards my goal",
                        "🔥 Calories burned breakdown",
                        "🏋️‍♂️ Recommended workout intensity",
                        "📈 Compare today vs 7d avg",
                        "💧 Hydration & recovery timing",
                        "⏱️ Pacing advice for today"
                    ).shuffled().take(5)
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(promptSuggestions) { prompt ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF101010))
                                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp))
                                .clickable {
                                    haptics.click()
                                    viewModel.sendChatMessage(prompt)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = prompt,
                                color = NeonCyan.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ── Attached Image Preview Chip ───────────────────────────────────────
            AnimatedVisibility(
                visible = selectedImageUri != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                selectedImageUri?.let { uri ->
                    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(uri) {
                        bitmap = ImageUtils.loadBitmapFromUri(context, uri)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF101010))
                                .border(1.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1A1A1A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Photo Attached",
                                    color = NeonCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap send to analyze food / workout",
                                    color = TextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF222222))
                                    .clickable {
                                        haptics.tick()
                                        selectedImageUri = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove photo",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Floating AMOLED Input Bar ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF0F0F0F))
                        .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(26.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Photo / Camera Attachment Button (hidden for text-only OpenRouter chat)
                    if (!isOpenRouter) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A1A1A))
                                .clickable {
                                    haptics.click()
                                    showMediaSheet = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Attach Photo",
                                tint = if (selectedImageUri != null) NeonCyan else TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontFamily = InterFontFamily,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if ((inputText.isNotBlank() || selectedImageUri != null) && !isGenerating) {
                                    haptics.click()
                                    viewModel.sendChatMessage(inputText, selectedImageUri?.toString())
                                    inputText = ""
                                    selectedImageUri = null
                                }
                            }
                        ),
                        decorationBox = { innerTextField ->
                            if (inputText.isEmpty()) {
                                Text(
                                    text = when {
                                        selectedImageUri != null -> "Add caption or question..."
                                        isOpenRouter -> "Ask Vanta Coach..."
                                        else -> "Ask Vanta Coach or attach photo..."
                                    },
                                    color = TextSecondary.copy(alpha = 0.55f),
                                    fontSize = 13.5.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    Spacer(Modifier.width(8.dp))

                    val canSend = (inputText.isNotBlank() || selectedImageUri != null) && !isGenerating
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isGenerating) Color(0xFF222226) else if (canSend) NeonCyan else Color(0xFF1C1C1E))
                            .clickable(enabled = canSend || isGenerating) {
                                if (isGenerating) {
                                    haptics.click()
                                    viewModel.stopGeneration()
                                } else if (canSend) {
                                    haptics.click()
                                    viewModel.sendChatMessage(inputText, selectedImageUri?.toString())
                                    inputText = ""
                                    selectedImageUri = null
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGenerating) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.Black else TextSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Media Picker Bottom Sheet (Camera vs Gallery) ──────────────────────────
        if (showMediaSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMediaSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF0A0A0A),
                scrimColor = Color.Black.copy(alpha = 0.75f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Attach Image",
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Upload a food plate, nutrition label, or workout photo for instant AI macro breakdown and form analysis.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Take Photo
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF141414))
                                .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .clickable {
                                    haptics.click()
                                    showMediaSheet = false
                                    launchCamera()
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Take Photo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        // Choose from Gallery
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF141414))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                .clickable {
                                    haptics.click()
                                    showMediaSheet = false
                                    galleryLauncher.launch("image/*")
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Choose Photo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        // ── Redesigned Premium AMOLED Chat History Bottom Sheet ────────────────────
        if (showHistorySheet) {
            ModalBottomSheet(
                onDismissRequest = { showHistorySheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF0A0A0A),
                scrimColor = Color.Black.copy(alpha = 0.75f)
            ) {
                val validSessions = remember(allSessions) { allSessions.filter { it.messages.isNotEmpty() } }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Chat History",
                                color = TextPrimary,
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Past 7 days retained automatically",
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (validSessions.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF181818))
                                        .border(1.dp, Color(0x33FF5252), RoundedCornerShape(12.dp))
                                        .clickable {
                                            haptics.tick()
                                            viewModel.clearAllChatSessions()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "Clear All",
                                        color = Color(0xFFFF5252).copy(alpha = 0.85f),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF181818))
                                    .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptics.tick()
                                        viewModel.startNewChatSession()
                                        showHistorySheet = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("New Chat", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (validSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No conversation history in the past 7 days.",
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(validSessions, key = { it.id }) { session ->
                                val isSelected = session.id == currentSession.id
                                val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                                val formattedDate = dateFmt.format(Date(session.timestamp))
                                val messageCount = session.messages.count { it.content.isNotBlank() }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) Color(0xFF161616) else Color(0xFF101010))
                                        .border(
                                            1.dp,
                                            if (isSelected) NeonCyan.copy(alpha = 0.6f) else Color(0x18FFFFFF),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                haptics.click()
                                                viewModel.selectChatSession(session.id)
                                                showHistorySheet = false
                                            }
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(NeonCyan)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(
                                                text = session.title,
                                                color = if (isSelected) NeonCyan else TextPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 14.sp,
                                                maxLines = 1
                                            )
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = formattedDate,
                                                color = TextSecondary.copy(alpha = 0.7f),
                                                fontSize = 11.sp
                                            )
                                            if (messageCount > 0) {
                                                Text(
                                                    text = " • $messageCount ${if (messageCount == 1) "message" else "messages"}",
                                                    color = TextSecondary.copy(alpha = 0.45f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF161616))
                                            .clickable {
                                                haptics.tick()
                                                viewModel.deleteChatSession(session.id)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFFF5252).copy(alpha = 0.75f),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(message: ChatMessage) {
    val context = LocalContext.current
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF121212))
                    .border(1.dp, NeonCyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(min = 40.dp, max = 290.dp)
        ) {
            // Render attached image if available
            if (!message.imageUri.isNullOrBlank()) {
                val uri = remember(message.imageUri) { Uri.parse(message.imageUri) }
                var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(uri) {
                    bitmap = ImageUtils.loadBitmapFromUri(context, uri)
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "User image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (message.content.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp
                            )
                        )
                        .background(
                            if (isUser) {
                                Color(0xFF1E1E22) // Pure dark graphite
                            } else {
                                Color(0xFF0F0F10) // Pure AMOLED surface
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isUser) Color(0x28FFFFFF) else Color(0x18FFFFFF),
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = message.content,
                        color = if (isUser) TextPrimary else TextPrimary.copy(alpha = 0.95f),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AiGeneratingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFF121212))
                .border(1.dp, NeonCyan.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = NeonCyan,
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.8.dp
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F10))
                .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Thinking...",
                color = NeonCyan.copy(alpha = 0.85f),
                fontFamily = InterFontFamily,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
