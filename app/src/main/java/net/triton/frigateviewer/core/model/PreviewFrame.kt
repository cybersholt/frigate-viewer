package net.triton.frigateviewer.core.model

/**
 * A cached preview-frame thumbnail, parsed client-side from a filename returned by
 * `GET api/preview/{camera}/start/{after}/end/{before}/frames`. Frigate names cached frames
 * `preview_{camera}-{unixSeconds}.webp`; the served image itself lives at
 * `api/preview/{fileName}/thumbnail.webp` regardless of requested extension.
 */
data class PreviewFrame(
    val fileName: String,
    val timestampMs: Long,
)

/**
 * Parses [fileNames] (as returned by the frames-list endpoint) into [PreviewFrame]s for
 * [camera]. Filenames that don't match the expected `preview_{camera}-{timestamp}.<ext>`
 * shape are silently skipped rather than crashing — this cache is best-effort scrubbing UI,
 * not core functionality.
 */
fun parsePreviewFrames(
    fileNames: List<String>,
    camera: String,
): List<PreviewFrame> {
    val prefix = "preview_$camera-"
    return fileNames.mapNotNull { fileName ->
        if (!fileName.startsWith(prefix)) return@mapNotNull null
        val withoutPrefix = fileName.removePrefix(prefix)
        val timestampPart = withoutPrefix.substringBeforeLast('.', missingDelimiterValue = "")
        val timestampSeconds = timestampPart.toDoubleOrNull() ?: return@mapNotNull null
        PreviewFrame(fileName = fileName, timestampMs = (timestampSeconds * 1000).toLong())
    }
}

/** Nearest [PreviewFrame] to [targetMs], or null if none is within [maxDistanceMs]. */
fun List<PreviewFrame>.nearestTo(
    targetMs: Long,
    maxDistanceMs: Long,
): PreviewFrame? =
    minByOrNull { kotlin.math.abs(it.timestampMs - targetMs) }
        ?.takeIf { kotlin.math.abs(it.timestampMs - targetMs) <= maxDistanceMs }
