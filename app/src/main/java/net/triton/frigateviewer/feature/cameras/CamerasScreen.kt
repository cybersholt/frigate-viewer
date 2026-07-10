@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package net.triton.frigateviewer.feature.cameras

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.triton.frigateviewer.LocalFullScreenMode
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
    val context = LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
        }
    val imageLoader = entryPoint.imageLoader()
    val frigateClient = entryPoint.frigateClient()
    var focused by remember { mutableStateOf<String?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }

    // frigateviewer://live?camera= deep link — focus once the camera list has loaded
    // and actually contains the requested name (resolve against the real camera list).
    var consumedInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(initialFocusedCamera, state.cameras) {
        if (!consumedInitialFocus && initialFocusedCamera != null && initialFocusedCamera in state.cameras) {
            focused = initialFocusedCamera
            consumedInitialFocus = true
        }
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
                        liveStreamOption = state.cameraStreamOverrides[focused!!] ?: state.fullscreenStreamType,
                        isStreamOptionOverridden = state.cameraStreamOverrides.containsKey(focused!!),
                        globalDefaultStreamOption = state.fullscreenStreamType,
                        onSetStreamOverride = { mode -> vm.setCameraStreamOverride(focused!!, mode) },
                        refreshTimestamp = state.refreshTimestamp,
                        showBoundingBoxes = state.showBoundingBoxes,
                        autoLandscapeOnStream = state.autoLandscapeOnStream,
                        showLastImageWhileLoading = state.showLastImageWhileLoading,
                        currentSsid = state.currentSsid,
                        onSubStreamFallback = { vm.markSubStreamFallback(focused!!) },
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
                                        isActive = isActive,
                                        onSubStreamFallback = { vm.markSubStreamFallback(name) },
                                        onOpenCamera = { focused = name },
                                        onFetchRecentEvent = { vm.fetchRecentEvent(name) },
                                        onNavigateToEvents = onNavigateToEvents,
                                    )
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
    isActive: Boolean,
    onSubStreamFallback: () -> Unit,
    onOpenCamera: () -> Unit,
    onFetchRecentEvent: () -> Unit,
    onNavigateToEvents: (camera: String?, label: String?, zone: String?) -> Unit,
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
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

        Card(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .then(
                    if (borderWidth.value > 0) {
                        Modifier.border(borderWidth, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    } else {
                        Modifier
                    },
                ),
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
                refreshTimestamp = refreshTimestamp,
                onSubStreamFallback = onSubStreamFallback,
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
    liveStreamOption: String,
    isStreamOptionOverridden: Boolean,
    globalDefaultStreamOption: String,
    onSetStreamOverride: (String?) -> Unit,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    showLastImageWhileLoading: Boolean,
    currentSsid: String?,
    onSubStreamFallback: () -> Unit,
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

    val subStreamName = "${cameraName}_sub"
    val liveCameraName =
        if (preferSubStream && go2rtcStreams.contains(subStreamName) &&
            subStreamFallbacks[cameraName] != true
        ) {
            subStreamName
        } else {
            cameraName
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

    val isFullScreen = LocalFullScreenMode.current.value
    var hasBeenFullScreen by remember { mutableStateOf(false) }
    LaunchedEffect(isFullScreen) {
        if (isFullScreen) {
            hasBeenFullScreen = true
        } else if (hasBeenFullScreen && autoLandscapeOnStream) {
            onClose()
        }
    }

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
        Box(if (isFullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            StreamContent(
                liveStreamOption = liveStreamOption,
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
                cameraName = cameraName,
                imageLoader = imageLoader,
                showBoundingBoxes = showBoundingBoxes,
                autoLandscapeOnStream = autoLandscapeOnStream,
                showLastImageWhileLoading = showLastImageWhileLoading,
                refreshTimestamp = refreshTimestamp,
                onSubStreamFallback = onSubStreamFallback,
                modifier = Modifier.fillMaxSize(),
            )
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
    snapshotPath: String,
    liveCameraName: String,
    cameraName: String,
    imageLoader: ImageLoader,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    showLastImageWhileLoading: Boolean,
    refreshTimestamp: Long,
    onSubStreamFallback: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var streamState by remember(liveStreamOption, liveCameraName) {
        mutableStateOf<LiveStreamState>(LiveStreamState.Idle)
    }
    val snapshotUrl = baseUrl?.trimEnd('/')?.plus("/") + snapshotPath

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
    val effectiveStreamOption =
        if (liveStreamOption == "rtsp" && rtspOffLan) {
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
                            snapshotCachedAt = refreshTimestamp,
                            autoLandscapeOnStream = autoLandscapeOnStream,
                            showLastImageWhileLoading = showLastImageWhileLoading,
                            onStateChanged = { streamState = it },
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
                        showLastImageWhileLoading = showLastImageWhileLoading,
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
                        snapshotCachedAt = refreshTimestamp,
                        autoLandscapeOnStream = autoLandscapeOnStream,
                        showBoundingBoxes = showBoundingBoxes,
                        showLastImageWhileLoading = showLastImageWhileLoading,
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

        // Badge layer — camera pill + status badge + protocol badge — floats above all stream types
        StreamTileBadgeLayer(
            streamState = streamState.toBadgeState(),
            cameraName = cameraName,
            streamTypeLabel =
                when {
                    liveStreamOption == "rtsp" && rtspOffLan -> "WebRTC (RTSP: home network only)"
                    effectiveStreamOption == "rtsp" -> "RTSP"
                    effectiveStreamOption == "snapshot" -> "Snapshot"
                    else -> "WebRTC"
                },
            selectedMode = liveStreamOption,
            isOverridden = isStreamOptionOverridden,
            globalDefaultStreamOption = globalDefaultStreamOption,
            onSetStreamOverride = onSetStreamOverride,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StreamStatusBadge(
    state: CameraStreamState,
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
                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(Color(0xFF4CAF50)) }
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
                StreamStatusBadge(state = streamState)
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
