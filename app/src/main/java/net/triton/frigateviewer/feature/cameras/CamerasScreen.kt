@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package net.triton.frigateviewer.feature.cameras

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.triton.frigateviewer.LocalEnterPipRequest
import net.triton.frigateviewer.LocalFullScreenMode
import net.triton.frigateviewer.LocalIsInPip
import net.triton.frigateviewer.LocalPipEligible
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.ui.components.CameraPill
import net.triton.frigateviewer.ui.components.CameraSkeletonTile
import net.triton.frigateviewer.ui.theme.LocalCardBorderWidth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private const val TAG = "CamerasScreen"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CamerasEntryPoint {
    fun imageLoader(): ImageLoader

    fun frigateClient(): net.triton.frigateviewer.core.network.FrigateClient

    fun credentialStore(): CredentialStore
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasScreen(
    onNavigateToEvents: (camera: String?, label: String?, zone: String?) -> Unit = { _, _, _ -> },
    initialFocusedCamera: String? = null,
    vm: CamerasViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Tells the ViewModel when this tab is actually on screen, so its auto-refresh loop stops
    // while another tab is showing instead of polling forever in the background (the ViewModel
    // itself survives tab switches via Navigation-Compose's saveState/restoreState).
    DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }

    val context = LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
        }
    val imageLoader = entryPoint.imageLoader()
    val frigateClient = entryPoint.frigateClient()
    var focused by remember { mutableStateOf<String?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }
    val rtspReconnectSettings =
        remember(state.rtspReconnectAttempts, state.rtspReconnectBaseDelaySeconds) {
            RtspReconnectSettings(state.rtspReconnectAttempts, state.rtspReconnectBaseDelaySeconds)
        }

    // frigateviewer://live?camera= deep link — focus once the camera list has loaded
    // and actually contains the requested name (resolve against the real camera list).
    var consumedInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(initialFocusedCamera, state.cameras) {
        if (!consumedInitialFocus && initialFocusedCamera != null && initialFocusedCamera in state.cameras) {
            focused = initialFocusedCamera
            consumedInitialFocus = true
        }
    }

    // #20: the "is an event open right now" poll is scoped to whichever single camera is
    // actually being viewed in fullscreen, not every grid tile — mirrors this app's
    // screenVisible gating so it never polls a camera nobody's looking at.
    LaunchedEffect(focused) {
        vm.setFocusedCamera(focused)
        vm.clearSidePanel()
    }

    // Full ordered list for edit sheet (visible + hidden, in persisted order)
    val allCamerasOrdered =
        remember(state.cameras, state.cameraOrder) {
            val inOrder = state.cameraOrder.filter { it in state.cameras }
            val notInOrder =
                state.cameras.keys
                    .filter { it !in state.cameraOrder }
                    .sorted()
            inOrder + notInOrder
        }

    CompositionLocalProvider(LocalRtspReconnectSettings provides rtspReconnectSettings) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.cameras.isEmpty() -> {
                    // Skeleton tiles — show last-known camera backgrounds where available
                    val knownNames = state.knownCameraNames
                    val skeletonCount =
                        if (knownNames.isEmpty()) 6 else knownNames.size.coerceAtMost(9)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(state.gridColumns),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(skeletonCount) { i ->
                            val name = knownNames.getOrNull(i)
                            Box(Modifier.fillMaxWidth()) {
                                CameraSkeletonTile(
                                    modifier = Modifier.fillMaxWidth(),
                                    cameraName = name,
                                    baseUrl = state.effectiveBaseUrl ?: state.activeServer?.baseUrl(),
                                    imageLoader = if (name != null) imageLoader else null,
                                )
                                Row(
                                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (name != null) CameraPill(camera = name) else Spacer(Modifier.size(1.dp))
                                    StreamStatusBadge(
                                        state =
                                            if (name != null) {
                                                CameraStreamState.LoadingWithCache(0L)
                                            } else {
                                                CameraStreamState.Skeleton
                                            },
                                    )
                                }
                            }
                        }
                    }
                }

                state.noServerConfigured -> {
                    EmptyState(
                        title = "No server configured",
                        body = "Open Settings and add your Frigate server.",
                    )
                }

                state.errorMessage != null && state.cameras.isEmpty() -> {
                    ErrorState(state.errorMessage!!, onRetry = { vm.refresh() })
                }

                else -> {
                    if (focused != null) {
                        BackHandler { focused = null }
                        FocusedTile(
                            cameraName = focused!!,
                            server = state.activeServer,
                            effectiveBaseUrl = state.effectiveBaseUrl,
                            frigateClient = frigateClient,
                            imageLoader = imageLoader,
                            preferSubStream = state.preferSubStreamFullscreen,
                            go2rtcStreams = state.go2rtcStreams,
                            subStreamFallbacks = state.subStreamFallbacks,
                            hideEventImage = state.hideEventImage,
                            initialStreamOption = state.cameraStreamOverrides[focused!!] ?: state.fullscreenStreamType,
                            refreshTimestamp = state.refreshTimestamp,
                            showBoundingBoxes = state.showBoundingBoxes,
                            autoLandscapeOnStream = state.autoLandscapeOnStream,
                            showLastImageWhileLoading = state.showLastImageWhileLoading,
                            showStreamStats = state.showStreamStats,
                            autoRefresh = state.autoRefresh,
                            autoRefreshIntervalSeconds = state.autoRefreshInterval,
                            audioEnabled = state.cameras[focused!!]?.audio?.enabled == true,
                            currentSsid = state.currentSsid,
                            onSubStreamFallback = { vm.markSubStreamFallback(focused!!) },
                            allCameraNames = state.displayedCameras,
                            onSwitchCamera = { name -> focused = name },
                            recordingEnabled = state.cameras[focused!!]?.record?.enabled == true,
                            activeEventNow = state.activeEventCameraNames.contains(focused!!),
                            sidePanelEvents = state.sidePanelEvents,
                            sidePanelEventsLoading = state.sidePanelEventsLoading,
                            sidePanelReviewSegments = state.sidePanelReviewSegments,
                            sidePanelRecordingGaps = state.sidePanelRecordingGaps,
                            sidePanelMotionActivity = state.sidePanelMotionActivity,
                            sidePanelTimelineLoading = state.sidePanelTimelineLoading,
                            onRequestSidePanelEvents = { vm.loadSidePanelEvents(focused!!) },
                            onRequestSidePanelTimeline = { hours -> vm.loadSidePanelTimeline(focused!!, hours) },
                            showEventsPanel = state.showLiveEventsPanel,
                            onToggleEventsPanel = { vm.setShowLiveEventsPanel(!state.showLiveEventsPanel) },
                            onOpenEvents = { onNavigateToEvents(focused, null, null) },
                            onClose = { focused = null },
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            // Header bar with hamburger
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { showEditSheet = true }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Edit cameras")
                                }
                            }
                            val gridState =
                                androidx.compose.foundation.lazy.grid
                                    .rememberLazyGridState()
                            PullToRefreshBox(
                                isRefreshing = state.loading,
                                onRefresh = { vm.refresh(forceCacheRefresh = true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(state.gridColumns),
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    itemsIndexed(state.displayedCameras, key = { _, it -> it }) { index, name ->
                                        val isActive by remember(state.keepOffscreenTilesAlive) {
                                            androidx.compose.runtime.derivedStateOf {
                                                if (state.keepOffscreenTilesAlive) return@derivedStateOf true
                                                val visibleItems = gridState.layoutInfo.visibleItemsInfo
                                                if (visibleItems.isEmpty()) return@derivedStateOf true
                                                val first = visibleItems.first().index
                                                val last = visibleItems.last().index
                                                index in (first - 2)..(last + 2)
                                            }
                                        }
                                        val labels =
                                            remember(name, state.cameras, state.globalTrackedObjects) {
                                                vm.labelsForCamera(name)
                                            }
                                        val zones =
                                            remember(name, state.cameras) {
                                                vm.zonesForCamera(name)
                                            }
                                        SwipeableCameraTile(
                                            name = name,
                                            baseUrl = state.effectiveBaseUrl,
                                            server = state.activeServer,
                                            frigateClient = frigateClient,
                                            imageLoader = imageLoader,
                                            refreshTimestamp = state.refreshTimestamp,
                                            showBoundingBoxes = state.showBoundingBoxes,
                                            swipeEnabled = state.showSwipeActions,
                                            labels = labels,
                                            zones = zones,
                                            recentEvent = state.recentEvents[name],
                                            recentEventFetched = state.recentEvents.containsKey(name),
                                            gridStreamType = state.cameraStreamOverrides[name] ?: state.gridStreamType,
                                            isStreamOptionOverridden = state.cameraStreamOverrides.containsKey(name),
                                            globalDefaultStreamOption = state.gridStreamType,
                                            onSetStreamOverride = { mode -> vm.setCameraStreamOverride(name, mode) },
                                            preferSubStreamGrid = state.preferSubStreamGrid,
                                            go2rtcStreams = state.go2rtcStreams,
                                            subStreamFallbacks = state.subStreamFallbacks,
                                            currentSsid = state.currentSsid,
                                            showLastImageWhileLoading = state.showLastImageWhileLoading,
                                            showStreamStats = state.showStreamStats,
                                            autoRefresh = state.autoRefresh,
                                            autoRefreshIntervalSeconds = state.autoRefreshInterval,
                                            audioEnabled = state.cameras[name]?.audio?.enabled == true,
                                            cameraTileTint = state.cameraTileTint,
                                            cameraTileShadow = state.cameraTileShadow,
                                            isActive = isActive,
                                            onSubStreamFallback = { vm.markSubStreamFallback(name) },
                                            onOpenCamera = { focused = name },
                                            onFetchRecentEvent = { vm.fetchRecentEvent(name) },
                                            onNavigateToEvents = onNavigateToEvents,
                                            recordingEnabled = state.cameras[name]?.record?.enabled == true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditSheet && allCamerasOrdered.isNotEmpty()) {
        CameraEditSheet(
            allCameraNames = allCamerasOrdered,
            hiddenCameras = state.hiddenCameras,
            serverUrl = state.activeServer?.baseUrl(),
            onReorder = { vm.saveCameraOrder(it) },
            onToggleHide = { vm.toggleHideCamera(it) },
            onDismiss = { showEditSheet = false },
        )
    }
}

@Composable
private fun SwipeableCameraTile(
    name: String,
    baseUrl: String?,
    server: net.triton.frigateviewer.core.data.Server?,
    frigateClient: net.triton.frigateviewer.core.network.FrigateClient,
    imageLoader: ImageLoader,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    swipeEnabled: Boolean,
    labels: List<String>,
    zones: List<String>,
    recentEvent: FrigateEvent?,
    recentEventFetched: Boolean,
    gridStreamType: String,
    isStreamOptionOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    preferSubStreamGrid: Boolean,
    go2rtcStreams: Set<String>,
    subStreamFallbacks: Map<String, Boolean>,
    currentSsid: String?,
    showLastImageWhileLoading: Boolean,
    showStreamStats: Boolean,
    autoRefresh: Boolean,
    autoRefreshIntervalSeconds: Int,
    audioEnabled: Boolean,
    cameraTileTint: String,
    cameraTileShadow: Int,
    isActive: Boolean,
    onSubStreamFallback: () -> Unit,
    onOpenCamera: () -> Unit,
    onFetchRecentEvent: () -> Unit,
    onNavigateToEvents: (camera: String?, label: String?, zone: String?) -> Unit,
    recordingEnabled: Boolean = false,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxOffsetPx = with(density) { 120.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    val leftPanelOpen by remember { derivedStateOf { offsetX.value < -maxOffsetPx * 0.35f } }

    // Trigger recent-event fetch when the user swipes to open the left panel
    LaunchedEffect(leftPanelOpen) {
        if (leftPanelOpen && swipeEnabled) onFetchRecentEvent()
    }

    val cs = MaterialTheme.colorScheme

    fun closePanel() {
        scope.launch { offsetX.animateTo(0f, spring()) }
    }

    // detectHorizontalDragGestures plays nicely with LazyVerticalGrid vertical scroll:
    // it only claims the gesture once horizontal slop is crossed, leaving vertical
    // drags entirely to the parent grid. detectTapGestures handles open/close taps.
    val swipeModifier =
        if (swipeEnabled) {
            Modifier
                .pointerInput(name) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target =
                                    when {
                                        offsetX.value * sign(offsetX.value) > maxOffsetPx * 0.35f -> {
                                            maxOffsetPx * sign(offsetX.value)
                                        }

                                        else -> {
                                            0f
                                        }
                                    }
                                offsetX.animateTo(target, spring())
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(
                                    (offsetX.value + dragAmount).coerceIn(-maxOffsetPx, maxOffsetPx),
                                )
                            }
                        },
                    )
                }.pointerInput(name) {
                    detectTapGestures {
                        if (abs(offsetX.value) > 1f) {
                            closePanel()
                        } else {
                            onOpenCamera()
                        }
                    }
                }
        } else {
            Modifier.clickable { onOpenCamera() }
        }

    Box(
        Modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .then(swipeModifier),
    ) {
        // ── Right-swipe panel (labels/zones) — left edge, revealed by swiping right ──
        Column(
            Modifier
                .fillMaxHeight()
                .width(120.dp)
                .align(Alignment.CenterStart)
                .background(cs.primaryContainer)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            val labelItems = labels.take(3)
            val zoneItems = zones.take(2)
            labelItems.forEach { label ->
                ActionChip(
                    text = label,
                    containerColor = cs.primaryContainer,
                    onClick = {
                        closePanel()
                        onNavigateToEvents(name, label, null)
                    },
                )
            }
            if (labelItems.isNotEmpty() && zoneItems.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 1.dp))
            }
            zoneItems.forEach { zone ->
                ActionChip(
                    text = zone,
                    containerColor = cs.tertiaryContainer,
                    onClick = {
                        closePanel()
                        onNavigateToEvents(name, null, zone)
                    },
                )
            }
            ActionChip(
                text = "All events",
                containerColor = cs.surfaceVariant,
                onClick = {
                    closePanel()
                    onNavigateToEvents(name, null, null)
                },
            )
        }

        // ── Left-swipe panel (recent event) — right edge, revealed by swiping left ──
        Box(
            Modifier
                .fillMaxHeight()
                .width(120.dp)
                .align(Alignment.CenterEnd)
                .background(cs.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !recentEventFetched -> {
                    // Still fetching
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = cs.onSecondaryContainer,
                    )
                }

                recentEvent != null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clickable {
                                closePanel()
                                onNavigateToEvents(name, null, null)
                            }.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        if (recentEvent.hasSnapshot) {
                            FrigateImage(
                                relativePath = "api/events/${recentEvent.id}/snapshot.jpg",
                                contentDescription = null,
                                baseUrl = baseUrl,
                                imageLoader = imageLoader,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f),
                            )
                        }
                        Text(
                            recentEvent.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSecondaryContainer,
                        )
                        if (recentEvent.topScore != null || recentEvent.score != null) {
                            val score = (recentEvent.topScore ?: recentEvent.score)!!
                            Text(
                                "${(score * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSecondaryContainer,
                            )
                        }
                    }
                }

                else -> {
                    Text(
                        "No events",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSecondaryContainer,
                    )
                }
            }
        }

        val borderWidth = LocalCardBorderWidth.current

        val context = LocalContext.current
        val entryPoint =
            remember {
                EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
            }
        val credentialStore = remember { entryPoint.credentialStore() }

        val okHttpClient by androidx.compose.runtime.produceState<okhttp3.OkHttpClient?>(initialValue = null, server) {
            value = if (server != null) frigateClient.clientFor(server) else null
        }

        val subStreamName = "${name}_sub"
        val liveCameraName =
            if (preferSubStreamGrid && go2rtcStreams.contains(subStreamName) &&
                subStreamFallbacks[name] != true
            ) {
                subStreamName
            } else {
                name
            }
        val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
        val snapshotPath = "api/$name/latest.jpg?h=360&t=$refreshTimestamp$bboxParam"

        val rtspUrl by androidx.compose.runtime.produceState<String?>(
            initialValue = null,
            server,
            liveCameraName,
        ) {
            if (server == null) {
                value = null
                return@produceState
            }
            // Strip any embedded ":port" — the main Host field's label invites "host:port" and
            // users carry that habit into RTSP host too, but rtspPort is always a separate field.
            val rtspTargetHost = (server.rtspHost?.takeIf { it.isNotBlank() } ?: server.host).substringBefore(':')
            val base = "rtsp://$rtspTargetHost:${server.rtspPort}/$liveCameraName"
            val secret = credentialStore.rawSecret(server.id)
            val finalUrl =
                if (secret != null && !secret.startsWith(CredentialStore.BEARER_PREFIX)) {
                    val parts = secret.split(":", limit = 2)
                    if (parts.size == 2) {
                        val u = java.net.URLEncoder.encode(parts[0], "UTF-8")
                        val p = java.net.URLEncoder.encode(parts[1], "UTF-8")
                        "rtsp://$u:$p@$rtspTargetHost:${server.rtspPort}/$liveCameraName"
                    } else {
                        base
                    }
                } else {
                    base
                }

            Log.d(TAG, "[DEBUG-RTSP] Constructing Grid RTSP URL: $finalUrl (SSID: $currentSsid)")
            value = finalUrl
        }

        // Tint and shadow are user-configurable (Settings → Cameras view → Tile style). The tint is a
        // container colour rather than M3's automatic elevation tint: the tile is almost entirely
        // covered by video, so only its edges and letterbox bars show the surface — which is exactly
        // where a tint actually reads.
        val tileContainerColor =
            when (cameraTileTint) {
                "surface" -> cs.surfaceVariant
                "primary" -> cs.primaryContainer
                "secondary" -> cs.secondaryContainer
                "tertiary" -> cs.tertiaryContainer
                else -> CardDefaults.cardColors().containerColor
            }

        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .testTag("camera_tile_$name")
                    .then(
                        if (borderWidth.value > 0) {
                            Modifier.border(borderWidth, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        },
                    ),
            colors = CardDefaults.cardColors(containerColor = tileContainerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = cameraTileShadow.dp),
        ) {
            StreamContent(
                liveStreamOption = if (isActive) gridStreamType else "snapshot",
                isStreamOptionOverridden = isStreamOptionOverridden,
                globalDefaultStreamOption = globalDefaultStreamOption,
                onSetStreamOverride = onSetStreamOverride,
                server = server,
                okHttpClient = okHttpClient,
                rtspUrl = rtspUrl,
                currentSsid = currentSsid,
                baseUrl = baseUrl,
                snapshotPath = snapshotPath,
                liveCameraName = liveCameraName,
                cameraName = name,
                imageLoader = imageLoader,
                showBoundingBoxes = showBoundingBoxes,
                autoLandscapeOnStream = false,
                showLastImageWhileLoading = showLastImageWhileLoading,
                showStreamStats = showStreamStats,
                autoRefresh = autoRefresh,
                autoRefreshIntervalSeconds = autoRefreshIntervalSeconds,
                audioEnabled = audioEnabled,
                refreshTimestamp = refreshTimestamp,
                onSubStreamFallback = onSubStreamFallback,
                recordingEnabled = recordingEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FocusedTile(
    cameraName: String,
    server: net.triton.frigateviewer.core.data.Server?,
    effectiveBaseUrl: String?,
    frigateClient: net.triton.frigateviewer.core.network.FrigateClient,
    imageLoader: ImageLoader,
    preferSubStream: Boolean,
    go2rtcStreams: Set<String>,
    subStreamFallbacks: Map<String, Boolean>,
    hideEventImage: Boolean,
    initialStreamOption: String,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    showLastImageWhileLoading: Boolean,
    showStreamStats: Boolean = false,
    autoRefresh: Boolean = true,
    autoRefreshIntervalSeconds: Int = 3,
    audioEnabled: Boolean = false,
    currentSsid: String?,
    onSubStreamFallback: () -> Unit,
    allCameraNames: List<String> = emptyList(),
    onSwitchCamera: (String) -> Unit = {},
    recordingEnabled: Boolean = false,
    activeEventNow: Boolean = false,
    sidePanelEvents: List<FrigateEvent> = emptyList(),
    sidePanelEventsLoading: Boolean = false,
    sidePanelReviewSegments: List<net.triton.frigateviewer.core.model.ReviewSegment> = emptyList(),
    sidePanelRecordingGaps: List<net.triton.frigateviewer.core.model.RecordingGap> = emptyList(),
    sidePanelMotionActivity: List<net.triton.frigateviewer.core.model.MotionActivity> = emptyList(),
    sidePanelTimelineLoading: Boolean = false,
    onRequestSidePanelEvents: () -> Unit = {},
    onRequestSidePanelTimeline: (Float) -> Unit = {},
    showEventsPanel: Boolean = false,
    onToggleEventsPanel: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onClose: () -> Unit,
) {
    val baseUrl = effectiveBaseUrl ?: server?.baseUrl()
    val context = LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
        }
    val credentialStore = remember { entryPoint.credentialStore() }

    val okHttpClient by androidx.compose.runtime.produceState<okhttp3.OkHttpClient?>(initialValue = null, server) {
        value = if (server != null) frigateClient.clientFor(server) else null
    }

    // A focused live view is always PiP-eligible (fullscreen or not) — matches YouTube/Google
    // Home convention of allowing PiP any time a video is the user's current focus.
    val pipEligible = LocalPipEligible.current
    DisposableEffect(Unit) {
        pipEligible.value = true
        onDispose { pipEligible.value = false }
    }
    val isInPip = LocalIsInPip.current
    val enterPip = LocalEnterPipRequest.current

    // Quick per-session resolution override (HD/main vs SD/sub), independent of the global
    // "Prefer sub stream" setting — resets whenever the focused camera changes.
    var resolutionOverride by remember(cameraName) { mutableStateOf<Boolean?>(null) }
    val effectivePreferSubStream = resolutionOverride ?: preferSubStream

    // Session-only stream-protocol override (#5): picking a protocol from the fullscreen pill
    // must NOT persist back to the grid's per-camera override — a fullscreen experiment
    // shouldn't silently change the grid default. Resets whenever the focused camera changes,
    // same as resolutionOverride above.
    var streamOptionOverride by remember(cameraName) { mutableStateOf<String?>(null) }
    val effectiveLiveStreamOption = streamOptionOverride ?: initialStreamOption

    val subStreamName = "${cameraName}_sub"
    val hasSubStream = go2rtcStreams.contains(subStreamName)
    val liveCameraName =
        if (effectivePreferSubStream && hasSubStream &&
            subStreamFallbacks[cameraName] != true
        ) {
            subStreamName
        } else {
            cameraName
        }

    // Mirrors StreamContent's identical off-LAN RTSP gating so the fullscreen protocol pill
    // shows what's actually playing, not just the raw setting — StreamContent's own copy of
    // this computation stays internal (only used for the badge layer StreamContent renders
    // itself when NOT hideBadgeLayer), so it isn't exposed for FullscreenChrome to reuse directly.
    val rtspOffLan =
        remember(server, currentSsid) {
            val lanOnlyHost = server?.rtspHost?.takeIf { it.isNotBlank() }
            lanOnlyHost != null &&
                server.localNetworkSsids.isNotEmpty() &&
                currentSsid !in server.localNetworkSsids
        }
    val effectiveStreamOption = if (effectiveLiveStreamOption == "rtsp" && rtspOffLan) "webrtc" else effectiveLiveStreamOption
    val streamTypeLabel =
        when {
            effectiveLiveStreamOption == "rtsp" && rtspOffLan -> "WebRTC (RTSP: home network only)"
            effectiveStreamOption == "rtsp" -> "RTSP"
            effectiveStreamOption == "snapshot" -> "Snapshot"
            else -> "WebRTC"
        }

    val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
    val snapshotPath = "api/$cameraName/latest.jpg?h=720&t=$refreshTimestamp$bboxParam"

    val rtspUrl by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        server,
        liveCameraName,
    ) {
        if (server == null) {
            value = null
            return@produceState
        }
        val rtspTargetHost = server.rtspHost?.takeIf { it.isNotBlank() } ?: server.host
        val base = "rtsp://$rtspTargetHost:${server.rtspPort}/$liveCameraName"
        val secret = credentialStore.rawSecret(server.id)
        value =
            if (secret != null && !secret.startsWith(CredentialStore.BEARER_PREFIX)) {
                val parts = secret.split(":", limit = 2)
                if (parts.size == 2) {
                    val u = java.net.URLEncoder.encode(parts[0], "UTF-8")
                    val p = java.net.URLEncoder.encode(parts[1], "UTF-8")
                    // Must target rtspTargetHost (not server.host) — the RTSP port is
                    // frequently only forwarded/reachable on the LAN-override host.
                    "rtsp://$u:$p@$rtspTargetHost:${server.rtspPort}/$liveCameraName"
                } else {
                    base
                }
            } else {
                base
            }
        Log.d(
            TAG,
            "RTSP target resolved: host=$rtspTargetHost port=${server.rtspPort} " +
                "camera=$liveCameraName authenticated=${secret != null}",
        )
    }

    // Fullscreen is owned HERE, not in StreamContent, because the video area below swaps between the
    // live tile and a recording player. If StreamContent owned it, starting playback would unmount
    // the owner and dump the user out of fullscreen — exactly what must not happen when they tap an
    // event from the fullscreen side panel.
    //
    // Exiting fullscreen returns to this camera's card view; it must NOT close the camera and drop
    // the user back on the grid (the old behavior, which fired whenever autoLandscapeOnStream had
    // auto-entered fullscreen). Back is the exception — see FullScreenEffects.
    val fullScreenState = remember { mutableStateOf(false) }
    val isFullScreen = fullScreenState.value

    LaunchedEffect(autoLandscapeOnStream) {
        if (autoLandscapeOnStream) fullScreenState.value = true
    }
    FullScreenEffects(
        fullScreen = fullScreenState,
        autoLandscapeOnStream = autoLandscapeOnStream,
        onExitFullScreenByBack = { if (autoLandscapeOnStream) onClose() },
    )

    // Non-null = the video area is playing recorded footage instead of the live stream. Survives the
    // live/playback swap because it lives here, above it.
    var playback by remember(cameraName) { mutableStateOf<PlaybackTarget?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (isFullScreen) Color.Black else Color.Transparent)
            .padding(if (isFullScreen) 0.dp else 8.dp),
    ) {
        if (!isFullScreen) {
            Box(Modifier.fillMaxWidth().clickable { onClose() }.padding(bottom = 8.dp)) {
                Text("← $cameraName (tap to close)", style = MaterialTheme.typography.titleMedium)
            }
        }

        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // #16/#17: the recent-events side panel only makes sense where there's spare width —
        // fullscreen landscape, matching the reference wishlist layout. Portrait fullscreen and
        // the non-fullscreen card view are unchanged. Off unless the user opts in from the
        // fullscreen overflow menu (persisted), so fullscreen live view is full-bleed by default.
        // Hidden in PiP without touching the persisted setting: a PiP window is a few hundred pixels
        // wide, so a 260dp panel crowds the video out entirely — you'd see the panel header and its
        // mode buttons and no camera at all. Restores itself on the way back out, since this reads
        // isInPip rather than writing the preference.
        val eventsPanelVisible = isFullScreen && isLandscape && showEventsPanel && !isInPip

        val videoContent: @Composable () -> Unit = {
            var liveStreamState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }

            val activePlayback = playback
            val playbackClient = okHttpClient
            if (activePlayback != null && baseUrl != null && playbackClient != null) {
                // Playback takes over the video area in place: same fullscreen, same orientation,
                // same side panel — only the picture changes. Backing out returns to live rather
                // than leaving the camera.
                BackHandler { playback = null }
                RecordingPlayback(
                    baseUrl = baseUrl,
                    cameraName = cameraName,
                    target = activePlayback,
                    okHttpClient = playbackClient,
                    onClose = { playback = null },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                StreamContent(
                    liveStreamOption = effectiveLiveStreamOption,
                    isStreamOptionOverridden = streamOptionOverride != null,
                    globalDefaultStreamOption = initialStreamOption,
                    onSetStreamOverride = { mode -> streamOptionOverride = mode },
                    server = server,
                    okHttpClient = okHttpClient,
                    rtspUrl = rtspUrl,
                    currentSsid = currentSsid,
                    baseUrl = baseUrl,
                    snapshotPath = snapshotPath,
                    liveCameraName = liveCameraName,
                    cameraName = cameraName,
                    imageLoader = imageLoader,
                    showBoundingBoxes = showBoundingBoxes,
                    autoLandscapeOnStream = autoLandscapeOnStream,
                    showLastImageWhileLoading = showLastImageWhileLoading,
                    showStreamStats = showStreamStats,
                    autoRefresh = autoRefresh,
                    autoRefreshIntervalSeconds = autoRefreshIntervalSeconds,
                    audioEnabled = audioEnabled,
                    refreshTimestamp = refreshTimestamp,
                    onSubStreamFallback = onSubStreamFallback,
                    hideBadgeLayer = isFullScreen,
                    onStreamStateChanged = { liveStreamState = it },
                    // Fullscreen is owned by FocusedTile (see fullScreenState above), so StreamContent
                    // must not host FullScreenEffects — otherwise starting playback unmounts it and
                    // takes fullscreen down with it.
                    fullScreenState = fullScreenState,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (isFullScreen && activePlayback == null) {
                FullscreenChrome(
                    visible = !isInPip,
                    cameraName = cameraName,
                    hasSubStream = hasSubStream,
                    isSubStreamActive = liveCameraName == subStreamName,
                    onToggleResolution = { resolutionOverride = !effectivePreferSubStream },
                    onBack = onClose,
                    onEnterPip = enterPip,
                    allCameraNames = allCameraNames,
                    onSwitchCamera = onSwitchCamera,
                    streamTypeLabel = streamTypeLabel,
                    liveStreamOption = effectiveLiveStreamOption,
                    isStreamOptionOverridden = streamOptionOverride != null,
                    globalDefaultStreamOption = initialStreamOption,
                    onSetStreamOverride = { mode -> streamOptionOverride = mode },
                    recordingEnabled = recordingEnabled,
                    activeEventNow = activeEventNow,
                    liveStreamState = liveStreamState,
                    eventsPanelSupported = isLandscape,
                    eventsPanelVisible = showEventsPanel,
                    onToggleEventsPanel = onToggleEventsPanel,
                )
            }
        }

        // One Row, one Box, always — the panel is a conditional *sibling* of the video, never a
        // different wrapper around it. Branching the layout (Row-with-panel vs bare Box) moved
        // videoContent() to a different call site, so Compose discarded and re-created StreamContent
        // on every panel toggle. That reset StreamContent's internal isFullScreen to false — showing
        // the system bars and dropping the orientation lock — before autoLandscapeOnStream re-entered
        // fullscreen a frame later: the visible "exits fullscreen, then goes back into it" flicker.
        Row(
            if (isFullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
        ) {
            Box(
                if (isFullScreen) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            ) {
                videoContent()
            }
            if (eventsPanelVisible) {
                LiveEventsPanel(
                    cameraName = cameraName,
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    events = sidePanelEvents,
                    eventsLoading = sidePanelEventsLoading,
                    reviewSegments = sidePanelReviewSegments,
                    recordingGaps = sidePanelRecordingGaps,
                    motionActivity = sidePanelMotionActivity,
                    timelineLoading = sidePanelTimelineLoading,
                    onRequestEvents = onRequestSidePanelEvents,
                    onRequestTimeline = onRequestSidePanelTimeline,
                    onOpenEvents = onOpenEvents,
                    onPlayEvent = { event -> playback = PlaybackTarget.forEvent(event) },
                    onPlayFromTime = { epochMs -> playback = PlaybackTarget.forTime(epochMs / 1000) },
                    onHidePanel = onToggleEventsPanel,
                    modifier = Modifier.fillMaxHeight().width(260.dp),
                )
            }
        }
        if (!isFullScreen && !hideEventImage) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FrigateImage(
                    relativePath = snapshotPath,
                    contentDescription = "$cameraName last snapshot",
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    crossfade = false,
                    diskCache = true,
                    diskCacheKey = "snap_${cameraName}_$refreshTimestamp",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }
    }
}

/**
 * Fullscreen-only chrome layered above [StreamContent]: back arrow + camera name + a pulsing
 * "LIVE" indicator up top, an HD/SD resolution toggle and an overflow menu (protocol switching,
 * PiP entry, quick camera switching) down at the bottom corners — offset above the
 * mute/fullscreen-exit buttons that [RtspLiveTile]/[WebRtcLiveTile] already render at the very
 * bottom of the same corners. Owns the top strip exclusively while fullscreen — the caller
 * passes `hideBadgeLayer = true` to [StreamContent] so its own camera-pill/protocol-badge Row
 * doesn't render underneath and collide with this one. Renders nothing while [visible] is false
 * (i.e. while actually in Picture-in-Picture — minimal chrome per Android PiP UX guidelines).
 */
@Composable
private fun FullscreenChrome(
    visible: Boolean,
    cameraName: String,
    hasSubStream: Boolean,
    isSubStreamActive: Boolean,
    onToggleResolution: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    allCameraNames: List<String>,
    onSwitchCamera: (String) -> Unit,
    streamTypeLabel: String,
    liveStreamOption: String,
    isStreamOptionOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    recordingEnabled: Boolean = false,
    activeEventNow: Boolean = false,
    liveStreamState: LiveStreamState = LiveStreamState.Idle,
    eventsPanelSupported: Boolean = false,
    eventsPanelVisible: Boolean = false,
    onToggleEventsPanel: () -> Unit = {},
) {
    if (!visible) return
    var showOverflow by remember { mutableStateOf(false) }
    var showStreamInfo by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                // System bars are hidden in fullscreen but can reappear as a transient overlay on
                // a swipe-down (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE in WebRtcLiveTile/RtspLiveTile)
                // — without this inset the status bar's own clock/battery/icons render on top of
                // this row, illegible where they overlap (reported + reproduced via screenshot).
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("fullscreen_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                cameraName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f).testTag("fullscreen_camera_name"),
            )
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    // StreamTypeBadge below has an 8dp horizontal inset baked into its pill
                    // background — match it here so "LIVE" and the protocol label's text share
                    // the same right edge instead of LIVE overhanging past the pill's text.
                    modifier = Modifier.padding(end = 8.dp).testTag("fullscreen_live_indicator"),
                ) {
                    LiveIndicatorDot(recordingEnabled, activeEventNow)
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                // Same tappable protocol pill grid tiles show (StreamTypeBadge) — kept here so
                // fullscreen retains direct one-tap protocol switching, not just via the overflow menu.
                StreamTypeBadge(
                    label = streamTypeLabel,
                    selectedMode = liveStreamOption,
                    isOverridden = isStreamOptionOverridden,
                    globalDefaultStreamOption = globalDefaultStreamOption,
                    onSetStreamOverride = onSetStreamOverride,
                    modifier = Modifier.testTag("fullscreen_protocol_pill"),
                )
            }
        }

        if (hasSubStream) {
            TextButton(
                onClick = onToggleResolution,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = 48.dp)
                        .testTag("fullscreen_resolution_toggle"),
            ) {
                Text(if (isSubStreamActive) "SD" else "HD", color = Color.White)
            }
        }

        // PiP sits beside the overflow button rather than only inside the menu — it's a one-tap action
        // people reach for constantly, and burying it in a menu made it feel hidden.
        IconButton(
            onClick = onEnterPip,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = 48.dp)
                    .testTag("fullscreen_pip_button"),
        ) {
            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture-in-picture", tint = Color.White)
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 48.dp)) {
            IconButton(onClick = { showOverflow = true }, modifier = Modifier.testTag("fullscreen_overflow_button")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
            }
            DropdownMenu(
                expanded = showOverflow,
                onDismissRequest = { showOverflow = false },
                modifier = Modifier.testTag("fullscreen_overflow_menu"),
            ) {
                DropdownMenuItem(
                    text = { Text("Picture-in-picture") },
                    leadingIcon = { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null) },
                    onClick = {
                        showOverflow = false
                        onEnterPip()
                    },
                    modifier = Modifier.testTag("fullscreen_pip_menu_item"),
                )
                DropdownMenuItem(
                    text = { Text("Stream info") },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = {
                        showOverflow = false
                        showStreamInfo = true
                    },
                    modifier = Modifier.testTag("fullscreen_stream_info_menu_item"),
                )
                // Landscape-only: the panel steals 260dp of width, which there's no room for in
                // portrait fullscreen — so don't offer a toggle that would visibly do nothing.
                if (eventsPanelSupported) {
                    DropdownMenuItem(
                        text = { Text(if (eventsPanelVisible) "Hide events panel" else "Show events panel") },
                        leadingIcon = {
                            Icon(
                                if (eventsPanelVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showOverflow = false
                            onToggleEventsPanel()
                        },
                        modifier = Modifier.testTag("fullscreen_events_panel_menu_item"),
                    )
                }
                val otherCameras = allCameraNames.filter { it != cameraName }
                if (otherCameras.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "Switch camera",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    otherCameras.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                showOverflow = false
                                onSwitchCamera(name)
                            },
                            modifier = Modifier.testTag("fullscreen_switch_camera_$name"),
                        )
                    }
                }
            }
        }

        if (showStreamInfo) {
            AlertDialog(
                onDismissRequest = { showStreamInfo = false },
                title = { Text("Stream info") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StreamInfoRow("Camera", cameraName)
                        StreamInfoRow("Protocol", streamTypeLabel)
                        if (hasSubStream) {
                            StreamInfoRow("Resolution", if (isSubStreamActive) "SD (sub stream)" else "HD (main stream)")
                        }
                        StreamInfoRow("Status", liveStreamStateLabel(liveStreamState))
                        StreamInfoRow("Recording", if (recordingEnabled) "Enabled" else "Disabled")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStreamInfo = false }) { Text("Close") }
                },
                modifier = Modifier.testTag("fullscreen_stream_info_dialog"),
            )
        }
    }
}

