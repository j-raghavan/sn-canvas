package com.sncanvas.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Where an image element's pixels come from (FR22): [ImageCache] on the device. */
fun interface ImageSource {
    /** [image]'s pixels, or null when they can't be had, such as for a missing file. */
    fun bitmap(image: ImageData): Bitmap?

    companion object {
        /** No pixels at all: every image draws as missing. */
        val NONE = ImageSource { null }
    }
}

/**
 * The canvas's images (FR22): copies a picked image into the canvas folder's
 * images folder ([import]) and decodes each for drawing, scaled down to at most
 * [MAX_SIDE_PX] a side, keeping the most recently drawn in memory. The live
 * view and the note thumbnail share it, and the thumbnail renders off the UI
 * thread, so every call is synchronized. [folder] is the shown canvas's,
 * set as it loads.
 */
class ImageCache : ImageSource {
    private val decoded =
        object : LruCache<String, Bitmap>(MAX_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    // Files that failed to decode, so a missing image isn't read again on every frame.
    private val unreadable = mutableSetOf<String>()

    /** The shown canvas's images folder; null before a canvas loads. */
    var folder: File? = null
        @Synchronized get

        @Synchronized set(value) {
            field = value
            unreadable.clear()
        }

    @Synchronized
    override fun bitmap(image: ImageData): Bitmap? {
        val file = File(folder ?: return null, image.file)
        return decoded.get(file.path) ?: if (file.path in unreadable) null else decode(file)
    }

    /**
     * Copies the image at [source] into the folder [into] as [fileName], its
     * pixels decoded ahead of the first draw; null, copying nothing, when
     * [source] is not an image this device can decode.
     */
    fun import(
        source: File,
        into: File,
        fileName: String,
    ): ImageData? {
        val (width, height) = sizeOf(source) ?: return null
        val target = File(into, fileName)
        // copyTo creates the images folder on the first import.
        source.copyTo(target, overwrite = true)
        synchronized(this) { decode(target) }
        return ImageData(fileName, width, height)
    }

    /** Decodes [file], scaled down to [MAX_SIDE_PX] a side, into the cache; called holding the lock. */
    private fun decode(file: File): Bitmap? {
        val bitmap =
            sizeOf(file)?.let { (width, height) ->
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(width, height)) }
                BitmapFactory.decodeFile(file.path, options)
            }
        if (bitmap == null) unreadable += file.path else decoded.put(file.path, bitmap)
        return bitmap
    }

    private companion object {
        const val MAX_SIDE_PX = 1600
        const val MAX_BYTES = 32 * 1024 * 1024

        /** [file]'s size in pixels, read from its header alone; null when it isn't an image. */
        fun sizeOf(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        }

        /** The power-of-two step that brings [longestSide] down to [MAX_SIDE_PX] or less. */
        fun sampleSize(longestSide: Int): Int {
            var sample = 1
            while (longestSide / sample > MAX_SIDE_PX) sample *= 2
            return sample
        }
    }
}
