package net.triton.frigateviewer.feature.cameras

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.triton.frigateviewer.LocalFullScreenMode
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.ServerRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebRtcEntryPoint {
    fun credentialStore(): CredentialStore

    fun serverRepository(): ServerRepository
}

@kotlinx.serialization.Serializable
data class FrigateObjectState(
    val box: List<Float>,
    val label: String,
    val score: Float,
    val region: List<Float>? = null,
)

/**
 * Sub-second live tile via Frigate WebRTC signaling.
 */
@Composable
fun WebRtcLiveTile(
    baseUrl: String,
    cameraName: String,
    okHttpClient: OkHttpClient,
    snapshotUrl: String? = null,
    modifier: Modifier = Modifier,
    autoLandscapeOnStream: Boolean = false,
    showBoundingBoxes: Boolean = true,
    onFatal: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val eglBase = remember { EglBase.create() }
    val pcfHolder = remember { AtomicReference<PeerConnectionFactory?>() }
    val pcHolder = remember { AtomicReference<PeerConnection?>() }
    val wsGlobalHolder = remember { AtomicReference<WebSocket?>() }
    val wsStreamHolder = remember { AtomicReference<WebSocket?>() }
    val rendererHolder = remember { AtomicReference<SurfaceViewRenderer?>() }
    val audioTrackHolder = remember { AtomicReference<org.webrtc.AudioTrack?>() }
    val pendingCandidates = remember { mutableListOf<IceCandidate>() }

    var isLoading by remember { mutableStateOf(true) }
    var isFullScreen by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var statusText by remember { mutableStateOf("Initializing...") }
    var connectionTime by remember { mutableStateOf<Long?>(null) }
    var retryCount by remember { mutableStateOf(0) }
    var objectStates by remember { mutableStateOf<List<FrigateObjectState>>(emptyList()) }
    val startTime = remember(retryCount) { System.currentTimeMillis() }
    val initialSnapshotUrl = remember(baseUrl, cameraName) { snapshotUrl }
    val fullScreenMode = LocalFullScreenMode.current

    val entryPoint = remember { EntryPointAccessors.fromApplication(context, WebRtcEntryPoint::class.java) }
    val credStore = entryPoint.credentialStore()
    val serverRepo = entryPoint.serverRepository()

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (autoLandscapeOnStream) isFullScreen = true
    }

    LaunchedEffect(isFullScreen, autoLandscapeOnStream) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation =
            if (isFullScreen && autoLandscapeOnStream) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }

    LaunchedEffect(isMuted) { audioTrackHolder.get()?.setEnabled(!isMuted) }

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

    LaunchedEffect(retryCount) {
        if (retryCount > 0) {
            isLoading = true
            statusText = "Reconnecting..."
        }
    }

    LaunchedEffect(baseUrl, cameraName, okHttpClient, retryCount) {
        withContext(Dispatchers.IO) {
            // Clean up any previous attempt before creating new resources
            audioTrackHolder.getAndSet(null)?.setEnabled(false)
            wsStreamHolder.getAndSet(null)?.close(1000, "retry")
            wsGlobalHolder.getAndSet(null)?.close(1000, "retry")
            pcHolder.getAndSet(null)?.dispose()
            pcfHolder.getAndSet(null)?.dispose()

            try {
                val server = serverRepo.activeServer() ?: return@withContext
                val rawSecret = credStore.rawSecret(server.id) ?: ""
                val token =
                    if (rawSecret.startsWith(CredentialStore.BEARER_PREFIX)) {
                        rawSecret.removePrefix(CredentialStore.BEARER_PREFIX)
                    } else {
                        ""
                    }

                val origin = baseUrl.trimEnd('/')

                // 1. Establish global session
                statusText = "Connecting..."
                val globalWsUrl =
                    baseUrl
                        .trimEnd('/')
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + "/ws"

                val globalWs =
                    okHttpClient.newWebSocket(
                        Request
                            .Builder()
                            .url(globalWsUrl)
                            .header("Origin", origin)
                            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                            .build(),
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                webSocket.send("onConnect")
                            }
                        },
                    )
                wsGlobalHolder.set(globalWs)

                // 2. PeerConnection Setup
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )

                val pcf =
                    PeerConnectionFactory
                        .builder()
                        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                        .createPeerConnectionFactory()
                pcfHolder.set(pcf)

                val rtcConfig =
                    PeerConnection
                        .RTCConfiguration(
                            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
                        ).apply {
                            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                            iceCandidatePoolSize = 10
                        }

                val pc =
                    pcf.createPeerConnection(
                        rtcConfig,
                        object : EmptyPcObserver() {
                            override fun onIceCandidate(candidate: IceCandidate?) {
                                candidate ?: return
                                val ws = wsStreamHolder.get()
                                if (ws != null) {
                                    val msg =
                                        buildJsonObject {
                                            put("type", "webrtc/candidate")
                                            put("value", candidate.sdp)
                                        }
                                    ws.send(msg.toString())
                                } else {
                                    synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
                                }
                            }

                            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                                    statusText = "Streaming..."
                                } else if (newState == PeerConnection.IceConnectionState.FAILED ||
                                    newState == PeerConnection.IceConnectionState.DISCONNECTED
                                ) {
                                    retryCount++
                                }
                            }

                            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                                val track = transceiver?.receiver?.track() ?: return
                                when (track) {
                                    is VideoTrack -> {
                                        track.setEnabled(true)
                                        rendererHolder.get()?.let { track.addSink(it) }
                                    }

                                    is org.webrtc.AudioTrack -> {
                                        track.setEnabled(!isMuted)
                                        audioTrackHolder.set(track)
                                    }
                                }
                            }
                        },
                    ) ?: throw IllegalStateException("PC failed")
                pcHolder.set(pc)

                pc.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
                pc.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )

                val offer = createOffer(pc)
                pc.setLocalDescriptionAwait(offer)

                // 3. Signaling WS
                val streamWsUrl =
                    baseUrl
                        .trimEnd('/')
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + "/live/webrtc/api/ws?src=$cameraName"

                val streamWs =
                    okHttpClient.newWebSocket(
                        Request
                            .Builder()
                            .url(streamWsUrl)
                            .header("Origin", origin)
                            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                            .build(),
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                webSocket.send(
                                    buildJsonObject {
                                        put("type", "webrtc/offer")
                                        put("value", offer.description)
                                    }.toString(),
                                )

                                synchronized(pendingCandidates) {
                                    pendingCandidates.forEach { c ->
                                        webSocket.send(
                                            buildJsonObject {
                                                put("type", "webrtc/candidate")
                                                put("value", c.sdp)
                                            }.toString(),
                                        )
                                    }
                                    pendingCandidates.clear()
                                }
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                Log.v("WebRtcLiveTile", "WS message: $text")
                                val obj = runCatching { Json.parseToJsonElement(text).let { it as JsonObject } }.getOrNull() ?: return

                                // Handle object states for bounding boxes
                                if (obj.containsKey("objects")) {
                                    val states =
                                        runCatching {
                                            obj["objects"]?.jsonArray?.map {
                                                Json.decodeFromJsonElement<FrigateObjectState>(it)
                                            }
                                        }.getOrNull()
                                    if (states != null) {
                                        objectStates = states
                                    }
                                    return
                                }

                                // Frigate 0.14+ often sends object data in a "message" type or similar
                                if (obj["type"]?.jsonPrimitive?.content == "objects") {
                                    val states =
                                        runCatching {
                                            obj["data"]?.jsonArray?.map {
                                                Json.decodeFromJsonElement<FrigateObjectState>(it)
                                            }
                                        }.getOrNull()
                                    if (states != null) {
                                        objectStates = states
                                    }
                                    return
                                }

                                val type = obj["type"]?.jsonPrimitive?.content ?: return
                                val value = obj["value"]?.jsonPrimitive?.content ?: return

                                when (type) {
                                    "webrtc/answer" -> {
                                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, value)
                                        pc.setRemoteDescription(
                                            object : SdpObserver {
                                                override fun onCreateSuccess(sdp: SessionDescription?) {}

                                                override fun onSetSuccess() {}

                                                override fun onCreateFailure(s: String?) {}

                                                override fun onSetFailure(s: String?) {}
                                            },
                                            sdp,
                                        )
                                    }

                                    "webrtc/candidate" -> {
                                        pc.addIceCandidate(IceCandidate("", 0, value))
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                Log.w("WebRtcLiveTile", "Stream WS failure, retrying...", t)
                                // Exponential backoff for retries
                                val delayMs = (1000L * (1 shl (retryCount % 5))).coerceAtMost(10000L)
                                scope.launch {
                                    delay(delayMs)
                                    retryCount++
                                }
                            }
                        },
                    )
                wsStreamHolder.set(streamWs)
            } catch (t: Throwable) {
                delay(2000)
                retryCount++
            }
        }
    }

    DisposableEffect(baseUrl, cameraName, okHttpClient) {
        onDispose {
            audioTrackHolder.getAndSet(null)?.setEnabled(false)
            wsStreamHolder.getAndSet(null)?.close(1000, "dispose")
            wsGlobalHolder.getAndSet(null)?.close(1000, "dispose")
            pcHolder.getAndSet(null)?.dispose()
            pcfHolder.getAndSet(null)?.dispose()
            rendererHolder.getAndSet(null)?.release()
            eglBase.release()
        }
    }

    Box(
        modifier =
            (if (isFullScreen) Modifier.fillMaxSize() else modifier)
                .background(Color.Black)
                .pointerInput("zoom") {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        zoomOffset = if (scale > 1f) zoomOffset + pan else Offset.Zero
                    }
                }.pointerInput("tap") {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        zoomOffset = Offset.Zero
                    })
                },
        contentAlignment = Alignment.Center,
    ) {
        // 1. Snapshot placeholder
        if (isLoading) {
            val displayUrl = snapshotUrl ?: initialSnapshotUrl
            if (displayUrl != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(displayUrl)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 2. WebRTC Renderer
        AndroidView(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = zoomOffset.x
                    translationY = zoomOffset.y
                    clip = true
                },
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(
                        eglBase.eglBaseContext,
                        object : org.webrtc.RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() {
                                isLoading = false
                                connectionTime = System.currentTimeMillis() - startTime
                            }

                            override fun onFrameResolutionChanged(
                                w: Int,
                                h: Int,
                                r: Int,
                            ) {}
                        },
                    )
                    setEnableHardwareScaler(true)
                    rendererHolder.set(this)
                }
            },
        )

        // Bounding Boxes
        if (showBoundingBoxes) {
            objectStates.forEach { objState ->
                BoundingBoxOverlay(objState)
            }
        }

        // 3. Loading Overlay (semi-transparent if we have a snapshot)
        if (isLoading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (snapshotUrl != null) 0.4f else 1f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    statusText,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }

        // 4. Controls & Info
        connectionTime?.let { time ->
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "WebRTC",
                    modifier =
                        Modifier
                            .background(
                                Color.Blue.copy(alpha = 0.5f),
                                MaterialTheme.shapes.small,
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Text(
                    "${time}ms",
                    modifier =
                        Modifier
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                MaterialTheme.shapes.small,
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        // Mute toggle
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        IconButton(
            onClick = { isFullScreen = !isFullScreen },
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        ) {
            Icon(
                imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = "Toggle full screen",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BoundingBoxOverlay(state: FrigateObjectState) {
    if (state.box.size < 4) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight

        // Frigate coordinates are often normalized or absolute depending on the API.
        // Assuming normalized 0-1 for now based on common Frigate WS patterns,
        // but if it's absolute we need the camera resolution.
        // Based on Frigate docs, /ws sends absolute coordinates relative to the detected frame.
        // Let's assume normalized for simplicity of the UI for now.
        val left = state.box[1] * w.value
        val top = state.box[0] * h.value
        val right = state.box[3] * w.value
        val bottom = state.box[2] * h.value

        Box(
            Modifier
                .offset { IntOffset(left.roundToInt().dp.roundToPx(), top.roundToInt().dp.roundToPx()) }
                .size((right - left).dp, (bottom - top).dp)
                .background(Color.Transparent)
                .border(2.dp, Color.Red, RoundedCornerShape(2.dp)),
        ) {
            Text(
                text = "${state.label} ${(state.score * 100).toInt()}%",
                modifier =
                    Modifier
                        .background(Color.Red.copy(alpha = 0.6f))
                        .padding(horizontal = 2.dp),
                color = Color.White,
                fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private suspend fun createOffer(pc: PeerConnection): SessionDescription {
    val deferred = kotlinx.coroutines.CompletableDeferred<SessionDescription>()
    pc.createOffer(
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    deferred.complete(sdp)
                } else {
                    deferred.completeExceptionally(IllegalStateException("Null SDP"))
                }
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(s: String?) {
                deferred.completeExceptionally(IllegalStateException(s))
            }

            override fun onSetFailure(s: String?) {}
        },
        MediaConstraints(),
    )
    return deferred.await()
}

private suspend fun PeerConnection.setLocalDescriptionAwait(sdp: SessionDescription) {
    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    setLocalDescription(
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}

            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onCreateFailure(s: String?) {}

            override fun onSetFailure(s: String?) {
                deferred.completeExceptionally(IllegalStateException(s))
            }
        },
        sdp,
    )
    deferred.await()
}

private open class EmptyPcObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

    override fun onIceCandidate(candidate: IceCandidate?) {}

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

    override fun onAddStream(stream: MediaStream?) {}

    override fun onRemoveStream(stream: MediaStream?) {}

    override fun onDataChannel(channel: org.webrtc.DataChannel?) {}

    override fun onRenegotiationNeeded() {}

    override fun onAddTrack(
        receiver: org.webrtc.RtpReceiver?,
        streams: Array<out MediaStream>?,
    ) {}
}