@Composable
private fun StreamInfoRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Human-readable status for the "Stream info" dialog — deliberately coarse (connection lifecycle,
 * not bitrate/resolution-in-pixels/buffer-depth telemetry). Real per-frame stats would need new
 * plumbing from ExoPlayer's Format/track listeners and WebRTC's getStats() API; out of scope for
 * this pass (backlog #8 flagged bitrate/buffer state as a nice-to-have, not required).
 */
private fun liveStreamStateLabel(state: LiveStreamState): String =
    when (state) {
        is LiveStreamState.Idle -> "Idle"
        is LiveStreamState.Connecting -> "Connecting (${state.elapsedMs / 1000}s)"
        is LiveStreamState.Negotiating -> "Negotiating"
        is LiveStreamState.Buffering -> "Buffering"
        is LiveStreamState.Playing -> "Playing"
        is LiveStreamState.Reconnecting -> "Reconnecting (attempt ${state.attempt})"
        is LiveStreamState.Error -> "Error: ${state.reason}"
    }

/**
 * The side effects of being fullscreen: orientation lock, system-bar visibility, publishing
 * [LocalFullScreenMode] for the rest of the tree, and the Back handler.
 *
 * Hosted by whoever owns the fullscreen state — StreamContent for a grid tile, FocusedTile for the
 * focused view — so that it outlives whatever is being swapped inside the video area. If these
 * effects lived below the swap point, replacing the live tile with a recording player would dispose
 * them and yank the user out of fullscreen mid-playback.
 */
@Composable
private fun FullScreenEffects(
    fullScreen: MutableState<Boolean>,
    autoLandscapeOnStream: Boolean,
    onExitFullScreenByBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val fullScreenMode = LocalFullScreenMode.current
    val isFullScreen = fullScreen.value

    // The orientation lock must be released on teardown, not just when isFullScreen flips false:
    // navigating away from a fullscreen stream (e.g. "View all events" straight into the Events
    // screen) disposes this composable while the lock is still SENSOR_LANDSCAPE, and with nothing
    // left to unwind it the whole app stayed stuck sideways.
    DisposableEffect(isFullScreen, autoLandscapeOnStream) {
        val activity = context as? Activity
        activity?.requestedOrientation =
            if (isFullScreen && autoLandscapeOnStream) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(isFullScreen) { fullScreenMode.value = isFullScreen }
    DisposableEffect(Unit) { onDispose { fullScreenMode.value = false } }

    DisposableEffect(isFullScreen) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        if (isFullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    if (isFullScreen) {
        // Back and the fullscreen-exit button are deliberately NOT the same gesture. The button
        // means "make this smaller" — it drops to the camera's card view and stays there. Back means
        // "leave", so when fullscreen was entered automatically (auto-landscape opens a camera
        // straight into fullscreen), backing out of it must also close the camera rather than
        // stranding the user in a card view they never asked for. Conflating the two is what made
        // the exit button dump people back on the grid.
        BackHandler {
            fullScreen.value = false
            onExitFullScreenByBack()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamContent(
    liveStreamOption: String,
    isStreamOptionOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    server: net.triton.frigateviewer.core.data.Server?,
    okHttpClient: okhttp3.OkHttpClient?,
    rtspUrl: String?,
    currentSsid: String?,
    baseUrl: String?,
    recordingEnabled: Boolean = false,
    snapshotPath: String,
    liveCameraName: String,
    cameraName: String,
    imageLoader: ImageLoader,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    showLastImageWhileLoading: Boolean,
    refreshTimestamp: Long,
    showStreamStats: Boolean = false,
    autoRefresh: Boolean = true,
    autoRefreshIntervalSeconds: Int = 3,
    /** From Frigate's config (cameras.<name>.audio.enabled). No audio published = no unmute control. */
    audioEnabled: Boolean = false,
    onSubStreamFallback: () -> Unit = {},
    hideBadgeLayer: Boolean = false,
    onStreamStateChanged: (LiveStreamState) -> Unit = {},
    /** Back pressed while fullscreen. Fullscreen is already exiting; the caller decides whether to also close. */
    onExitFullScreenByBack: () -> Unit = {},
    /** Hoisted fullscreen state. When non-null the caller owns fullscreen (and hosts [FullScreenEffects]). */
    fullScreenState: MutableState<Boolean>? = null,
    modifier: Modifier = Modifier,
) {
    var streamState by remember(liveStreamOption, liveCameraName) {
        mutableStateOf<LiveStreamState>(LiveStreamState.Idle)
    }
    LaunchedEffect(streamState) { onStreamStateChanged(streamState) }
    val snapshotUrl = baseUrl?.trimEnd('/')?.plus("/") + snapshotPath

    // Fullscreen is owned by the highest call site that survives whatever gets swapped beneath it.
    // For a grid tile that is StreamContent itself (a protocol switch replaces only the leaf tile
    // below it). For the focused view it is FocusedTile, which hoists the state in via
    // [fullScreenState] — there the video area *also* swaps between the live tile and a recording
    // player, unmounting StreamContent entirely. Whoever owns the state hosts [FullScreenEffects];
    // hosting them here while the state is hoisted would drop the orientation lock and bring the
    // system bars back the instant playback replaced the live tile.
    val ownedFullScreen = remember { mutableStateOf(false) }
    val fullScreen = fullScreenState ?: ownedFullScreen
    val isFullScreen = fullScreen.value

    if (fullScreenState == null) {
        LaunchedEffect(Unit) {
            if (autoLandscapeOnStream) fullScreen.value = true
        }
        FullScreenEffects(
            fullScreen = fullScreen,
            autoLandscapeOnStream = autoLandscapeOnStream,
            onExitFullScreenByBack = onExitFullScreenByBack,
        )
    }

    // The RTSP port is commonly only reachable via server.rtspHost, a LAN IP — off that
    // Wi-Fi network the connection will just hang/fail. Gate it and fall back to WebRTC
    // (which always goes through the public host over 443) instead of failing silently.
    val rtspOffLan =
        remember(server, currentSsid) {
            val lanOnlyHost = server?.rtspHost?.takeIf { it.isNotBlank() }
            lanOnlyHost != null &&
                server.localNetworkSsids.isNotEmpty() &&
                currentSsid !in server.localNetworkSsids
        }
    // Some go2rtc restreams (Wyze cameras, in practice) publish an SDP with no sprop-parameter-sets,
    // which ExoPlayer's RTSP stack refuses outright while WebRTC plays them fine. The tile reports
    // that back once, and this camera transparently switches transport instead of sitting on an
    // offline tile. Keyed per camera+stream so re-opening a different camera re-evaluates.
    var rtspUnsupported by remember(cameraName, liveCameraName) { mutableStateOf(false) }

    val effectiveStreamOption =
        if (liveStreamOption == "rtsp" && (rtspOffLan || rtspUnsupported)) {
            "webrtc"
        } else {
            liveStreamOption
        }

    LaunchedEffect(liveStreamOption, rtspOffLan, cameraName) {
        if (liveStreamOption == "rtsp" && rtspOffLan) {
            Log.i(TAG, "RTSP gated off-LAN (ssid=$currentSsid) for $cameraName — falling back to WebRTC")
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (server != null && okHttpClient != null) {
            when (effectiveStreamOption) {
                "rtsp" -> {
                    val resolvedRtspUrl = rtspUrl
                    if (resolvedRtspUrl != null) {
                        RtspLiveTile(
                            url = resolvedRtspUrl,
                            snapshotUrl = snapshotUrl,
                            isFullScreen = isFullScreen,
                            onToggleFullScreen = { fullScreen.value = !fullScreen.value },
                            showLastImageWhileLoading = showLastImageWhileLoading,
                            showStreamStats = showStreamStats,
                            audioEnabled = audioEnabled,
                            onStateChanged = { streamState = it },
                            onRtspUnsupported = { rtspUnsupported = true },
                            onFatal = {
                                if (liveCameraName != cameraName) {
                                    onSubStreamFallback()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) { LoadingIndicator(modifier = Modifier.size(48.dp), color = Color.White.copy(alpha = 0.80f)) }
                    }
                }

                "snapshot" -> {
                    SnapshotLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = cameraName,
                        imageLoader = imageLoader,
                        showBoundingBoxes = showBoundingBoxes,
                        snapshotUrl = snapshotUrl,
                        autoRefresh = autoRefresh,
                        autoRefreshIntervalSeconds = autoRefreshIntervalSeconds,
                        refreshTimestamp = refreshTimestamp,
                        onStateChanged = { streamState = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    WebRtcLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = liveCameraName,
                        okHttpClient = okHttpClient,
                        snapshotUrl = snapshotUrl,
                        isFullScreen = isFullScreen,
                        onToggleFullScreen = { fullScreen.value = !fullScreen.value },
                        showBoundingBoxes = showBoundingBoxes,
                        showLastImageWhileLoading = showLastImageWhileLoading,
                        showStreamStats = showStreamStats,
                        audioEnabled = audioEnabled,
                        onFatal = {
                            if (liveCameraName != cameraName) {
                                onSubStreamFallback()
                            }
                        },
                        onStateChanged = { streamState = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Badge layer — camera pill + status badge + protocol badge — floats above all stream
        // types. Suppressed in true fullscreen: FullscreenChrome owns the top strip there
        // (back arrow + name + LIVE dot), and protocol-switching moves into its overflow menu
        // instead, so the two don't render duplicate/overlapping camera-name+status info.
        if (!hideBadgeLayer) {
            StreamTileBadgeLayer(
                streamState = streamState.toBadgeState(),
                cameraName = cameraName,
                streamTypeLabel =
                    when {
                        liveStreamOption == "rtsp" && rtspOffLan -> "WebRTC (RTSP: home network only)"
                        liveStreamOption == "rtsp" && rtspUnsupported -> "WebRTC (RTSP unsupported)"
                        effectiveStreamOption == "rtsp" -> "RTSP"
                        effectiveStreamOption == "snapshot" -> "Snapshot"
                        else -> "WebRTC"
                    },
                selectedMode = liveStreamOption,
                isOverridden = isStreamOptionOverridden,
                globalDefaultStreamOption = globalDefaultStreamOption,
                recordingEnabled = recordingEnabled,
                onSetStreamOverride = onSetStreamOverride,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Green pulsing dot normally; slow red pulse when the camera has Frigate recording enabled;
 * solid blue (no pulse) when an event is actively open right now on this camera (#20) — that
 * state wins over the other two since it's the most urgent/attention-grabbing.
 */
@Composable
private fun LiveIndicatorDot(
    recordingEnabled: Boolean,
    activeEventNow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (activeEventNow) {
        Canvas(modifier.size(8.dp)) { drawCircle(Color(0xFF2196F3)) }
        return
    }
    val infiniteTransition = rememberInfiniteTransition(label = "liveDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (recordingEnabled) 0.15f else 0.4f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(tween(if (recordingEnabled) 1400 else 900), RepeatMode.Reverse),
        label = "liveDotAlpha",
    )
    val color = if (recordingEnabled) Color(0xFFE53935) else Color(0xFF4CAF50)
    Canvas(modifier.size(8.dp)) { drawCircle(color.copy(alpha = alpha)) }
}

@Composable
private fun StreamStatusBadge(
    state: CameraStreamState,
    recordingEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val white70 = Color.White.copy(alpha = 0.70f)
    when (val s = state) {
        CameraStreamState.Skeleton -> {
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = white70,
                    modifier = Modifier.size(12.dp),
                )
                Text("Loading…", style = MaterialTheme.typography.labelSmall, color = white70)
            }
        }

        is CameraStreamState.LoadingWithCache -> {
            var timeText by remember(s.cachedAt) { mutableStateOf(formatTimeAgo(s.cachedAt)) }
            LaunchedEffect(s.cachedAt) {
                while (true) {
                    delay(60_000)
                    timeText = formatTimeAgo(s.cachedAt)
                }
            }
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(14.dp), color = white70)
                if (s.cachedAt > 0L) {
                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = white70)
                }
            }
        }

        CameraStreamState.Live -> {
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LiveIndicatorDot(recordingEnabled)
                Text("Live", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        is CameraStreamState.Offline -> { /* tile overlay handles this */ }
    }
}

@Composable
private fun StreamTileBadgeLayer(
    streamState: CameraStreamState,
    cameraName: String,
    streamTypeLabel: String,
    selectedMode: String,
    isOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    recordingEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CameraPill(camera = cameraName)
                StreamStatusBadge(state = streamState, recordingEnabled = recordingEnabled)
            }
            StreamTypeBadge(
                label = streamTypeLabel,
                selectedMode = selectedMode,
                isOverridden = isOverridden,
                globalDefaultStreamOption = globalDefaultStreamOption,
                onSetStreamOverride = onSetStreamOverride,
            )
        }
    }
}

/**
 * Dark translucent pill showing the active stream protocol (RTSP / WebRTC / Snapshot).
 * Tap opens a menu to override this camera's feed mode; overriding persists per-camera
 * and takes precedence over the global default set in Settings.
 */
@Composable
private fun StreamTypeBadge(
    label: String,
    selectedMode: String,
    isOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .clickable { expanded = true },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("webrtc" to "WebRTC", "rtsp" to "RTSP", "snapshot" to "Snapshot").forEach { (mode, modeLabel) ->
                DropdownMenuItem(
                    text = { Text(modeLabel) },
                    leadingIcon =
                        if (isOverridden && selectedMode == mode) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        onSetStreamOverride(mode)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Use default ($globalDefaultStreamOption)") },
                leadingIcon =
                    if (!isOverridden) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                onClick = {
                    expanded = false
                    onSetStreamOverride(null)
                },
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't load cameras", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
    }
}
