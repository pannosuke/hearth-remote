package net.pcg.hearth.remote.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal const val PHOTO_MAX_EDGE = 1600
internal const val PHOTO_JPEG_QUALITY = 80

/** Largest power of two that still leaves the long edge at least [maxEdge], or 1. */
internal fun sampleSize(width: Int, height: Int, maxEdge: Int = PHOTO_MAX_EDGE): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    val long = max(width, height)
    while (long / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** Width and height that keep the long edge at or under [maxEdge] without enlarging. */
internal fun fittedSize(width: Int, height: Int, maxEdge: Int = PHOTO_MAX_EDGE): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val long = max(width, height)
    if (long <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / long.toFloat()
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * Rotate and shrink a JPEG for upload. The result stays in memory.
 * Nothing is written to the gallery, MediaStore, or a file.
 */
internal fun jpegForUpload(
    jpeg: ByteArray,
    rotationDegrees: Int,
    maxEdge: Int = PHOTO_MAX_EDGE,
    quality: Int = PHOTO_JPEG_QUALITY
): ByteArray {
    if (jpeg.isEmpty()) throw OverlayException("empty photo")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw OverlayException("bad photo")
    val decoded = BitmapFactory.decodeByteArray(
        jpeg,
        0,
        jpeg.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
    ) ?: throw OverlayException("bad photo")
    val upright = rotate(decoded, rotationDegrees)
    val (width, height) = fittedSize(upright.width, upright.height, maxEdge)
    val scaled = if (width == upright.width && height == upright.height) {
        upright
    } else {
        Bitmap.createScaledBitmap(upright, width, height, true)
    }
    return try {
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
            throw OverlayException("bad photo")
        }
        out.toByteArray()
    } finally {
        if (scaled !== upright) scaled.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
    }
}

private fun rotate(source: Bitmap, degrees: Int): Bitmap {
    val turns = ((degrees % 360) + 360) % 360
    if (turns == 0) return source
    val matrix = Matrix().apply { postRotate(turns.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
