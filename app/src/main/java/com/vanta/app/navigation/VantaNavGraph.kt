package com.vanta.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.data.db.VantaDatabase
import com.vanta.app.ui.screens.*
import com.vanta.app.ui.theme.VantaBlack
import com.vanta.app.ui.utils.rememberVantaHaptics

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Onboarding     : Screen("onboarding",     "Welcome",    Icons.Filled.Person)
    object Home           : Screen("home",           "Home",       Icons.Filled.Home)
    object Steps          : Screen("steps",          "Steps",      Icons.AutoMirrored.Filled.DirectionsWalk)
    object Analytics      : Screen("analytics",      "Insights",   Icons.Filled.Analytics)
    object AdaptiveCore   : Screen("adaptive_core",  "Vantix",     Icons.Filled.AutoAwesome)
    object HealthConnect  : Screen("health_connect", "Health SDK", Icons.Filled.Home)
    object Settings       : Screen("settings",       "Settings",   Icons.Filled.Settings)
    object PhysiologyDetail : Screen("physiology_detail", "Details", Icons.Filled.Home)
    object Breathing      : Screen("breathing",      "Breathe",    Icons.Filled.Home)
}

val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Steps,
    Screen.AdaptiveCore,
    Screen.Analytics,
)

/**
 * Ultra-Performance Persistent Vanta Navigation Architecture with Onboarding Flow.
 */
