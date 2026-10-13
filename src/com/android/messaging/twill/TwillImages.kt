package com.android.messaging.twill

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes local images (attachments, avatars) at a size fit for the screen. */
internal object TwillImages {
    const val PREVIEW_MAX_SIDE = 1080
    const val VIEWER_MAX_SIDE = 2560
    private const val AVATAR_SIDE = 160

    private val avatarCache = LruCache<String, Bitmap>(100)

    /** A downscaled bitmap of the image at [path], or null if it can't be read. */
    suspend fun loadPreview(path: String, maxSide: Int = PREVIEW_MAX_SIDE): Bitmap? =
        withContext(Dispatchers.IO) { decodeFile(File(path), maxSide) }

    /** A small, cached avatar from a local file (Delta Chat keeps profile pictures as files). */
    suspend fun loadLocalAvatar(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        val key = path + ":" + file.lastModified()
        avatarCache.get(key) ?: decodeFile(file, AVATAR_SIDE)?.also { avatarCache.put(key, it) }
    }

    private fun decodeFile(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
