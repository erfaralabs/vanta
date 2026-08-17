package com.vanta.app.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vanta.app.R
import java.io.File

/**
 * Resolves the user's avatar (one of the two built-in avatars, or their own
 * cropped photo) into a drawable or a bitmap from internal storage.
 */
object AvatarHelper {
    const val KEY_AVATAR_1 = "avatar1"
    const val KEY_AVATAR_2 = "avatar2"
    const val KEY_CUSTOM = "custom"

    /** The avatar choices offered in onboarding + settings, in display order. */
    val avatarKeys = listOf(KEY_AVATAR_1, KEY_AVATAR_2, KEY_CUSTOM)

    /** Drawable for a built-in avatar (custom is stored as a file instead). */
    fun drawableRes(key: String?): Int = when (key) {
        KEY_AVATAR_2 -> R.drawable.avatar2
        else -> R.drawable.avatar1
    }

    /** Where the user's own cropped avatar lives. */
    fun customAvatarFile(context: Context): File = File(context.filesDir, "avatar_custom.png")

    /** Loads the custom avatar bitmap, or null when none has been saved. */
    fun loadCustomAvatar(context: Context): Bitmap? {
        val f = customAvatarFile(context)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Persists a cropped avatar photo (circular PNG) to internal storage. */
    fun saveCustomAvatar(context: Context, bitmap: Bitmap): Boolean = runCatching {
        customAvatarFile(context).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    }.getOrDefault(false)

    /** Deletes a previously saved custom avatar (e.g. when switching back to a preset). */
    fun deleteCustomAvatar(context: Context) {
        runCatching { customAvatarFile(context).delete() }
    }
}
