package com.vanta.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.rememberVantaHaptics
import kotlin.math.min
import kotlin.math.roundToInt

/** Square photo crop for the custom avatar — drag + zoom, circle-masked output. */
@Composable
fun AvatarCropScreen(
    imageUri: Uri,
    onDone: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()

    val source = remember(imageUri) {
        runCatching {
            context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    if (source == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(VantaBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load that photo.", color = TextPrimary)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onCancel) { Text("Back") }
            }
        }
        return
    }

    var zoom by remember { mutableFloatStateOf(2f) }
    // Crop window center in bitmap pixel coordinates — free movement both axes.
    var centerX by remember { mutableFloatStateOf(source.width / 2f) }
    var centerY by remember { mutableFloatStateOf(source.height / 2f) }

    val maxCrop = min(source.width, source.height).toFloat()
    val cropSize = maxCrop / zoom
    val frameSize = 320.dp
    val circleSize = 260.dp

    Box(modifier = Modifier.fillMaxSize().background(VantaBlack)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(VantaSurface2)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("CUSTOM AVATAR", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("Drag to position · zoom to fit", color = TextPrimary, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                }
                Button(
                    onClick = {
                        haptics.click()
                        val size = cropSize.roundToInt()
                        val left = (centerX - cropSize / 2f).roundToInt().coerceIn(0, source.width - size)
                        val top = (centerY - cropSize / 2f).roundToInt().coerceIn(0, source.height - size)
                        val cropped = Bitmap.createBitmap(source, left, top, size, size)
                        onDone(circleMask(cropped))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = VantaBlack),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Crop frame ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(frameSize)
                    .clip(RoundedCornerShape(24.dp))
                    .background(VantaSurface2)
                    .pointerInput(source) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            // Circle px → bitmap px so panning feels 1:1 inside the circle.
                            val bmpPerCirclePx = cropSize / circleSize.toPx()
                            centerX -= pan.x * bmpPerCirclePx
                            centerY -= pan.y * bmpPerCirclePx
                            zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            // Keep the crop window fully inside the image (free movement).
                            centerX = centerX.coerceIn(cropSize / 2f, (source.width - cropSize / 2f))
                            centerY = centerY.coerceIn(cropSize / 2f, (source.height - cropSize / 2f))
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cropPx = cropSize.roundToInt()
                    val left = (centerX - cropSize / 2f).roundToInt().coerceIn(0, source.width - cropPx)
                    val top = (centerY - cropSize / 2f).roundToInt().coerceIn(0, source.height - cropPx)
                    // Draw the visible crop window centered at the (smaller) circle size.
                    val dstPx = circleSize.toPx()
                    val dstOffset = IntOffset(
                        ((size.width - dstPx) / 2f).roundToInt(),
                        ((size.height - dstPx) / 2f).roundToInt()
                    )
                    drawImage(
                        image = source.asImageBitmap(),
                        srcOffset = IntOffset(left, top),
                        srcSize = IntSize(cropPx, cropPx),
                        dstOffset = dstOffset,
                        dstSize = IntSize(dstPx.roundToInt(), dstPx.roundToInt())
                    )
                }
                // Circular guide overlay: dim outside the smaller circle, ring on its edge.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = circleSize.toPx() / 2f
                    val c = center
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius = size.maxDimension)
                    drawCircle(Color.Transparent, radius = r, center = c)
                    drawCircle(color = NeonCyan, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
                }
            }


            Spacer(Modifier.height(28.dp))

            // ── Zoom slider ────────────────────────────────────────────────────
            Text("ZOOM", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Slider(
                value = zoom,
                onValueChange = { zoom = it.coerceIn(1f, 6f) },
                valueRange = 1f..6f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The visible area is saved as your circular avatar.",
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

/** Masks a square bitmap into a circle (transparent corners). */
private fun circleMask(square: Bitmap): Bitmap {
    val size = square.width
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(square, null, Rect(0, 0, size, size), paint)
    return out
}

