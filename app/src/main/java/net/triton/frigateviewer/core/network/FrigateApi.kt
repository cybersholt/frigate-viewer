package net.triton.frigateviewer.core.network

import net.triton.frigateviewer.core.model.FrigateConfig
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.LoginRequest
import net.triton.frigateviewer.core.model.LoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Frigate REST surface (subset needed for v0.1).
 * All calls return `Response<T>` so [safeApiCall] can branch on HTTP status before parsing.
 * Never expose this interface directly to ViewModels — wrap via FrigateRepository.
 */
interface FrigateApi {

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @GET("api/config")
    suspend fun config(): Response<FrigateConfig>

    @GET("api/events")
    suspend fun events(
        @Query("camera") camera: String? = null,
        @Query("label") label: String? = null,
        @Query("after") after: Double? = null,
        @Query("before") before: Long? = null,
        @Query("has_snapshot") hasSnapshot: Int? = null,
        @Query("has_clip") hasClip: Int? = null,
        @Query("limit") limit: Int = 50,
    ): Response<List<FrigateEvent>>

    @GET("api/events/{id}")
    suspend fun event(@Path("id") id: String): Response<FrigateEvent>

    @DELETE("api/events/{id}")
    suspend fun deleteEvent(@Path("id") id: String): Response<Unit>

    @POST("api/events/{id}/retain")
    suspend fun retainEvent(@Path("id") id: String): Response<Unit>

    @DELETE("api/events/{id}/retain")
    suspend fun unretainEvent(@Path("id") id: String): Response<Unit>
}
