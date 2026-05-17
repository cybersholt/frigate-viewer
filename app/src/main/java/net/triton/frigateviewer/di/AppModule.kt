package net.triton.frigateviewer.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.triton.frigateviewer.BuildConfig
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.network.AuthInterceptor
import net.triton.frigateviewer.core.network.FrigateApi
import net.triton.frigateviewer.core.network.TokenRefreshAuthenticator
import net.triton.frigateviewer.core.network.TrustConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "frigate_servers")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePrefs(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.serverDataStore

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true   // Frigate adds fields per version; never crash on extras
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        serverRepo: ServerRepository,
        credentialStore: CredentialStore,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(AuthInterceptor(serverRepo, credentialStore))
            .authenticator(
                TokenRefreshAuthenticator(serverRepo, credentialStore) { _ -> false }
                // Repo-injected refresher gets wired in FrigateRepository at runtime; for v0.1
                // we don't refresh inside OkHttp to avoid recursive Hilt graph. Refresh happens
                // explicitly on a failed call from ViewModel.
            )

        // Pinned cert (if user imported one for the active server) — async load on graph init.
        // For v0.1 we don't apply per-request pinning here; pinning lives on a per-server
        // OkHttp builder fork in network test screen. Production hardening hook:
        // TrustConfig.applyPinnedCertificate(builder, credentialStore.pinnedCertSync(...))

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json, serverRepo: ServerRepository): Retrofit {
        // Base URL is required by Retrofit but we override per call via @Url in future endpoints
        // OR re-create Retrofit on server change. For v0.1, we rebuild via ServerScopedRetrofit.
        val placeholder = "http://localhost/"
        return Retrofit.Builder()
            .baseUrl(placeholder)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideFrigateApi(retrofit: Retrofit): FrigateApi = retrofit.create(FrigateApi::class.java)
}
