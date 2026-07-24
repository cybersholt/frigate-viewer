package net.triton.frigateviewer.feature.review

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.model.Severity
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

private const val TAG = "ReviewViewModel"

/** How much history the feed loads. Frigate's review UI is a "what happened recently" surface. */
private const val WINDOW_HOURS = 24

data class ReviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val baseUrl: String? = null,
    /** Segments passing every active filter, newest first — what the grid and the rail render. */
    val segments: List<ReviewSegment> = emptyList(),
    /** Everything in the loaded window. Backs the header counts, which ignore the filters. */
    val allSegments: List<ReviewSegment> = emptyList(),
    // ── filters ──
    val severities: Set<Severity> = setOf(Severity.ALERT),
    val selectedCameras: Set<String> = emptySet(),
    val selectedLabels: Set<String> = emptySet(),
    val selectedZones: Set<String> = emptySet(),
    /** When false, segments Frigate has flagged `has_been_reviewed` are hidden. */
    val showReviewed: Boolean = true,
    // ── timeline ──
    val scrubberTimeMs: Long = System.currentTimeMillis(),
    // The *fetch* window is WINDOW_HOURS (all 130-ish segments load regardless). This is only the
    // initial *render* zoom of the rail — Frigate's own timeline is a tall virtualized strip you
    // scroll through at a fixed pixel-per-second rate; ours squeezes whatever range is selected
    // into a fixed height instead, so starting at the full 24h squashes every pill to a sliver.
    // Starting dense (recent activity, clearly visible pills) and letting zoom-out reach the full
    // day is the cheap approximation of that without a scrollable-strip rewrite.
    val timeRangeHours: Float = 2f,
    /**
     * Motion waveform behind the rail's activity strip. Loaded separately from [allSegments] and
     * allowed to stay empty — the rail falls back to review-item density, so a Frigate too old to
     * serve `api/review/activity/motion` degrades instead of showing a blank strip.
     */
    val motionActivity: List<MotionActivity> = emptyList(),
) {
    // Counts describe the window, not the current filter — they're the thing you filter *with*.
    val alertCount: Int get() = allSegments.count { it.severityType == Severity.ALERT }
    val detectionCount: Int get() = allSegments.count { it.severityType == Severity.DETECTION }

    /** Filter options are derived from the loaded data, so they only ever offer what exists. */
    val availableCameras: List<String> get() = allSegments.map { it.camera }.distinct().sorted()
    val availableLabels: List<String> get() = allSegments.flatMap { it.data.objects }.distinct().sorted()
    val availableZones: List<String> get() = allSegments.flatMap { it.data.zones }.distinct().sorted()

    val activeFilterCount: Int
        get() =
            selectedCameras.size + selectedLabels.size + selectedZones.size +
                (if (showReviewed) 0 else 1)
}

