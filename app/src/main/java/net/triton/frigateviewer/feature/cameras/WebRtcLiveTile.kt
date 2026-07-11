package net.triton.frigateviewer.feature.cameras

import android.media.AudioAttributes
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
import org.webrtc.audio.JavaAudioDeviceModule
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

private const val MAX_AUTO_RECONNECTS = 2
private val BACKOFF_MS = longArrayOf(2_000L, 5_000L)

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
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    showBoundingBoxes: Boolean = true,
    showLastImageWhileLoading: Boolean = true,
    onFatal: (String) -> Unit = {},
    onStateChanged: (LiveStreamState) -> Unit = {},
) {
    val context = LocalContext.current
    val eglBase = remember(baseUrl, cameraName, okHttpClient) { EglBase.create() }
    DisposableEffect(eglBase) {
        onDispose {
            eglBase.release()
        }
    }

    val pcfHolder = remember { AtomicReference<PeerConnectionFactory?>() }
    val admHolder = remember { AtomicReference<JavaAudioDeviceModule?>() }
    val pcHolder = remember { AtomicReference<PeerConnection?>() }
    val wsGlobalHolder = remember { AtomicReference<WebSocket?>() }
    val wsStreamHolder = remember { AtomicReference<WebSocket?>() }
    val rendererHolder = remember { AtomicReference<SurfaceViewRenderer?>() }
    val audioTrackHolder = remember { AtomicReference<org.webrtc.AudioTrack?>() }
    val pendingCandidates = remember { mutableListOf<IceCandidate>() }

    var isMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var retryCount by remember { mutableStateOf(0) }
    // Native frame aspect ratio, so the renderer can be sized to fit-within its container
    // (letterboxed) at the Compose layout level — SurfaceViewRenderer.setScalingType alone
    // doesn't reliably letterbox when the AndroidView itself is forced to fillMaxSize.
    var videoAspectRatio by remember { mutableStateOf(16f / 9f) }
    var autoReconnectAttempts by remember { mutableStateOf(0) }
    var liveState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }
    var videoRevealed by remember { mutableStateOf(false) }
    var objectStates by remember { mutableStateOf<List<FrigateObjectState>>(emptyList()) }

    LaunchedEffect(liveState) { onStateChanged(liveState) }

    val entryPoint = remember { EntryPointAccessors.fromApplication(context, WebRtcEntryPoint::class.java) }
    val credStore = entryPoint.credentialStore()
    val serverRepo = entryPoint.serverRepository()

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(isMuted) { audioTrackHolder.get()?.setEnabled(!isMuted) }

    // Fullscreen/orientation/system-bars ownership lives in StreamContent (the stable call
    // site across protocol switches), not here — this composable is torn down and recreated
    // whenever the user switches protocol, which previously reset that state and left the
    // orientation lock stuck (see #5 verification session writeup).

    // Reconnect when returning from background: the peer connection silently dies
    // while stopped (no WS failure fires), leaving a black frozen surface. Bumping
    // retryCount triggers the full teardown + redial path below.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stopped = false
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        stopped = true
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        if (stopped) {
                            stopped = false
                            autoReconnectAttempts = 0
                            retryCount++
                        }
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Classifies a transient failure: auto-retries up to [MAX_AUTO_RECONNECTS] with backoff, else terminal Error. */
    fun handleFailure(reason: StreamError) {
        if (autoReconnectAttempts < MAX_AUTO_RECONNECTS) {
            autoReconnectAttempts++
            liveState = LiveStreamState.Reconnecting(autoReconnectAttempts)
            val delayMs = BACKOFF_MS[autoReconnectAttempts - 1]
            scope.launch {
                delay(delayMs)
                retryCount++
            }
        } else {
            liveState = LiveStreamState.Error(reason)
            onFatal(reason.name)
        }
    }

    // Timeout watchdog: server can take >10s (measured 17.4s against a real unhealthy camera),
    // so give it 20s total before declaring a hard timeout rather than looping forever.
    LaunchedEffect(retryCount) {
        var elapsed = 0L
        while (true) {
            delay(500)
            elapsed += 500
            val s = liveState
            if (s !is LiveStreamState.Connecting && s !is LiveStreamState.Negotiating && s !is LiveStreamState.Buffering) {
                break
            }
            if (elapsed >= 20_000) {
                liveState = LiveStreamState.Error(StreamError.TIMEOUT)
                onFatal(StreamError.TIMEOUT.name)
                break
            }
            if (s is LiveStreamState.Connecting) {
                liveState = LiveStreamState.Connecting(elapsed)
            }
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
            admHolder.getAndSet(null)?.release()

            videoRevealed = false
            liveState = LiveStreamState.Connecting(0)

            try {
                val server = serverRepo.activeServer() ?: return@withContext
                // baseUrl is passed in by the caller (CamerasScreen), which already resolves
                // local-vs-public URL via CamerasViewModel's SSID tracking (including the debug
                // simulated-SSID override) — recomputing it here from a raw WifiMonitor read
                // would silently ignore that override.
                val effectiveBaseUrl = baseUrl
                Log.i("WebRtcLiveTile", "[DEBUG-WebRTC] Starting connection. Effective Base URL: $effectiveBaseUrl")

                val rawSecret = credStore.rawSecret(server.id) ?: ""
                val token =
                    if (rawSecret.startsWith(CredentialStore.BEARER_PREFIX)) {
                        rawSecret.removePrefix(CredentialStore.BEARER_PREFIX)
                    } else {
                        ""
                    }

                val origin = effectiveBaseUrl.trimEnd('/')

                // 1. Establish global session
                val globalWsUrl =
                    effectiveBaseUrl
                        .trimEnd('/')
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + "/ws"

                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Global WS URL: $globalWsUrl")

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
                                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Global WS Opened")
                                webSocket.send("onConnect")
                            }
                        },
                    )
                wsGlobalHolder.set(globalWs)

                // 2. PeerConnection Setup
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )

                // WebRTC's default audio device module plays out with USAGE_VOICE_COMMUNICATION,
                // which makes Android treat a muted camera tile like an active phone call —
                // ducking/reprocessing other apps' audio even though this app emits no sound.
                // Media-style attributes avoid that call-like audio focus/routing behavior.
                val adm =
                    JavaAudioDeviceModule
                        .builder(context)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build(),
                        ).createAudioDeviceModule()
                admHolder.set(adm)

                val pcf =
                    PeerConnectionFactory
                        .builder()
                        .setAudioDeviceModule(adm)
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
                                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Local ICE Candidate: ${candidate.sdp}")
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
                                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] ICE Connection State: $newState")
                                when (newState) {
                                    PeerConnection.IceConnectionState.CONNECTED -> {
                                        if (liveState !is LiveStreamState.Playing) {
                                            liveState = LiveStreamState.Buffering
                                        }
                                    }

                                    PeerConnection.IceConnectionState.FAILED,
                                    PeerConnection.IceConnectionState.DISCONNECTED,
                                    -> {
                                        handleFailure(StreamError.ICE_FAILED)
                                    }

                                    else -> {}
                                }
                            }

                            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                                val track = transceiver?.receiver?.track() ?: return
                                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Received track: ${track.id()} type: ${track.kind()}")
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
                    effectiveBaseUrl
                        .trimEnd('/')
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + "/live/webrtc/api/ws?src=$cameraName"

                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Stream WS URL: $streamWsUrl")

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
                                Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Stream WS Opened")
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
                                        Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Received WebRTC Answer")
                                        if (liveState is LiveStreamState.Connecting) {
                                            liveState = LiveStreamState.Negotiating
                                        }
                                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, value)
                                        pc.setRemoteDescription(
                                            object : SdpObserver {
                                                override fun onCreateSuccess(sdp: SessionDescription?) {}

                                                override fun onSetSuccess() {
                                                    Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Remote Description Set")
                                                }

                                                override fun onCreateFailure(s: String?) {}

                                                override fun onSetFailure(s: String?) {
                                                    Log.e("WebRtcLiveTile", "[DEBUG-WebRTC] Failed to set Remote Description: $s")
                                                }
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
                                handleFailure(StreamError.NETWORK)
                            }
                        },
                    )
                wsStreamHolder.set(streamWs)
            } catch (t: Throwable) {
                handleFailure(StreamError.NETWORK)
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
            admHolder.getAndSet(null)?.release()
        }
    }

    BoxWithConstraints(
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
        // Fit-within sizing: letterbox the renderer to the video's real aspect ratio instead of
        // stretching to fillMaxSize. SurfaceViewRenderer.setScalingType alone doesn't reliably
        // letterbox when the AndroidView itself is forced to fill its container.
        val fitHeightFromWidth = maxWidth / videoAspectRatio
        val rendererModifier =
            if (fitHeightFromWidth <= maxHeight) {
                Modifier.width(maxWidth).height(fitHeightFromWidth)
            } else {
                Modifier.width(maxHeight * videoAspectRatio).height(maxHeight)
            }

        // 1. WebRTC Renderer — always in tree so the surface exists before first frame.
        // We key it on the session to ensure a fresh renderer (and surface) if eglBase changes.
        key(baseUrl, cameraName, okHttpClient) {
            AndroidView(
                modifier =
                    rendererModifier.graphicsLayer {
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
                                    videoRevealed = true
                                    liveState = LiveStreamState.Playing
                                }

                                override fun onFrameResolutionChanged(
                                    w: Int,
                                    h: Int,
                                    r: Int,
                                ) {
                                    if (w > 0 && h > 0) {
                                        videoAspectRatio = w.toFloat() / h.toFloat()
                                    }
                                }
                            },
                        )
                        setEnableHardwareScaler(true)
                        setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        rendererHolder.set(this)
                    }
                },
                onRelease = { renderer ->
                    renderer.release()
                    rendererHolder.compareAndSet(renderer, null)
                },
            )
        }

        // Bounding Boxes
        if (showBoundingBoxes) {
            objectStates.forEach { objState ->
                BoundingBoxOverlay(objState)
            }
        }

        // 2. Poster / spinner / error overlay — after AndroidView so it sits above the video layer.
        StreamOverlay(
            state = liveState,
            posterUrl = snapshotUrl,
            showPoster = showLastImageWhileLoading,
            videoRevealed = videoRevealed,
            onRetry = {
                autoReconnectAttempts = 0
                retryCount++
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Mute toggle
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("webrtc_mute_button"),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        IconButton(
            onClick = onToggleFullScreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp).testTag("webrtc_fullscreen_button"),
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
