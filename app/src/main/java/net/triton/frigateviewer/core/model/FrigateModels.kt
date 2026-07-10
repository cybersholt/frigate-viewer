package net.triton.frigateviewer.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LoginRequest(
    val user: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    @SerialName("token") val token: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/**
 * Frigate /api/config returns a deep nested structure that changes between versions.
 * We only deserialize the parts we render — everything else stays JsonElement.
 *
 * `cameras` is a map of cameraName -> CameraConfig. Unknown camera fields are kept raw.
 */
@Serializable
data class FrigateConfig(
    val cameras: Map<String, CameraConfig> = emptyMap(),
    @SerialName("mqtt") val mqtt: MqttConfig? = null,
    @SerialName("ui") val ui: JsonElement? = null,
    @SerialName("objects") val objects: GlobalObjectsConfig? = null,
)

@Serializable
data class GlobalObjectsConfig(
    val track: List<String> = emptyList(),
    val filters: JsonElement? = null,
)

@Serializable
data class CameraConfig(
    val enabled: Boolean = true,
    @SerialName("ffmpeg") val ffmpeg: JsonElement? = null,
    @SerialName("detect") val detect: DetectConfig? = null,
    @SerialName("snapshots") val snapshots: JsonElement? = null,
    @SerialName("record") val record: JsonElement? = null,
    @SerialName("live") val live: LiveConfig? = null,
    /** Zone name → zone config (we only need the names for UI). */
    @SerialName("zones") val zones: Map<String, JsonElement> = emptyMap(),
    /** Per-camera object filter; falls back to global objects.track when absent. */
    @SerialName("objects") val objects: CameraObjectsConfig? = null,
)

@Serializable
data class CameraObjectsConfig(
    val track: List<String> = emptyList(),
    val filters: JsonElement? = null,
)

@Serializable
data class DetectConfig(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 5,
    val enabled: Boolean = true,
)

@Serializable
data class LiveConfig(
    val height: Int? = null,
    val quality: Int? = null,
    @SerialName("stream_name") val streamName: String? = null,
)

@Serializable
data class MqttConfig(
    val enabled: Boolean = false,
    val host: String? = null,
    val port: Int = 1883,
    val topicPrefix: String = "frigate",
    val user: String? = null,
)

/** One physical recording segment on disk. Frigate's `GET api/{camera}/recordings`. */
@Serializable
data class RecordingSegment(
    val id: String,
    val camera: String? = null,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val path: String? = null,
    @SerialName("segment_size") val segmentSize: Double? = null,
    val duration: Double = 0.0,
    val motion: Int? = null,
    val objects: Int? = null,
    @SerialName("dBFS") val dBFS: Double? = null,
)

/** A gap in the recording track (no footage). Frigate's `GET api/recordings/unavailable`. */
@Serializable
data class RecordingGap(
    val id: String,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val motion: Int? = null,
    val objects: Int? = null,
    val duration: Double = 0.0,
)

/**
 * One bucket of Frigate's `GET api/review/activity/motion` — the fine-grained motion waveform
 * (distinct from [ReviewSegment] severity data). `motion` is normalized 0-100 per-hour by the
 * server; `camera` is comma-joined when a bucket spans multiple cameras.
 */
@Serializable
data class MotionActivity(
    @SerialName("start_time") val startTime: Double,
    val motion: Double = 0.0,
    val camera: String? = null,
)

/** Frigate's `GET api/review` — the severity-classified activity feed the web timeline draws. */
@Serializable
data class ReviewSegment(
    val id: String,
    val camera: String,
    /** "alert" | "detection" | "significant_motion" */
    val severity: String,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("thumb_path") val thumbPath: String? = null,
    @SerialName("has_been_reviewed") val hasBeenReviewed: Boolean = false,
    val data: JsonElement? = null,
)

@Serializable
data class FrigateEvent(
    val id: String,
    val camera: String,
    val label: String,
    @SerialName("sub_label") val subLabel: JsonElement? = null,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("top_score") val topScore: Double? = null,
    @SerialName("score") val score: Double? = null,
    val retained: Boolean = false,
    val thumbnail: String? = null,
    val zones: List<String> = emptyList(),
)
