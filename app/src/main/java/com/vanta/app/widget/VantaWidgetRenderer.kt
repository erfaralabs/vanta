package com.vanta.app.widget

import android.graphics.*

/**
 * Ultra-Fast High-Resolution Bitmap Renderer for Vanta Android App Widgets.
 * Draws dark AMOLED glass circular progress arcs & triple WHOOP/Oura rings.
 */
object VantaWidgetRenderer {

    // Palette Colors
    const val COLOR_CYAN     = 0xFF00F5FF.toInt()
    const val COLOR_GREEN    = 0xFF39FF80.toInt()
    const val COLOR_BLUE     = 0xFF0080FF.toInt()
    const val COLOR_BG_TRACK = 0x22FFFFFF

    /**
     * Renders a 1x1 Quick Score widget bitmap with progress arc ring and metric text.
     */
    fun renderQuickScoreBitmap(
        valueText: String,
        labelText: String,
        progressFraction: Float,
        accentColor: Int,
        sizePx: Int = 240
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokeWidthPx = sizePx * 0.075f
        val padding = strokeWidthPx * 1.1f
        val rect = RectF(padding, padding, sizePx - padding, sizePx - padding)

        // Background Track Ring
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = COLOR_BG_TRACK
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rect, -90f, 360f, false, trackPaint)

        // Active Arc Ring
        val sweepAngle = (progressFraction.coerceIn(0f, 1f)) * 360f
        if (sweepAngle > 0.5f) {
            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                color = accentColor
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(rect, -90f, sweepAngle, false, activePaint)
        }

        // Value Text
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val valueY = (sizePx / 2f) - ((valuePaint.descent() + valuePaint.ascent()) / 2f) - (sizePx * 0.05f)
        canvas.drawText(valueText, sizePx / 2f, valueY, valuePaint)

        // Label Text (Dynamic sizing based on length to prevent clipping)
        val isLongLabel = labelText.length >= 7
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = if (isLongLabel) sizePx * 0.080f else sizePx * 0.092f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = if (isLongLabel) 0.02f else 0.05f
        }
        val labelY = valueY + (sizePx * 0.16f)
        canvas.drawText(labelText.uppercase(), sizePx / 2f, labelY, labelPaint)

        return bitmap
    }

    /**
     * Renders 2x2 Core Widget triple concentric rings bitmap without text branding.
     * Outer: Strain (Cyan #00F5FF)
     * Middle: Recovery (Green #39FF80)
     * Inner: Energy (Blue #0080FF)
     */
    fun renderTripleRingsBitmap(
        strain: Double,
        recovery: Int,
        energy: Int,
        sizePx: Int = 360
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val ringStrokePx = sizePx * 0.068f
        val gap = ringStrokePx * 1.30f

        val rOuter = (sizePx / 2f) - (ringStrokePx * 1.1f)
        val rMiddle = rOuter - gap
        val rInner = rMiddle - gap

        // 1. Draw Outer Ring (Strain 0–21)
        drawRingArc(canvas, center, rOuter, ringStrokePx, (strain.toFloat() / 21f).coerceIn(0f, 1f), COLOR_CYAN)

        // 2. Draw Middle Ring (Recovery 0–100)
        drawRingArc(canvas, center, rMiddle, ringStrokePx, (recovery.toFloat() / 100f).coerceIn(0f, 1f), COLOR_GREEN)

        // 3. Draw Inner Ring (Energy 0–100)
        drawRingArc(canvas, center, rInner, ringStrokePx, (energy.toFloat() / 100f).coerceIn(0f, 1f), COLOR_BLUE)

        // Clean minimal AMOLED center — no text branding

        return bitmap
    }

    private fun drawRingArc(
        canvas: Canvas,
        center: Float,
        radius: Float,
        strokeWidthPx: Float,
        fraction: Float,
        color: Int
    ) {
        val rect = RectF(center - radius, center - radius, center + radius, center + radius)

        // Track
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            this.color = COLOR_BG_TRACK
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rect, -90f, 360f, false, trackPaint)

        // Active
        val sweepAngle = fraction * 360f
        if (sweepAngle > 0.5f) {
            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                this.color = color
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(rect, -90f, sweepAngle, false, activePaint)
        }
    }
}