@Composable
fun VantaNavGraph() {
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()
    val aiViewModel: com.vanta.app.ui.viewmodel.VantaAiViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    var showOnboarding by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val db = VantaDatabase.getInstance(context)
        val profile = db.userProfileDao().getUserProfile()
        showOnboarding = profile == null || !profile.isOnboardingCompleted
    }

    var selectedTab by remember { mutableStateOf<Screen>(Screen.Home) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var detailMetric by remember { mutableStateOf(PhysiologyMetric.RECOVERY) }
    var isAiChatOpen by remember { mutableStateOf(false) }

    // Intercept back gesture / button press so it navigates back to Home page instead of closing the app
    val canGoBackToHome = isAiChatOpen || currentScreen != Screen.Home || selectedTab != Screen.Home
    androidx.activity.compose.BackHandler(enabled = canGoBackToHome && showOnboarding == false) {
        haptics.tick()
        if (isAiChatOpen) {
            isAiChatOpen = false
        } else if (currentScreen == Screen.HealthConnect || currentScreen == Screen.Settings ||
            currentScreen == Screen.PhysiologyDetail || currentScreen == Screen.Breathing) {
            currentScreen = selectedTab
        } else {
            selectedTab = Screen.Home
            currentScreen = Screen.Home
        }
    }

    if (showOnboarding == null) {
        Box(modifier = Modifier.fillMaxSize().background(VantaBlack))
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(VantaBlack)) {
        if (showOnboarding == true) {
            OnboardingScreen(
                onOnboardingComplete = {
                    haptics.click()
                    showOnboarding = false
                    currentScreen = Screen.Home
                    selectedTab = Screen.Home
                }
            )
        } else if (currentScreen == Screen.HealthConnect) {
            HealthConnectScreen(
                onBackClick = {
                    haptics.tick()
                    currentScreen = selectedTab
                }
            )
        } else if (currentScreen == Screen.Settings) {
            SettingsScreen(
                onBackClick = {
                    haptics.tick()
                    currentScreen = selectedTab
                }
            )
        } else if (currentScreen == Screen.PhysiologyDetail) {
            PhysiologyDetailScreen(
                metric = detailMetric,
                onBackClick = {
                    haptics.tick()
                    currentScreen = selectedTab
                },
                onBreatheClick = {
                    haptics.tick()
                    currentScreen = Screen.Breathing
                }
            )
        } else if (currentScreen == Screen.Breathing) {
            BreathingScreen(
                onBackClick = {
                    haptics.tick()
                    currentScreen = selectedTab
                }
            )
        } else {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val initialIndex = bottomNavScreens.indexOf(initialState)
                    val targetIndex = bottomNavScreens.indexOf(targetState)
                    if (targetIndex > initialIndex) {
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> (fullWidth * 0.3f).toInt() }
                        ) + fadeIn(animationSpec = tween(280))).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> -(fullWidth * 0.3f).toInt() }
                            ) + fadeOut(animationSpec = tween(200))
                        )
                    } else {
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> -(fullWidth * 0.3f).toInt() }
                        ) + fadeIn(animationSpec = tween(280))).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> (fullWidth * 0.3f).toInt() }
                            ) + fadeOut(animationSpec = tween(200))
                        )
                    }.using(SizeTransform(clip = false))
                },
                label = "tab_crossfade"
            ) { tab ->
                when (tab) {
                    Screen.Home -> HomeScreen(
                        onHealthConnectClick = {
                            haptics.tick()
                            currentScreen = Screen.HealthConnect
                        },
                        onSettingsClick = {
                            haptics.tick()
                            currentScreen = Screen.Settings
                        },
                        onMetricClick = { metric ->
                            haptics.click()
                            detailMetric = metric
                            currentScreen = Screen.PhysiologyDetail
                        },
                        onStepsClick = {
                            haptics.click()
                            selectedTab = Screen.Steps
                            currentScreen = Screen.Steps
                        }
                    )
                    Screen.Steps -> StepsScreen()
                    Screen.Analytics -> AnalyticsScreen()
                    Screen.AdaptiveCore -> AdaptiveCoreScreen()
                    else -> HomeScreen(
                        onStepsClick = {
                            haptics.click()
                            selectedTab = Screen.Steps
                            currentScreen = Screen.Steps
                        }
                    )
                }
            }
        }

        if (showOnboarding == false) {
            // Top Status Bar Dark Gradient Scrim
            val scrimBrush = remember {
                Brush.verticalGradient(
                    colors = listOf(
                        VantaBlack,
                        VantaBlack.copy(alpha = 0.85f),
                        VantaBlack.copy(alpha = 0.40f),
                        Color.Transparent
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(scrimBrush)
                    .align(Alignment.TopCenter)
            )

            // Floating Glass Bottom Bar
            if (currentScreen != Screen.HealthConnect && currentScreen != Screen.Settings &&
                currentScreen != Screen.PhysiologyDetail && currentScreen != Screen.Breathing) {
                val isAiChatAvailable by aiViewModel.isAiChatAvailable.collectAsState()
                VantaBottomBar(
                    screens = bottomNavScreens,
                    selectedTab = selectedTab,
                    onNavigate = { screen ->
                        if (selectedTab != screen) {
                            haptics.tick()
                            selectedTab = screen
                            currentScreen = screen
                        }
                    },
                    onOpenAiChat = {
                        if (isAiChatAvailable) {
                            isAiChatOpen = true
                        }
                    },
                    isAiChatAvailable = isAiChatAvailable,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Fullscreen/expanding AI Chat overlay with buttery-smooth animations
        AnimatedVisibility(
            visible = isAiChatOpen,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(320, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(260, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(240))
        ) {
            com.vanta.app.ui.screens.VantaAiChatOverlay(
                viewModel = aiViewModel,
                onClose = { isAiChatOpen = false }
            )
        }
    }
}

@Composable
private fun VantaBottomBar(
    screens: List<Screen>,
    selectedTab: Screen,
    onNavigate: (Screen) -> Unit,
    onOpenAiChat: () -> Unit,
    isAiChatAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberVantaHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val barShape = remember { RoundedCornerShape(26.dp) }
        // Left main navigation pill
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(barShape)
                .background(Color(0xFF0A0A0A))
                .border(
                    width = 1.dp,
                    color = Color(0x26FFFFFF),
                    shape = barShape
                )
                .padding(vertical = 4.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            screens.forEach { screen ->
                val selected = selectedTab.route == screen.route
                NavItem(
                    screen   = screen,
                    selected = selected,
                    onClick  = {
                        haptics.tick()
                        onNavigate(screen)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        if (isAiChatAvailable) {
            Spacer(Modifier.width(10.dp))

            // Right circular AI Chat Orb button driven by Gyroscope
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0A0A))
                    .border(
                        width = 1.dp,
                        color = Color(0x26FFFFFF),
                        shape = CircleShape
                    )
                    .clickable {
                        haptics.click()
                        onOpenAiChat()
                    },
                contentAlignment = Alignment.Center
            ) {
                com.vanta.app.ui.components.AiOrbCanvas(
                    size = 38.dp,
                    particleColor = Color(0xFFFF7A45),
                    pointCount = 115
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color(0x59FFFFFF),
        animationSpec = tween(180),
        label = "nav_icon_color"
    )
    val itemBg by animateColorAsState(
        targetValue = if (selected) Color(0x24FFFFFF) else Color.Transparent,
        animationSpec = tween(180),
        label = "nav_bg_color"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(itemBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (screen == Screen.AdaptiveCore) {
            Icon(
                painter = painterResource(com.vanta.app.R.drawable.ecg_heart_24),
                contentDescription = screen.label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                imageVector = screen.icon,
                contentDescription = screen.label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
