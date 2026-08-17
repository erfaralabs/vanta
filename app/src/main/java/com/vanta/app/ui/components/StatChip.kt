package com.vanta.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.vanta.app.ui.theme.TextSecondary

/**
 * Small label+value column chip used in chart preview cards and workout screens.
 */
@Composable
fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text  = value,
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold)
        )
        Text(
            text  = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
