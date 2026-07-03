package net.triton.frigateviewer.feature.cameras

sealed class CameraStreamState {
    object Skeleton : CameraStreamState()

    data class LoadingWithCache(
        val cachedAt: Long,
    ) : CameraStreamState()

    object Live : CameraStreamState()

    data class Offline(
        val reason: String? = null,
    ) : CameraStreamState()
}

internal fun formatTimeAgo(epochMillis: Long): String {
    val diffSecs = ((System.currentTimeMillis() - epochMillis) / 1000).coerceAtLeast(0)
    return when {
        diffSecs < 60 -> "${diffSecs}s ago"
        diffSecs < 3_600 -> "${diffSecs / 60}m ago"
        diffSecs < 86_400 -> "${diffSecs / 3_600}h ago"
        else -> "${diffSecs / 86_400}d ago"
    }
}
