package com.vanta.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.NeonCyan
import com.vanta.app.ui.theme.TextPrimary

/**
 * Word-by-word streaming text component with initial loading preservation.
 *
 * Rules:
 * - If targetText is empty or null -> displays loadingContent (e.g. Consulting Vanta Coach...)
 * - As soon as words land -> reveals in real time with an accent trailing cursor
 * - When isGenerating is true -> shows a glowing accent cursor at the tail
 */
@Composable
fun StreamingTextEffect(
    targetText: String?,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        color = TextPrimary,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal
    ),
    loadingContent: @Composable () -> Unit
) {
    if (targetText.isNullOrBlank()) {
        loadingContent()
        return
    }

    Column(modifier = modifier) {
        Row {
            Text(
                text = targetText,
                style = style,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isGenerating) {
                Text(
                    text = " ✦",
                    color = NeonCyan,
                    style = style.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
