package net.triton.frigateviewer.core.network

import kotlinx.serialization.json.JsonElement
import net.triton.frigateviewer.core.model.FrigateConfig
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.LoginRequest
import net.triton.frigateviewer.core.model.LoginResponse
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.RecordingSegment
import net.triton.frigateviewer.core.model.ReviewSegment
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
    suspend fun login(
        @Body body: LoginRequest,
    ): Response<LoginResponse>

    @GET("api/config")
    suspend fun config(): Response<FrigateConfig>

    @GET("api/events")
    suspend fun events(
        @Query("camera") camera: String? = null,
        @Query("label") label: String? = null,
        @Query("zone") zone: String? = null,
        @Query("after") after: Double? = null,
        @Query("before") before: Long? = null,
        @Query("has_snapshot") hasSnapshot: Int? = null,
        @Query("has_clip") hasClip: Int? = null,
        @Query("limit") limit: Int = 50,
    ): Response<List<FrigateEvent>>

    @GET("api/events/{id}")
    suspend fun event(
        @Path("id") id: String,
    ): Response<FrigateEvent>

    @DELETE("api/events/{id}")
    suspend fun deleteEvent(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/events/{id}/retain")
    suspend fun retainEvent(
        @Path("id") id: String,
    ): Response<Unit>

    @DELETE("api/events/{id}/retain")
    suspend fun unretainEvent(
        @Path("id") id: String,
    ): Response<Unit>

    @GET("api/go2rtc/streams")
    suspend fun go2rtcStreams(): Response<Map<String, JsonElement>>

    /** Physical recording segments for [camera] in `[after, before]` — drives VOD seek math. */
    @GET("api/{camera}/recordings")
    suspend fun recordings(
        @Path("camera") camera: String,
        @Query("after") after: Double? = null,
        @Query("before") before: Double? = null,
    ): Response<List<RecordingSegment>>

    /** Gaps in the recording track — drawn on the timeline as "no recording" ranges. */
    @GET("api/recordings/unavailable")
    suspend fun recordingGaps(
        @Query("cameras") cameras: String? = null,
        @Query("after") after: Double? = null,
        @Query("before") before: Double? = null,
        @Query("scale") scale: Int? = null,
    ): Response<List<RecordingGap>>

    /** Severity-classified review segments (alert/detection/significant_motion). */
    @GET("api/review")
    suspend fun review(
        @Query("cameras") cameras: String? = null,
        @Query("labels") labels: String? = null,
        @Query("zones") zones: String? = null,
        @Query("reviewed") reviewed: Int? = null,
        @Query("after") after: Double? = null,
        @Query("before") before: Double? = null,
    ): Response<List<ReviewSegment>>
}
