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
)

@Serializable
data class CameraConfig(
    val enabled: Boolean = true,
    @SerialName("ffmpeg") val ffmpeg: JsonElement? = null,
    @SerialName("detect") val detect: DetectConfig? = null,
    @SerialName("snapshots") val snapshots: JsonElement? = null,
    @SerialName("record") val record: JsonElement? = null,
    @SerialName("live") val live: LiveConfig? = null,
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
