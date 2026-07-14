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
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.model.Severity
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

private const val TAG = "ReviewViewModel"

/** How far back the feed looks. Frigate's own review UI is a "what happened recently" surface. */
private const val WINDOW_HOURS = 24

data class ReviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val baseUrl: String? = null,
    /** Segments matching [severity], newest first — what the grid renders. */
    val segments: List<ReviewSegment> = emptyList(),
    /** Every segment in the window, regardless of severity. Backs the counts and the rail. */
    val allSegments: List<ReviewSegment> = emptyList(),
    val severity: Severity = Severity.ALERT,
) {
    val alertCount: Int get() = allSegments.count { it.severityType == Severity.ALERT }
    val detectionCount: Int get() = allSegments.count { it.severityType == Severity.DETECTION }
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

        fun setSeverity(severity: Severity) {
            _state.value = _state.value.copy(severity = severity).withFilterApplied()
        }

        fun refresh() {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true, error = null)
                val baseUrl = serverRepo.activeServer()?.baseUrl()
                val nowSec = System.currentTimeMillis() / 1000.0
                val afterSec = nowSec - WINDOW_HOURS * 3600

                // `reviewed = 1` keeps already-seen items in the feed. Hiding them is a separate
                // decision that needs the mark-as-reviewed UI to exist first — otherwise items
                // would vanish with no way for the user to have acted on them.
                when (val r = repo.review(after = afterSec, before = nowSec, reviewed = 1)) {
                    is ApiResult.Success -> {
                        _state.value =
                            _state.value
                                .copy(
                                    loading = false,
                                    error = null,
                                    baseUrl = baseUrl,
                                    allSegments = r.data.sortedByDescending { it.startTime },
                                ).withFilterApplied()
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

        private fun fail(
            baseUrl: String?,
            message: String,
            cause: Throwable?,
        ) {
            Log.w(TAG, "review load failed: $message", cause)
            _state.value = _state.value.copy(loading = false, error = message, baseUrl = baseUrl)
        }

        private fun ReviewUiState.withFilterApplied(): ReviewUiState =
            copy(segments = allSegments.filter { it.severityType == severity })
    }
