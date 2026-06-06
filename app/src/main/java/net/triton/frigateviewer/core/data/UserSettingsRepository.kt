package net.triton.frigateviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        }
    }
