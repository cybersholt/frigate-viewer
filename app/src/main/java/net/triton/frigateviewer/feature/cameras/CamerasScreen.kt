package net.triton.frigateviewer.feature.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
                        CameraSkeletonTile(
                            modifier = Modifier.fillMaxWidth(),
                            cameraName = name,
                            baseUrl = state.effectiveBaseUrl ?: state.activeServer?.baseUrl(),
                            imageLoader = if (name != null) imageLoader else null,
                        )
                    }
                }
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                    strokeWidth = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                        preferSubStream = state.preferSubStream,
                        go2rtcStreams = state.go2rtcStreams,
                        hideEventImage = state.hideEventImage,
                        liveStreamOption = state.liveStreamOption,
                        refreshTimestamp = state.refreshTimestamp,
                        showBoundingBoxes = state.showBoundingBoxes,
                        autoLandscapeOnStream = state.autoLandscapeOnStream,
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
                        PullToRefreshBox(
                            isRefreshing = state.loading,
                            onRefresh = { vm.refresh(forceCacheRefresh = true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(state.gridColumns),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.displayedCameras, key = { it }) { name ->
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
                                        imageLoader = imageLoader,
                                        refreshTimestamp = state.refreshTimestamp,
                                        showBoundingBoxes = state.showBoundingBoxes,
                                        swipeEnabled = state.showSwipeActions,
                                        labels = labels,
                                        zones = zones,
                                        recentEvent = state.recentEvents[name],
                                        recentEventFetched = state.recentEvents.containsKey(name),
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
    imageLoader: ImageLoader,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    swipeEnabled: Boolean,
    labels: List<String>,
    zones: List<String>,
    recentEvent: FrigateEvent?,
    recentEventFetched: Boolean,
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

        // ── Main tile (slides over panels) ──
        val borderWidth = LocalCardBorderWidth.current
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
            Box(Modifier.fillMaxSize()) {
                val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
                FrigateImage(
                    relativePath = "api/$name/latest.jpg?h=360&quality=60&t=$refreshTimestamp$bboxParam",
                    contentDescription = name,
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    crossfade = false,
                    diskWriteOnly = true,
                    diskCacheKey = "snap_$name",
                    modifier = Modifier.fillMaxSize(),
                )
                CameraPill(
                    camera = name,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                )
            }
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
    hideEventImage: Boolean,
    liveStreamOption: String,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
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
    val liveCameraName = if (preferSubStream && go2rtcStreams.contains(subStreamName)) subStreamName else cameraName
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
                    "rtsp://$u:$p@${server.host}:${server.rtspPort}/$liveCameraName"
                } else {
                    base
                }
            } else {
                base
            }
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

    Column(Modifier.fillMaxSize().padding(if (isFullScreen) 0.dp else 8.dp)) {
        if (!isFullScreen) {
            Box(Modifier.fillMaxWidth().clickable { onClose() }.padding(bottom = 8.dp)) {
                Text("← $cameraName (tap to close)", style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(if (isFullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            StreamContent(
                liveStreamOption = liveStreamOption,
                server = server,
                okHttpClient = okHttpClient,
                rtspUrl = rtspUrl,
                baseUrl = baseUrl,
                snapshotPath = snapshotPath,
                liveCameraName = liveCameraName,
                cameraName = cameraName,
                imageLoader = imageLoader,
                showBoundingBoxes = showBoundingBoxes,
                autoLandscapeOnStream = autoLandscapeOnStream,
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

@Composable
private fun StreamContent(
    liveStreamOption: String,
    server: net.triton.frigateviewer.core.data.Server?,
    okHttpClient: okhttp3.OkHttpClient?,
    rtspUrl: String?,
    baseUrl: String?,
    snapshotPath: String,
    liveCameraName: String,
    cameraName: String,
    imageLoader: ImageLoader,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        if (server != null && okHttpClient != null) {
            when (liveStreamOption) {
                "rtsp" -> {
                    val resolvedRtspUrl = rtspUrl
                    if (resolvedRtspUrl != null) {
                        RtspLiveTile(
                            url = resolvedRtspUrl,
                            snapshotUrl = baseUrl?.trimEnd('/') + "/" + snapshotPath,
                            autoLandscapeOnStream = autoLandscapeOnStream,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White) }
                    }
                }

                "snapshot" -> {
                    SnapshotLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = cameraName,
                        imageLoader = imageLoader,
                        showBoundingBoxes = showBoundingBoxes,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    WebRtcLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = liveCameraName,
                        okHttpClient = okHttpClient,
                        snapshotUrl = baseUrl?.trimEnd('/') + "/" + snapshotPath,
                        autoLandscapeOnStream = autoLandscapeOnStream,
                        showBoundingBoxes = showBoundingBoxes,
                        onFatal = { },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
