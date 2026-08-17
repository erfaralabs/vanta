package com.vanta.app.ui.dev

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 100% Android Device Models and Viewport Resolutions.
 * Covers Samsung Galaxy, Google Pixel, OnePlus, Nothing, Xiaomi, POCO,
 * Motorola, Sony, and Android Foldables.
 */
enum class DevResolution(
    val id: String,
    val modelName: String,
    val brand: String,
    val widthDp: Int?,
    val heightDp: Int?,
    val cornerRadiusDp: Int,
    val description: String
) {
    NATIVE(
        id = "native",
        modelName = "Native Screen",
        brand = "Device",
        widthDp = null,
        heightDp = null,
        cornerRadiusDp = 0,
        description = "Your physical device full display"
    ),

    // ── Samsung Galaxy ───────────────────────────────────────────────────────
    GALAXY_S24(
        id = "galaxy_s24",
        modelName = "Galaxy S24 / S23 / S22",
        brand = "Samsung",
        widthDp = 360,
        heightDp = 780,
        cornerRadiusDp = 34,
        description = "360 × 780 dp · Baseline compact Android"
    ),
    GALAXY_S24_ULTRA(
        id = "galaxy_s24_ultra",
        modelName = "Galaxy S24 Ultra / S23 Ultra",
        brand = "Samsung",
        widthDp = 412,
        heightDp = 915,
        cornerRadiusDp = 24,
        description = "412 × 915 dp · 6.8\" Quad HD+ flat flagship"
    ),
    GALAXY_A54(
        id = "galaxy_a54",
        modelName = "Galaxy A54 / A34 / A15",
        brand = "Samsung",
        widthDp = 384,
        heightDp = 854,
        cornerRadiusDp = 32,
        description = "384 × 854 dp · Best-selling mid-tier Android"
    ),
    GALAXY_Z_FLIP(
        id = "galaxy_z_flip",
        modelName = "Galaxy Z Flip 6 / 5",
        brand = "Samsung",
        widthDp = 360,
        heightDp = 900,
        cornerRadiusDp = 36,
        description = "360 × 900 dp · Ultra-tall 22:9 flip screen"
    ),
    GALAXY_Z_FOLD(
        id = "galaxy_z_fold",
        modelName = "Galaxy Z Fold 6 / 5 (Inner)",
        brand = "Samsung",
        widthDp = 580,
        heightDp = 880,
        cornerRadiusDp = 22,
        description = "580 × 880 dp · Large foldable tablet display"
    ),

    // ── Google Pixel ─────────────────────────────────────────────────────────
    PIXEL_8_PRO(
        id = "pixel_8_pro",
        modelName = "Pixel 8 Pro / 9 Pro XL",
        brand = "Google",
        widthDp = 412,
        heightDp = 915,
        cornerRadiusDp = 36,
        description = "412 × 915 dp · Google Tensor flagship"
    ),
    PIXEL_7A(
        id = "pixel_7a",
        modelName = "Pixel 8a / 7a / 8",
        brand = "Google",
        widthDp = 360,
        heightDp = 800,
        cornerRadiusDp = 32,
        description = "360 × 800 dp · Standard Pixel viewport"
    ),

    // ── OnePlus & Nothing ────────────────────────────────────────────────────
    ONEPLUS_12(
        id = "oneplus_12",
        modelName = "OnePlus 12 / 11",
        brand = "OnePlus",
        widthDp = 412,
        heightDp = 919,
        cornerRadiusDp = 38,
        description = "412 × 919 dp · 2K ProXDR 120Hz display"
    ),
    ONEPLUS_NORD(
        id = "oneplus_nord",
        modelName = "OnePlus Nord 3 / CE",
        brand = "OnePlus",
        widthDp = 392,
        heightDp = 872,
        cornerRadiusDp = 30,
        description = "392 × 872 dp · Popular mid-range OxygenOS"
    ),
    NOTHING_PHONE_2(
        id = "nothing_phone_2",
        modelName = "Nothing Phone (2) / (2a)",
        brand = "Nothing",
        widthDp = 412,
        heightDp = 915,
        cornerRadiusDp = 34,
        description = "412 × 915 dp · Symmetric bezel aesthetic"
    ),

    // ── Xiaomi, Redmi & POCO ─────────────────────────────────────────────────
    XIAOMI_14(
        id = "xiaomi_14",
        modelName = "Xiaomi 14 / 13 Pro",
        brand = "Xiaomi",
        widthDp = 393,
        heightDp = 873,
        cornerRadiusDp = 36,
        description = "393 × 873 dp · HyperOS compact flagship"
    ),
    REDMI_NOTE_13(
        id = "redmi_note_13",
        modelName = "Redmi Note 13 / POCO X6",
        brand = "Xiaomi",
        widthDp = 392,
        heightDp = 872,
        cornerRadiusDp = 30,
        description = "392 × 872 dp · Global volume leader 20:9"
    ),
    REDMI_BUDGET(
        id = "redmi_budget",
        modelName = "Redmi 13C / Galaxy A05",
        brand = "Entry Android",
        widthDp = 360,
        heightDp = 800,
        cornerRadiusDp = 20,
        description = "360 × 800 dp · Budget entry HD+ Android"
    ),

    // ── Motorola & Sony ──────────────────────────────────────────────────────
    MOTO_EDGE(
        id = "moto_edge",
        modelName = "Moto Edge 50 / G Power",
        brand = "Motorola",
        widthDp = 384,
        heightDp = 854,
        cornerRadiusDp = 32,
        description = "384 × 854 dp · Hello UI clean Android"
    ),
    SONY_XPERIA(
        id = "sony_xperia",
        modelName = "Sony Xperia 1 VI / V",
        brand = "Sony",
        widthDp = 384,
        heightDp = 854,
        cornerRadiusDp = 22,
        description = "384 × 854 dp · 19.5:9 Bravia OLED display"
    );

    companion object {
        fun fromId(id: String): DevResolution =
            entries.find { it.id == id } ?: NATIVE
    }
}

object DevResolutionManager {
    private const val PREFS_NAME = "vanta_dev_resolution_prefs"
    private const val KEY_SIMULATOR_ENABLED = "simulator_enabled"
    private const val KEY_SELECTED_RESOLUTION = "selected_resolution"

    var isSimulatorEnabled by mutableStateOf(false)
        private set

    var currentResolution by mutableStateOf(DevResolution.NATIVE)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isSimulatorEnabled = prefs.getBoolean(KEY_SIMULATOR_ENABLED, false)
        val resId = prefs.getString(KEY_SELECTED_RESOLUTION, DevResolution.NATIVE.id) ?: DevResolution.NATIVE.id
        currentResolution = DevResolution.fromId(resId)
    }

    fun setSimulatorActive(context: Context, enabled: Boolean) {
        isSimulatorEnabled = enabled
        if (!enabled) {
            currentResolution = DevResolution.NATIVE
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SIMULATOR_ENABLED, enabled)
            .putString(KEY_SELECTED_RESOLUTION, currentResolution.id)
            .apply()
    }

    fun setResolution(context: Context, resolution: DevResolution) {
        currentResolution = resolution
        isSimulatorEnabled = resolution != DevResolution.NATIVE
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SIMULATOR_ENABLED, isSimulatorEnabled)
            .putString(KEY_SELECTED_RESOLUTION, resolution.id)
            .apply()
    }
}
