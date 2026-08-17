package com.vanta.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.NeonCyan
import com.vanta.app.ui.theme.TextPrimary
import com.vanta.app.ui.theme.TextSecondary
import com.vanta.app.ui.theme.TextTertiary
import com.vanta.app.ui.theme.VantaBlack

/**
 * In-app Privacy Policy for Vanta — open source, everything processed on-device.
 * Also exposes the public source repository via an external redirect.
 */
@Composable
fun PrivacyPolicyScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val githubUrl = "https://github.com/erfaralabs/vanta"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VantaBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "Privacy Policy",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 8.dp)
            ) {
                SectionTitle("Vanta is open source")
                Body(
                    "Vanta's source code is public on GitHub so anyone can verify exactly what the app does. " +
                        "Everything below is implemented on-device — you can read it in the code."
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("View source on GitHub", color = NeonCyan, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("github.com/erfaralabs/vanta", color = TextTertiary, fontSize = 12.sp)
                    }
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = NeonCyan)
                }

                Spacer(Modifier.height(22.dp))
                SectionTitle("What stays on your device")
                Body(
                    "Vanta processes your health and fitness data locally on your phone. " +
                        "Health Connect data (steps, heart rate, sleep, workouts), your profile, " +
                        "daily Recovery/Strain/Energy scores, chat history, and any AI API keys you " +
                        "enter are all stored only on your device — never on our servers."
                )

                Spacer(Modifier.height(18.dp))
                SectionTitle("What leaves your device (and when)")
                Body(
                    "Nothing leaves by default. If you choose to connect an AI coach provider " +
                        "(Gemini, DeepSeek, OpenRouter, or the on-device model), the message text and " +
                        "the physiological numbers included in your prompt are sent to that provider's " +
                        "servers to generate coaching responses — only while the feature is enabled. " +
                        "The on-device model is downloaded once from Hugging Face (~1.2 GB) and contains " +
                        "no personal data. Firebase Crashlytics collects anonymous crash and ANR reports " +
                        "(never health data) to help fix bugs."
                )

                Spacer(Modifier.height(18.dp))
                SectionTitle("Your control")
                Body(
                    "Delete your data anytime from Settings (reset historical data) or by clearing " +
                        "Vanta's data in Android system settings. Revoke Health Connect access at any " +
                        "time in the Android Health Connect app. AI features are opt-in and can be " +
                        "turned off at any time."
                )

                Spacer(Modifier.height(18.dp))
                SectionTitle("Health disclaimer")
                Body(
                    "Vanta is a fitness and performance tool, not a medical device. It does not " +
                        "diagnose, treat, or cure any condition. Always consult a qualified professional " +
                        "for medical advice."
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NeonCyan,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
}
