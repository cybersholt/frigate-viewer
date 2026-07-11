package net.triton.frigateviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserSettingsRepository
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        private val preferSubStreamKey = booleanPreferencesKey(KEY_PREFER_SUB_STREAM)
        private val themeModeKey = stringPreferencesKey(KEY_THEME_MODE)
        private val accentColorKey = longPreferencesKey(KEY_ACCENT_COLOR)
        private val useWallpaperColorKey = booleanPreferencesKey(KEY_USE_WALLPAPER_COLOR)
        private val paletteStyleKey = stringPreferencesKey(KEY_PALETTE_STYLE)
        private val amoledBlackKey = booleanPreferencesKey(KEY_AMOLED_BLACK)
        private val autoRefreshCamerasKey = booleanPreferencesKey(KEY_AUTO_REFRESH_CAMERAS)
        private val cameraGridColumnsKey = intPreferencesKey(KEY_CAMERA_GRID_COLUMNS)
        private val hideEventImageInStreamKey = booleanPreferencesKey(KEY_HIDE_EVENT_IMAGE_IN_STREAM)
        private val autoLandscapeOnStreamKey = booleanPreferencesKey(KEY_AUTO_LANDSCAPE_ON_STREAM)
        private val autoRefreshIntervalKey = intPreferencesKey(KEY_AUTO_REFRESH_INTERVAL)
        private val eventPhotoPreferenceKey = stringPreferencesKey(KEY_EVENT_PHOTO_PREFERENCE)
        private val liveStreamOptionKey = stringPreferencesKey(KEY_LIVE_STREAM_OPTION)
        private val showBoundingBoxesKey = booleanPreferencesKey(KEY_SHOW_BOUNDING_BOXES)
        private val eventGridColumnsKey = intPreferencesKey(KEY_EVENT_GRID_COLUMNS)
        private val dateFormatKey = stringPreferencesKey(KEY_DATE_FORMAT)
        private val cameraOrderKey = stringPreferencesKey(KEY_CAMERA_ORDER)
        private val hiddenCamerasKey = stringPreferencesKey(KEY_HIDDEN_CAMERAS)
        private val showCameraSwipeActionsKey = booleanPreferencesKey(KEY_SHOW_CAMERA_SWIPE_ACTIONS)
        private val lastKnownCameraNameListKey = stringPreferencesKey(KEY_LAST_KNOWN_CAMERA_NAMES)
        private val contrastLevelKey = intPreferencesKey(KEY_CONTRAST_LEVEL)
        private val cardCornerRadiusKey = intPreferencesKey(KEY_CARD_CORNER_RADIUS)
        private val cardBorderWidthKey = intPreferencesKey(KEY_CARD_BORDER_WIDTH)
        private val customAccentColorsKey = stringPreferencesKey(KEY_CUSTOM_ACCENT_COLORS)
        private val cameraStreamOverridesKey = stringPreferencesKey(KEY_CAMERA_STREAM_OVERRIDES)
        private val showLastImageWhileLoadingKey = booleanPreferencesKey(KEY_SHOW_LAST_IMAGE_WHILE_LOADING)

        private val gridStreamTypeKey = stringPreferencesKey(KEY_GRID_STREAM_TYPE)
        private val fullscreenStreamTypeKey = stringPreferencesKey(KEY_FULLSCREEN_STREAM_TYPE)
        private val preferSubStreamGridKey = booleanPreferencesKey(KEY_PREFER_SUB_STREAM_GRID)
        private val preferSubStreamFullscreenKey = booleanPreferencesKey(KEY_PREFER_SUB_STREAM_FULLSCREEN)
        private val keepOffscreenTilesAliveKey = booleanPreferencesKey(KEY_KEEP_OFFSCREEN_TILES_ALIVE)
        private val rtspReconnectAttemptsKey = intPreferencesKey(KEY_RTSP_RECONNECT_ATTEMPTS)
        private val rtspReconnectBaseDelaySecondsKey = intPreferencesKey(KEY_RTSP_RECONNECT_BASE_DELAY_SECONDS)

        val preferSubStream: Flow<Boolean> = store.data.map { it[preferSubStreamKey] ?: false }
        val themeMode: Flow<String> = store.data.map { it[themeModeKey] ?: "SYSTEM" }
        val accentColor: Flow<Long> = store.data.map { it[accentColorKey] ?: 0xFF6750A4 }
        val useWallpaperColor: Flow<Boolean> = store.data.map { it[useWallpaperColorKey] ?: false }
        val paletteStyle: Flow<String> = store.data.map { it[paletteStyleKey] ?: "TonalSpot" }
        val amoledBlack: Flow<Boolean> = store.data.map { it[amoledBlackKey] ?: false }
        val autoRefreshCameras: Flow<Boolean> = store.data.map { it[autoRefreshCamerasKey] ?: false }
        val cameraGridColumns: Flow<Int> = store.data.map { it[cameraGridColumnsKey] ?: 2 }
        val hideEventImageInStream: Flow<Boolean> = store.data.map { it[hideEventImageInStreamKey] ?: false }
        val autoLandscapeOnStream: Flow<Boolean> = store.data.map { it[autoLandscapeOnStreamKey] ?: false }
        val autoRefreshInterval: Flow<Int> = store.data.map { it[autoRefreshIntervalKey] ?: 3 }
        val eventPhotoPreference: Flow<String> = store.data.map { it[eventPhotoPreferenceKey] ?: "snapshot" }
        val liveStreamOption: Flow<String> = store.data.map { it[liveStreamOptionKey] ?: "webrtc" }

        /** Max RTSP auto-reconnect attempts before surfacing a terminal error. Range 0–10. */
        val rtspReconnectAttempts: Flow<Int> = store.data.map { it[rtspReconnectAttemptsKey] ?: 2 }

        /** Base delay (seconds) before the first RTSP reconnect attempt; doubles each subsequent attempt. */
        val rtspReconnectBaseDelaySeconds: Flow<Int> = store.data.map { it[rtspReconnectBaseDelaySecondsKey] ?: 2 }

        val gridStreamType: Flow<String> = store.data.map { it[gridStreamTypeKey] ?: "snapshot" }
        val fullscreenStreamType: Flow<String> = store.data.map { it[fullscreenStreamTypeKey] ?: it[liveStreamOptionKey] ?: "webrtc" }
        val preferSubStreamGrid: Flow<Boolean> = store.data.map { it[preferSubStreamGridKey] ?: it[preferSubStreamKey] ?: true }
        val preferSubStreamFullscreen: Flow<Boolean> =
            store.data.map {
                it[preferSubStreamFullscreenKey] ?: it[preferSubStreamKey] ?: false
            }
        val keepOffscreenTilesAlive: Flow<Boolean> = store.data.map { it[keepOffscreenTilesAliveKey] ?: false }

        /**
         * Per-camera live-mode override (camera name -> "webrtc"/"rtsp"/"snapshot").
         * A camera absent from this map falls back to [liveStreamOption].
         */
        val cameraStreamOverrides: Flow<Map<String, String>> =
            store.data.map { prefs ->
                prefs[cameraStreamOverridesKey]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.mapNotNull { entry ->
                        val parts = entry.split(":", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }?.toMap() ?: emptyMap()
            }
        val showBoundingBoxes: Flow<Boolean> = store.data.map { it[showBoundingBoxesKey] ?: true }
        val eventGridColumns: Flow<Int> = store.data.map { it[eventGridColumnsKey] ?: 1 }
        val dateFormat: Flow<String> = store.data.map { it[dateFormatKey] ?: "descriptive" }

        /** Ordered list of camera names; empty = use API order. */
        val cameraOrder: Flow<List<String>> =
            store.data.map { prefs ->
                prefs[cameraOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            }

        /** Set of camera names that are hidden from the grid. */
        val hiddenCameras: Flow<Set<String>> =
            store.data.map { prefs ->
                prefs[hiddenCamerasKey]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            }

        /** Whether swipe gestures are active on camera tiles. Default ON. */
        val showCameraSwipeActions: Flow<Boolean> = store.data.map { it[showCameraSwipeActionsKey] ?: true }

        /** Camera names from last successful load, for skeleton background images. */
        val lastKnownCameraNames: Flow<List<String>> =
            store.data.map { prefs ->
                prefs[lastKnownCameraNameListKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            }

        val contrastLevel: Flow<Int> = store.data.map { it[contrastLevelKey] ?: 0 }
        val cardCornerRadius: Flow<Int> = store.data.map { it[cardCornerRadiusKey] ?: 12 }
        val cardBorderWidth: Flow<Int> = store.data.map { it[cardBorderWidthKey] ?: 0 }
        val customAccentColors: Flow<List<Long>> =
            store.data.map { prefs ->
                prefs[customAccentColorsKey]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            }

        suspend fun setPreferSubStream(prefer: Boolean) = store.edit { it[preferSubStreamKey] = prefer }

        suspend fun setGridStreamType(type: String) = store.edit { it[gridStreamTypeKey] = type }

        suspend fun setFullscreenStreamType(type: String) = store.edit { it[fullscreenStreamTypeKey] = type }

        suspend fun setPreferSubStreamGrid(prefer: Boolean) = store.edit { it[preferSubStreamGridKey] = prefer }

        suspend fun setPreferSubStreamFullscreen(prefer: Boolean) = store.edit { it[preferSubStreamFullscreenKey] = prefer }

        suspend fun setKeepOffscreenTilesAlive(keep: Boolean) = store.edit { it[keepOffscreenTilesAliveKey] = keep }

        suspend fun setRtspReconnectAttempts(attempts: Int) = store.edit { it[rtspReconnectAttemptsKey] = attempts.coerceIn(0, 10) }

        suspend fun setRtspReconnectBaseDelaySeconds(seconds: Int) =
            store.edit { it[rtspReconnectBaseDelaySecondsKey] = seconds.coerceIn(1, 30) }

        suspend fun setThemeMode(mode: String) = store.edit { it[themeModeKey] = mode }

        suspend fun setAccentColor(color: Long) = store.edit { it[accentColorKey] = color }

        suspend fun setUseWallpaperColor(use: Boolean) = store.edit { it[useWallpaperColorKey] = use }

        suspend fun setPaletteStyle(style: String) = store.edit { it[paletteStyleKey] = style }

        suspend fun setAmoledBlack(enabled: Boolean) = store.edit { it[amoledBlackKey] = enabled }

        suspend fun setAutoRefreshCameras(enabled: Boolean) = store.edit { it[autoRefreshCamerasKey] = enabled }

        suspend fun setCameraGridColumns(columns: Int) = store.edit { it[cameraGridColumnsKey] = columns }

        suspend fun setHideEventImageInStream(hide: Boolean) = store.edit { it[hideEventImageInStreamKey] = hide }

        suspend fun setAutoLandscapeOnStream(auto: Boolean) = store.edit { it[autoLandscapeOnStreamKey] = auto }

        suspend fun setAutoRefreshInterval(seconds: Int) = store.edit { it[autoRefreshIntervalKey] = seconds }

        suspend fun setEventPhotoPreference(pref: String) = store.edit { it[eventPhotoPreferenceKey] = pref }

        suspend fun setLiveStreamOption(option: String) = store.edit { it[liveStreamOptionKey] = option }

        /** Set [camera]'s live-mode override, or clear it (revert to the global default) when [mode] is null. */
        suspend fun setCameraStreamOverride(
            camera: String,
            mode: String?,
        ) = store.edit { prefs ->
            val current =
                prefs[cameraStreamOverridesKey]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.mapNotNull { entry ->
                        val parts = entry.split(":", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }?.toMap() ?: emptyMap()
            val updated = if (mode != null) current + (camera to mode) else current - camera
            prefs[cameraStreamOverridesKey] = updated.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

        suspend fun setShowBoundingBoxes(show: Boolean) = store.edit { it[showBoundingBoxesKey] = show }

        suspend fun setEventGridColumns(columns: Int) = store.edit { it[eventGridColumnsKey] = columns }

        suspend fun setDateFormat(fmt: String) = store.edit { it[dateFormatKey] = fmt }

        suspend fun setCameraOrder(names: List<String>) = store.edit { it[cameraOrderKey] = names.joinToString(",") }

        suspend fun setHiddenCameras(cameras: Set<String>) = store.edit { it[hiddenCamerasKey] = cameras.joinToString(",") }

        suspend fun setShowCameraSwipeActions(show: Boolean) = store.edit { it[showCameraSwipeActionsKey] = show }

        suspend fun setLastKnownCameraNames(names: List<String>) = store.edit { it[lastKnownCameraNameListKey] = names.joinToString(",") }

        suspend fun setContrastLevel(level: Int) = store.edit { it[contrastLevelKey] = level }

        suspend fun setCardCornerRadius(radius: Int) = store.edit { it[cardCornerRadiusKey] = radius }

        suspend fun setCardBorderWidth(width: Int) = store.edit { it[cardBorderWidthKey] = width }

        suspend fun setCustomAccentColors(colors: List<Long>) = store.edit { it[customAccentColorsKey] = colors.joinToString(",") }

        /** Show the last cached snapshot under the loading spinner instead of a blank fill. Default ON. */
        val showLastImageWhileLoading: Flow<Boolean> = store.data.map { it[showLastImageWhileLoadingKey] ?: true }

        suspend fun setShowLastImageWhileLoading(show: Boolean) = store.edit { it[showLastImageWhileLoadingKey] = show }

        /**
         * Serializes every exportable preference to JSON (#15) — deliberately app-preferences-only:
         * servers and credentials live in `ServerRepository`/`CredentialStore`, a separate system,
         * and are never touched here. [KEY_LAST_KNOWN_CAMERA_NAMES] is excluded too — it's a
         * skeleton-placeholder cache, not a real user preference, and restoring stale camera names
         * on a different install/server would be actively wrong.
         */
        suspend fun exportPreferencesJson(): String {
            val prefs = store.data.first()
            val obj =
                buildJsonObject {
                    put("version", EXPORT_VERSION)
                    putJsonObject("preferences") {
                        for ((keyName, type) in EXPORTABLE_KEYS) {
                            when (type) {
                                PrefType.BOOL -> prefs[booleanPreferencesKey(keyName)]?.let { put(keyName, it) }
                                PrefType.INT -> prefs[intPreferencesKey(keyName)]?.let { put(keyName, it) }
                                PrefType.LONG -> prefs[longPreferencesKey(keyName)]?.let { put(keyName, it) }
                                PrefType.STRING -> prefs[stringPreferencesKey(keyName)]?.let { put(keyName, it) }
                            }
                        }
                    }
                }
            return obj.toString()
        }

        /**
         * Restores preferences from [json] (produced by [exportPreferencesJson]). Returns false
         * (no changes applied) if the file isn't a recognized export — an unknown/future version,
         * or missing the expected shape — rather than partially applying something unvalidated.
         */
        suspend fun importPreferencesJson(json: String): Boolean {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return false
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: return false
            if (version != EXPORT_VERSION) return false
            val preferences = root["preferences"]?.jsonObject ?: return false
            store.edit { mutablePrefs ->
                for ((keyName, type) in EXPORTABLE_KEYS) {
                    val value = preferences[keyName] ?: continue
                    runCatching {
                        when (type) {
                            PrefType.BOOL -> mutablePrefs[booleanPreferencesKey(keyName)] = value.jsonPrimitive.boolean
                            PrefType.INT -> mutablePrefs[intPreferencesKey(keyName)] = value.jsonPrimitive.int
                            PrefType.LONG -> mutablePrefs[longPreferencesKey(keyName)] = value.jsonPrimitive.long
                            PrefType.STRING -> mutablePrefs[stringPreferencesKey(keyName)] = value.jsonPrimitive.content
                        }
                    }
                }
            }
            return true
        }

        companion object {
            private const val KEY_PREFER_SUB_STREAM = "prefer_sub_stream_v1"
            private const val KEY_THEME_MODE = "theme_mode_v1"
            private const val KEY_ACCENT_COLOR = "accent_color_v1"
            private const val KEY_USE_WALLPAPER_COLOR = "use_wallpaper_color_v1"
            private const val KEY_PALETTE_STYLE = "palette_style_v1"
            private const val KEY_AMOLED_BLACK = "amoled_black_v1"
            private const val KEY_AUTO_REFRESH_CAMERAS = "auto_refresh_cameras_v1"
            private const val KEY_CAMERA_GRID_COLUMNS = "camera_grid_columns_v1"
            private const val KEY_HIDE_EVENT_IMAGE_IN_STREAM = "hide_event_image_in_stream_v1"
            private const val KEY_AUTO_LANDSCAPE_ON_STREAM = "auto_landscape_on_stream_v1"
            private const val KEY_AUTO_REFRESH_INTERVAL = "auto_refresh_interval_v1"
            private const val KEY_EVENT_PHOTO_PREFERENCE = "event_photo_preference_v1"
            private const val KEY_LIVE_STREAM_OPTION = "live_stream_option_v1"
            private const val KEY_SHOW_BOUNDING_BOXES = "show_bounding_boxes_v1"
            private const val KEY_EVENT_GRID_COLUMNS = "event_grid_columns_v1"
            private const val KEY_DATE_FORMAT = "date_format_v1"
            private const val KEY_CAMERA_ORDER = "camera_order_v1"
            private const val KEY_HIDDEN_CAMERAS = "hidden_cameras_v1"
            private const val KEY_SHOW_CAMERA_SWIPE_ACTIONS = "show_camera_swipe_actions_v1"
            private const val KEY_LAST_KNOWN_CAMERA_NAMES = "last_known_camera_names_v1"
            private const val KEY_CONTRAST_LEVEL = "contrast_level_v1"
            private const val KEY_CARD_CORNER_RADIUS = "card_corner_radius_v1"
            private const val KEY_CARD_BORDER_WIDTH = "card_border_width_v1"
            private const val KEY_CUSTOM_ACCENT_COLORS = "custom_accent_colors_v1"
            private const val KEY_CAMERA_STREAM_OVERRIDES = "camera_stream_overrides_v1"
            private const val KEY_SHOW_LAST_IMAGE_WHILE_LOADING = "show_last_image_while_loading_v1"

            private const val KEY_GRID_STREAM_TYPE = "grid_stream_type_v1"
            private const val KEY_FULLSCREEN_STREAM_TYPE = "fullscreen_stream_type_v1"
            private const val KEY_PREFER_SUB_STREAM_GRID = "prefer_sub_stream_grid_v1"
            private const val KEY_PREFER_SUB_STREAM_FULLSCREEN = "prefer_sub_stream_fullscreen_v1"
            private const val KEY_KEEP_OFFSCREEN_TILES_ALIVE = "keep_offscreen_tiles_alive_v1"
            private const val KEY_RTSP_RECONNECT_ATTEMPTS = "rtsp_reconnect_attempts_v1"
            private const val KEY_RTSP_RECONNECT_BASE_DELAY_SECONDS = "rtsp_reconnect_base_delay_seconds_v1"

            /** Bump on any breaking change to [EXPORTABLE_KEYS]' shape; [importPreferencesJson] rejects a mismatch. */
            private const val EXPORT_VERSION = 1

            /**
             * Every preference [exportPreferencesJson]/[importPreferencesJson] round-trip — add new
             * keys here when adding a new setting above. [KEY_LAST_KNOWN_CAMERA_NAMES] is
             * intentionally absent (see [exportPreferencesJson]'s doc).
             */
            private val EXPORTABLE_KEYS: Map<String, PrefType> =
                mapOf(
                    KEY_PREFER_SUB_STREAM to PrefType.BOOL,
                    KEY_THEME_MODE to PrefType.STRING,
                    KEY_ACCENT_COLOR to PrefType.LONG,
                    KEY_USE_WALLPAPER_COLOR to PrefType.BOOL,
                    KEY_PALETTE_STYLE to PrefType.STRING,
                    KEY_AMOLED_BLACK to PrefType.BOOL,
                    KEY_AUTO_REFRESH_CAMERAS to PrefType.BOOL,
                    KEY_CAMERA_GRID_COLUMNS to PrefType.INT,
                    KEY_HIDE_EVENT_IMAGE_IN_STREAM to PrefType.BOOL,
                    KEY_AUTO_LANDSCAPE_ON_STREAM to PrefType.BOOL,
                    KEY_AUTO_REFRESH_INTERVAL to PrefType.INT,
                    KEY_EVENT_PHOTO_PREFERENCE to PrefType.STRING,
                    KEY_LIVE_STREAM_OPTION to PrefType.STRING,
                    KEY_SHOW_BOUNDING_BOXES to PrefType.BOOL,
                    KEY_EVENT_GRID_COLUMNS to PrefType.INT,
                    KEY_DATE_FORMAT to PrefType.STRING,
                    KEY_CAMERA_ORDER to PrefType.STRING,
                    KEY_HIDDEN_CAMERAS to PrefType.STRING,
                    KEY_SHOW_CAMERA_SWIPE_ACTIONS to PrefType.BOOL,
                    KEY_CONTRAST_LEVEL to PrefType.INT,
                    KEY_CARD_CORNER_RADIUS to PrefType.INT,
                    KEY_CARD_BORDER_WIDTH to PrefType.INT,
                    KEY_CUSTOM_ACCENT_COLORS to PrefType.STRING,
                    KEY_CAMERA_STREAM_OVERRIDES to PrefType.STRING,
                    KEY_SHOW_LAST_IMAGE_WHILE_LOADING to PrefType.BOOL,
                    KEY_GRID_STREAM_TYPE to PrefType.STRING,
                    KEY_FULLSCREEN_STREAM_TYPE to PrefType.STRING,
                    KEY_PREFER_SUB_STREAM_GRID to PrefType.BOOL,
                    KEY_PREFER_SUB_STREAM_FULLSCREEN to PrefType.BOOL,
                    KEY_KEEP_OFFSCREEN_TILES_ALIVE to PrefType.BOOL,
                    KEY_RTSP_RECONNECT_ATTEMPTS to PrefType.INT,
                    KEY_RTSP_RECONNECT_BASE_DELAY_SECONDS to PrefType.INT,
                )
        }
    }

private enum class PrefType { BOOL, INT, LONG, STRING }
