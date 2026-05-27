package net.triton.frigateviewer.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.network.FrigateClient
import okio.Path.Companion.toOkioPath
import javax.inject.Singleton

/**
 * Coil ImageLoader bound to the active server's OkHttp client (auth headers + pinned cert).
 *
 * Disk cache: 256 MB for event snapshots and thumbnails (stable URLs).
 * Live camera tiles and snapshot tiles opt out via diskCachePolicy = DISABLED in their requests.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: FrigateClient,
        serverRepo: ServerRepository,
    ): ImageLoader {
        val fetcherFactory =
            OkHttpNetworkFetcherFactory(
                callFactory = {
                    runBlocking {
                        val server = serverRepo.activeServer()
                        if (server != null) {
                            client.clientFor(server)
                        } else {
                            okhttp3.OkHttpClient()
                        }
                    }
                },
            )
        return ImageLoader
            .Builder(context)
            .components { add(fetcherFactory) }
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, percent = 0.20)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }.crossfade(false)
            .build()
    }
}
