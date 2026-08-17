package com.vanta.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vanta.app.ui.theme.NeonCyan
import com.vanta.app.ui.utils.AvatarHelper

/**
 * Renders the user's avatar — one of the built-in avatars or their custom photo.
 * Always circle-cropped and center-cropped so any source is "properly cropped".
 */
@Composable
fun AvatarImage(
    avatarKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    if (avatarKey == AvatarHelper.KEY_CUSTOM) {
        val bmp = remember(avatarKey) { AvatarHelper.loadCustomAvatar(context) }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Avatar",
                contentScale = contentScale,
                modifier = modifier.clip(CircleShape)
            )
            return
        }
    }
    Image(
        painter = painterResource(id = AvatarHelper.drawableRes(avatarKey)),
        contentDescription = "Avatar",
        contentScale = contentScale,
        modifier = modifier.clip(CircleShape)
    )
}

/**
 * A selectable avatar option — rendered as a finished, properly-cropped circle
 * with a highlight ring when selected.
 */
@Composable
fun AvatarOption(
    avatarKey: String,
    selected: Boolean,
    size: Dp = 64.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) NeonCyan else Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
            .clickable { onClick() }
    ) {
        AvatarImage(
            avatarKey = avatarKey,
            modifier = Modifier.size(size)
        )
    }
}