@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val repo: FrigateRepository,
        private val serverRepo: ServerRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ReviewUiState())
        val state = _state.asStateFlow()

        init {
            refresh()
        }

        fun toggleSeverity(severity: Severity) =
            update {
                val next =
                    if (severity in it.severities) it.severities - severity else it.severities + severity
                // Empty would mean "show nothing", which reads as a broken screen rather than a
                // filter. Refuse to clear the last one.
                it.copy(severities = next.ifEmpty { it.severities })
            }

        fun toggleCamera(camera: String) = update { it.copy(selectedCameras = it.selectedCameras.toggle(camera)) }

        fun toggleLabel(label: String) = update { it.copy(selectedLabels = it.selectedLabels.toggle(label)) }

        fun toggleZone(zone: String) = update { it.copy(selectedZones = it.selectedZones.toggle(zone)) }

        fun setShowReviewed(show: Boolean) = update { it.copy(showReviewed = show) }

        /** Clears every filter except severity, which always has at least one member. */
        fun resetFilters() =
            update {
                it.copy(
                    selectedCameras = emptySet(),
                    selectedLabels = emptySet(),
                    selectedZones = emptySet(),
                    showReviewed = true,
                    severities = setOf(Severity.ALERT),
                )
            }

        fun setScrubberTime(timeMs: Long) {
            _state.value = _state.value.copy(scrubberTimeMs = timeMs)
        }

        fun zoomTimeline(factor: Float) {
            val next = (_state.value.timeRangeHours * factor).coerceIn(1f, WINDOW_HOURS.toFloat())
            _state.value = _state.value.copy(timeRangeHours = next)
        }

        fun refresh() {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true, error = null)
                val baseUrl = serverRepo.activeServer()?.baseUrl()
                val nowSec = System.currentTimeMillis() / 1000.0
                val afterSec = nowSec - WINDOW_HOURS * 3600

                // Fire-and-forget alongside the review fetch: the rail renders without it, and
                // making the grid wait on a secondary waveform would be a regression in perceived
                // speed for a purely decorative lane.
                loadMotionActivity(afterSec = afterSec, beforeSec = nowSec)

                // `reviewed` is deliberately NOT sent: it is a *filter*, not an include-flag —
                // `reviewed=1` returns ONLY already-reviewed items (verified live: 2 of 130).
                // We fetch the whole window and filter reviewed/camera/label/zone client-side, so
                // toggling a filter is instant and never re-hits the network.
                when (val r = repo.review(after = afterSec, before = nowSec)) {
                    is ApiResult.Success -> {
                        _state.value =
                            _state.value
                                .copy(
                                    loading = false,
                                    error = null,
                                    baseUrl = baseUrl,
                                    allSegments = r.data.sortedByDescending { it.startTime },
                                ).withFilters()
                    }

                    is ApiResult.HttpError -> {
                        fail(baseUrl, "HTTP ${r.code}", null)
                    }

                    is ApiResult.NetworkError -> {
                        fail(baseUrl, "Network unavailable", r.cause)
                    }

                    is ApiResult.ParseError -> {
                        fail(baseUrl, "Unreadable response", r.cause)
                    }
                }
            }
        }

        /**
         * Loads the rail's motion waveform. Failure is deliberately non-fatal and never reaches
         * [ReviewUiState.error]: the review feed is the screen, the waveform is a lane on the rail,
         * and Frigate versions before 0.14 have no such endpoint. Every branch is logged — the
         * strip silently falling back to review-item density with no trace is exactly the failure
         * mode that makes "why is the timeline different on this server" unanswerable.
         */
        private fun loadMotionActivity(
            afterSec: Double,
            beforeSec: Double,
        ) {
            viewModelScope.launch {
                when (val r = repo.motionActivity(after = afterSec, before = beforeSec)) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(motionActivity = r.data)
                    }

                    is ApiResult.HttpError -> {
                        Log.w(TAG, "motion activity unavailable (HTTP ${r.code}); rail falls back to item density")
                    }

                    is ApiResult.NetworkError -> {
                        Log.w(TAG, "motion activity unavailable (network); rail falls back to item density", r.cause)
                    }

                    is ApiResult.ParseError -> {
                        Log.w(TAG, "motion activity unreadable; rail falls back to item density", r.cause)
                    }
                }
            }
        }

        private fun fail(
            baseUrl: String?,
            message: String,
            cause: Throwable?,
        ) {
            Log.w(TAG, "review load failed: $message", cause)
            _state.value = _state.value.copy(loading = false, error = message, baseUrl = baseUrl)
        }

        private fun update(block: (ReviewUiState) -> ReviewUiState) {
            _state.value = block(_state.value).withFilters()
        }

        private fun ReviewUiState.withFilters(): ReviewUiState =
            copy(
                segments =
                    allSegments.filter { seg ->
                        seg.severityType in severities &&
                            (showReviewed || !seg.hasBeenReviewed) &&
                            (selectedCameras.isEmpty() || seg.camera in selectedCameras) &&
                            (selectedLabels.isEmpty() || seg.data.objects.any { it in selectedLabels }) &&
                            (selectedZones.isEmpty() || seg.data.zones.any { it in selectedZones })
                    },
            )

        private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
    }
