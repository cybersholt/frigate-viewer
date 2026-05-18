package net.triton.frigateviewer.core.image

import android.content.Context
import coil3.ImageLoader
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
import javax.inject.Singleton

/**
 * Coil ImageLoader bound to the active server's OkHttp client (auth headers + pinned cert).
 *
 * Snapshots are served from `/api/<cam>/latest.jpg` or `/api/events/<id>/snapshot.jpg`,
 * both of which require the same auth as REST calls. We piggyback on FrigateClient's
 * per-server OkHttpClient so Authorization + cookies + cert pinning all apply.
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
        // Lazily produce an OkHttpClient bound to the active server. Coil calls this once
        // per request via the fetcher factory.
        val fetcherFactory = OkHttpNetworkFetcherFactory(
            callFactory = {
                runBlocking {
                    val server = serverRepo.activeServer()
                    if (server != null) client.clientFor(server)
                    else okhttp3.OkHttpClient()
                }
            }
        )
        return ImageLoader.Builder(context)
            .components { add(fetcherFactory) }
            .crossfade(true)
            .build()
    }
}
