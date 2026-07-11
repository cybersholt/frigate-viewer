package net.triton.frigateviewer.core.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever

/** Feeds an already-downloaded MP4 byte buffer to [MediaMetadataRetriever] with no temp file. */
private class ByteArrayMediaDataSource(
    private val data: ByteArray,
) : MediaDataSource() {
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (position >= data.size) return -1
        val length = minOf(size, (data.size - position).toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/**
 * Extracts a still frame at [offsetUs] (microseconds from the clip's own start) from an
 * in-memory MP4, e.g. Frigate's low-res `preview.mp4` timelapse clips. Returns null on any
 * decode failure (corrupt/truncated clip, offset past the end, unsupported codec) — this is
 * best-effort scrubbing UI, never worth crashing over.
 */
fun extractFrame(
    clipBytes: ByteArray,
    offsetUs: Long,
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(ByteArrayMediaDataSource(clipBytes))
        // CLOSEST (not CLOSEST_SYNC): these low-fps preview clips may have sparse keyframes,
        // so snapping to the nearest sync frame can silently return the same frame every time.
        retriever.getFrameAtTime(offsetUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }.getOrNull().also { retriever.release() }
}
