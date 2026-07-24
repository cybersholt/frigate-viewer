package net.triton.frigateviewer.feature.cameras

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import net.triton.frigateviewer.LocalIsInPip
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.ServerRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
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

private const val MAX_AUTO_RECONNECTS = 2
private val BACKOFF_MS = longArrayOf(2_000L, 5_000L)

/** Sampling cadence for the Developer Options stats overlay. */
private const val STATS_INTERVAL_MS = 1_000L

/** How often the freeze watchdog looks at the last-frame timestamp. */
private const val FREEZE_CHECK_INTERVAL_MS = 2_000L

/** No frames for this long while Playing means the picture is frozen, however healthy the transport looks. */
private const val FREEZE_TIMEOUT_MS = 8_000L

/**
 * Owns one [PeerConnection]'s native lifetime.
 *
 * WebSocket callbacks (`webrtc/answer`, `webrtc/candidate`) arrive on OkHttp's reader thread and
 * can land *after* teardown disposed the peer connection — closing a WebSocket only requests a
 * close, it does not synchronously stop in-flight callbacks. Touching a disposed PeerConnection is
 * a native use-after-free rather than a Kotlin exception: it killed the process with SIGSEGV inside
 * nativeSetRemoteDescription whenever an answer was still in flight while the tile was being torn
 * down (reproduced by leaving fullscreen, which tears down + rotates + redials all at once).
 *
 * [use] and [dispose] are mutually exclusive, and [use] no-ops once disposed, so a late callback is
 * dropped instead of dereferencing freed memory.
 */
private class PcSession(
    private val pc: PeerConnection,
) {
    private var disposed = false

    /** Runs [block] on the live PeerConnection; returns false (doing nothing) if already disposed. */
    fun use(block: (PeerConnection) -> Unit): Boolean =
        synchronized(this) {
            if (disposed) return@synchronized false
            block(pc)
            true
        }

    fun dispose() =
        synchronized(this) {
            if (!disposed) {
                disposed = true
                pc.dispose()
            }
        }
}

/**
 * One `getStats()` round-trip, suspending until WebRTC delivers the report.
 *
 * Goes through [PcSession.use] like every other native call: the stats poll runs on its own 1 Hz
 * loop and would otherwise be one more way to touch a disposed PeerConnection. Returns null if the
 * session is already gone, which just skips this sample.
 */
private suspend fun PcSession.awaitStats(): org.webrtc.RTCStatsReport? {
    val deferred = kotlinx.coroutines.CompletableDeferred<org.webrtc.RTCStatsReport>()
    val started = use { pc -> pc.getStats { report -> deferred.complete(report) } }
    if (!started) return null
    return deferred.await()
}

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
    showStreamStats: Boolean = false,
    /** From Frigate's config (cameras.<name>.audio.enabled). With audio off there is nothing to unmute. */
    audioEnabled: Boolean = false,
    onFatal: (String) -> Unit = {},
    onStateChanged: (LiveStreamState) -> Unit = {},
) {
    val context = LocalContext.current
    // Frames arrive on WebRTC's render thread; Compose state has to be written on main.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Shared, process-wide, never disposed — see WebRtcCore. Creating a factory and EglBase per tile
    // and disposing them on every camera switch is exactly what crashed libwebrtc's global
    // NetworkMonitor when channels were tapped in quick succession.
    val eglBase = WebRtcCore.eglBase

    val pcHolder = remember { AtomicReference<PcSession?>() }
    val wsGlobalHolder = remember { AtomicReference<WebSocket?>() }
    val wsStreamHolder = remember { AtomicReference<WebSocket?>() }
    val rendererHolder = remember { AtomicReference<SurfaceViewRenderer?>() }

    // The remote video track and the SurfaceViewRenderer are created by two independent races: the
    // track arrives on WebRTC's signaling thread the moment media negotiates, while the renderer is
    // created whenever Compose gets around to running the AndroidView factory. Whoever lands second
    // has to do the wiring — attaching the sink only from onTrack (the original behavior) silently
    // dropped the video whenever the track won that race, and since RTP kept flowing the tile looked
    // alive in every way except the picture: bandwidth ticked, frames decoded, drop rate stayed
    // near zero, and the tile still timed out at 20s because onFirstFrameRendered never fired.
    val videoTrackHolder = remember { AtomicReference<VideoTrack?>() }

    // Wall-clock of the last frame that actually reached the surface. Written from WebRTC's render
    // thread, read by the freeze watchdog.
    val lastFrameAtMs =
        remember {
            java.util.concurrent.atomic
                .AtomicLong(0L)
        }

    // Monotonic id for the current dial attempt. Every WebSocket callback carries the generation it
    // was created under and does nothing unless it is still the live one.
    //
    // Teardown cancel()s the old sockets, and cancel() delivers onFailure("Socket closed") to their
    // listeners — those listeners are the *previous* attempt's, but they still closed over
    // handleFailure, so they reported our own teardown as a network failure, which scheduled another
    // retry, which tore down again: a self-feeding reconnect loop that left the tile buffering
    // forever even though every single attempt had actually connected (ICE COMPLETED, track
    // received). Gating on generation is what makes a superseded session's callbacks inert.
    val sessionGeneration =
        remember {
            java.util.concurrent.atomic
                .AtomicInteger(0)
        }
    val audioTrackHolder = remember { AtomicReference<org.webrtc.AudioTrack?>() }
    val pendingCandidates = remember { mutableListOf<IceCandidate>() }

    var isMuted by remember { mutableStateOf(true) }
    // Pinch-zoom / pan, clamped so the picture always covers the viewport. See ZoomPanState.kt.
    val zoomPan = rememberZoomPanState()
    var retryCount by remember { mutableStateOf(0) }
    // Native frame aspect ratio, so the renderer can be sized to fit-within its container
    // (letterboxed) at the Compose layout level — SurfaceViewRenderer.setScalingType alone
    // doesn't reliably letterbox when the AndroidView itself is forced to fillMaxSize.
    var videoAspectRatio by remember { mutableStateOf(16f / 9f) }
    var autoReconnectAttempts by remember { mutableStateOf(0) }
    var liveState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }
    var videoRevealed by remember { mutableStateOf(false) }

    /**
     * Frames arrive through this proxy rather than being handed straight to the SurfaceViewRenderer.
     *
     * SurfaceViewRenderer's onFirstFrameRendered fires exactly once per *renderer* lifetime, and the
     * renderer is not recreated on a reconnect — so after any reconnect the new track rendered fine
     * while liveState stayed on Buffering forever and the poster kept covering perfectly good video.
     * That's the "frozen at buffering 85% while bandwidth ticks" report: the stream was healthy, the
     * state machine was stuck. Counting frames ourselves makes "is it playing?" a question about
     * frames rather than about a callback that only fires once.
     */
    val frameSinkHolder = remember { AtomicReference<org.webrtc.VideoSink?>() }
    val currentLiveState by rememberUpdatedState(liveState)
    DisposableEffect(Unit) {
        val sink =
            org.webrtc.VideoSink { frame ->
                lastFrameAtMs.set(System.currentTimeMillis())
                rendererHolder.get()?.onFrame(frame)
                if (currentLiveState !is LiveStreamState.Playing) {
                    // onFrame runs on WebRTC's render thread; Compose state must be touched on main.
                    mainHandler.post {
                        videoRevealed = true
                        liveState = LiveStreamState.Playing
                    }
                }
            }
        frameSinkHolder.set(sink)
        onDispose { frameSinkHolder.set(null) }
    }

    // Freeze watchdog. A stream can stop delivering frames while ICE stays CONNECTED and RTP keeps
    // flowing — the picture just stops. Nothing in WebRTC reports that as a failure, so without this
    // the tile sits on a frozen frame indefinitely. Redial rather than wait for a failure that will
    // never come.
    LaunchedEffect(retryCount) {
        while (true) {
            delay(FREEZE_CHECK_INTERVAL_MS)
            if (liveState !is LiveStreamState.Playing) continue
            val last = lastFrameAtMs.get()
            if (last > 0L && System.currentTimeMillis() - last > FREEZE_TIMEOUT_MS) {
                Log.w("WebRtcLiveTile", "No frames for ${FREEZE_TIMEOUT_MS}ms on $cameraName — redialling")
                lastFrameAtMs.set(0L)
                autoReconnectAttempts = 0
                retryCount++
            }
        }
    }
    var objectStates by remember { mutableStateOf<List<FrigateObjectState>>(emptyList()) }

    LaunchedEffect(liveState) { onStateChanged(liveState) }

    val entryPoint = remember { EntryPointAccessors.fromApplication(context, WebRtcEntryPoint::class.java) }
    val credStore = entryPoint.credentialStore()
    val serverRepo = entryPoint.serverRepository()

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /**
     * Binds the remote video track to the renderer, whenever both exist. Safe to call repeatedly:
     * removeSink first, since addSink on an already-attached sink would deliver every frame twice.
     */
    fun attachVideoSink() {
        val track = videoTrackHolder.get() ?: return
        val sink = frameSinkHolder.get() ?: return
        runCatching {
            track.removeSink(sink)
            track.addSink(sink)
        }.onFailure { Log.w("WebRtcLiveTile", "Could not attach video sink for $cameraName", it) }
    }

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

    // A different camera is a fresh start, not a continuation. This composable is reused across
    // camera switches (same call site, only cameraName changes), so without this the new camera
    // inherits the previous one's spent retry budget — two failures against the old camera and the
    // new one goes straight to "live view unavailable" without being given a single real attempt.
    LaunchedEffect(baseUrl, cameraName, okHttpClient) {
        autoReconnectAttempts = 0
    }

    // The pending backoff retry, if one is queued. Held so it can be cancelled — a retry scheduled by
    // a failure is worthless once a later attempt has connected, and firing it anyway tears down the
    // working session. Coming back from a long background does exactly that: the first dial times out
    // (network still waking), queues a retry, the next dial succeeds — and then the queued retry kills
    // it. Two such failures also exhaust MAX_AUTO_RECONNECTS, so the tile finally gives up with "live
    // view unavailable" while a perfectly good stream is running behind it.
    val pendingRetry = remember { AtomicReference<kotlinx.coroutines.Job?>() }

    /** Classifies a transient failure: auto-retries up to [MAX_AUTO_RECONNECTS] with backoff, else terminal Error. */
    fun handleFailure(reason: StreamError) {
        if (autoReconnectAttempts < MAX_AUTO_RECONNECTS) {
            autoReconnectAttempts++
            liveState = LiveStreamState.Reconnecting(autoReconnectAttempts)
            val delayMs = BACKOFF_MS[autoReconnectAttempts - 1]
            // Only ever one retry in flight; a second failure supersedes the first rather than
            // queueing a second teardown behind it.
            pendingRetry
                .getAndSet(
                    scope.launch {
                        delay(delayMs)
                        retryCount++
                    },
                )?.cancel()
        } else {
            liveState = LiveStreamState.Error(reason)
            onFatal(reason.name)
        }
    }

    // Playing means the stream is good: drop any queued retry, and hand the next transient failure a
    // fresh budget instead of one already spent on a stall the connection has since recovered from.
    LaunchedEffect(liveState) {
        if (liveState is LiveStreamState.Playing) {
            pendingRetry.getAndSet(null)?.cancel()
            autoReconnectAttempts = 0
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
            if (s !is LiveStreamState.Connecting &&
                s !is LiveStreamState.Negotiating &&
                s !is LiveStreamState.Buffering
            ) {
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
            // Claim the new generation BEFORE tearing anything down, never after.
            //
            // cancel() returns immediately and OkHttp delivers onFailure("Socket closed") later, on
            // its own reader thread. Bumping the generation *after* the cancel() calls below leaves a
            // window in which the outgoing session is still the current generation — and that reader
            // thread routinely wins it. The old listener then sees itself as live, mistakes our own
            // teardown for a network failure, and schedules a retry that tears down the session which
            // replaced it: the reconnect loop that left a camera buffering forever after a switch.
            // Claiming first makes everything cancelled below stale by construction.
            val generation = sessionGeneration.incrementAndGet()

            fun isCurrent() = sessionGeneration.get() == generation

            // cancel() rather than close(): close() is a graceful handshake that keeps delivering
            // queued messages to the listener, which is precisely how a stale webrtc/answer reached
            // a disposed PeerConnection (a native use-after-free crash).
            audioTrackHolder.getAndSet(null)?.setEnabled(false)
            // Drop the sink before the PeerConnection (and with it the track) is disposed below.
            videoTrackHolder.getAndSet(null)?.let { track ->
                frameSinkHolder.get()?.let { sink -> runCatching { track.removeSink(sink) } }
            }
            wsStreamHolder.getAndSet(null)?.cancel()
            wsGlobalHolder.getAndSet(null)?.cancel()
            pcHolder.getAndSet(null)?.dispose()

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

                // 2. PeerConnection setup. The factory (and its audio device module and EGL context)
                // is process-wide and outlives this tile — only the PeerConnection below is
                // per-session, and only it gets disposed.
                val pcf = WebRtcCore.factory(context)

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
                                // A peer connection being torn down walks through DISCONNECTED/CLOSED
                                // on its way out; that is not a failure of whatever replaced it.
                                if (!isCurrent()) return
                                when (newState) {
                                    // COMPLETED is a terminal *success* state (ICE finished checking
                                    // and settled on a pair) and does not have to be preceded by a
                                    // CONNECTED callback. Ignoring it meant a connection that went
                                    // straight to COMPLETED never left Connecting.
                                    PeerConnection.IceConnectionState.CONNECTED,
                                    PeerConnection.IceConnectionState.COMPLETED,
                                    -> {
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
                                Log.d(
                                    "WebRtcLiveTile",
                                    "[DEBUG-WebRTC] Received track: ${track.id()} type: ${track.kind()}",
                                )
                                when (track) {
                                    is VideoTrack -> {
                                        track.setEnabled(true)
                                        videoTrackHolder.set(track)
                                        attachVideoSink()
                                    }

                                    is org.webrtc.AudioTrack -> {
                                        track.setEnabled(!isMuted)
                                        audioTrackHolder.set(track)
                                    }
                                }
                            }
                        },
                    ) ?: throw IllegalStateException("PC failed")
                val session = PcSession(pc)
                pcHolder.set(session)

                session.use {
                    it.addTransceiver(
                        org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        org.webrtc.RtpTransceiver.RtpTransceiverInit(
                            org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                        ),
                    )
                    it.addTransceiver(
                        org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                        org.webrtc.RtpTransceiver.RtpTransceiverInit(
                            org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                        ),
                    )
                }

                val offer = createOffer(session)
                session.setLocalDescriptionAwait(offer)

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
                                // Same reasoning as onFailure: a superseded session must not push SDP,
                                // ICE candidates or object states into the tile that replaced it.
                                if (!isCurrent()) return
                                Log.v("WebRtcLiveTile", "WS message: $text")
                                val obj =
                                    runCatching { Json.parseToJsonElement(text).let { it as JsonObject } }.getOrNull()
                                        ?: return

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

                                // Every native call below goes through the session: these callbacks
                                // land on OkHttp's reader thread and routinely arrive after teardown
                                // has disposed the PeerConnection, which is a hard native crash
                                // rather than a catchable failure.
                                when (type) {
                                    "webrtc/answer" -> {
                                        Log.d("WebRtcLiveTile", "[DEBUG-WebRTC] Received WebRTC Answer")
                                        if (liveState is LiveStreamState.Connecting) {
                                            liveState = LiveStreamState.Negotiating
                                        }
                                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, value)
                                        val applied =
                                            session.use { livePc ->
                                                livePc.setRemoteDescription(
                                                    object : SdpObserver {
                                                        override fun onCreateSuccess(sdp: SessionDescription?) {}

                                                        override fun onSetSuccess() {
                                                            Log.d(
                                                                "WebRtcLiveTile",
                                                                "[DEBUG-WebRTC] Remote Description Set",
                                                            )
                                                        }

                                                        override fun onCreateFailure(s: String?) {}

                                                        override fun onSetFailure(s: String?) {
                                                            Log.e(
                                                                "WebRtcLiveTile",
                                                                "[DEBUG-WebRTC] Failed to set Remote Description: $s",
                                                            )
                                                        }
                                                    },
                                                    sdp,
                                                )
                                            }
                                        if (!applied) {
                                            Log.d(
                                                "WebRtcLiveTile",
                                                "Answer arrived after teardown for $cameraName — dropped",
                                            )
                                        }
                                    }

                                    "webrtc/candidate" -> {
                                        session.use { livePc -> livePc.addIceCandidate(IceCandidate("", 0, value)) }
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                // A cancelled socket reports "Socket closed" here. If this session has
                                // been superseded (camera switched, retry, teardown), that failure is
                                // our own doing — reporting it would schedule a retry that kills the
                                // session which replaced us.
                                if (!isCurrent()) {
                                    Log.d(
                                        "WebRtcLiveTile",
                                        "Ignoring WS failure from superseded session for $cameraName",
                                    )
                                    return
                                }
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
            // Retire the session FIRST, exactly as the connect effect does. This is the other path
            // that cancels the sockets — it runs whenever the camera changes — and it used to do so
            // without retiring the generation, so the outgoing camera's listener still believed it
            // was live, reported our own cancel() as a network failure, and bumped retryCount. That
            // is the old camera reaching out and disturbing the new one's connection: the "still
            // connected to ch1 while ch2 is connecting" behavior.
            sessionGeneration.incrementAndGet()

            audioTrackHolder.getAndSet(null)?.setEnabled(false)
            // Drop the sink before the PeerConnection (and with it the track) is disposed below.
            videoTrackHolder.getAndSet(null)?.let { track ->
                frameSinkHolder.get()?.let { sink -> runCatching { track.removeSink(sink) } }
            }
            wsStreamHolder.getAndSet(null)?.cancel()
            wsGlobalHolder.getAndSet(null)?.cancel()
            pcHolder.getAndSet(null)?.dispose()
        }
    }

    BoxWithConstraints(
        modifier =
            (if (isFullScreen) Modifier.fillMaxSize() else modifier)
                .background(Color.Black)
                // The viewport is what the pan is clamped against, so it has to be measured, not
                // assumed — it changes on rotation and when the tile enters/leaves fullscreen.
                .onSizeChanged {
                    zoomPan.onViewportChanged(Size(it.width.toFloat(), it.height.toFloat()))
                }.pointerInput("zoom") {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        zoomPan.onTransform(centroid, pan, zoom)
                    }
                }.pointerInput("tap") {
                    detectTapGestures(onDoubleTap = { zoomPan.reset() })
                },
        contentAlignment = Alignment.Center,
    ) {
        // Fit-within sizing: letterbox the renderer to the video's real aspect ratio instead of
        // stretching to fillMaxSize. SurfaceViewRenderer.setScalingType alone doesn't reliably
        // letterbox when the AndroidView itself is forced to fill its container.
        val fitHeightFromWidth = maxWidth / videoAspectRatio
        val rendererModifier =
            when {
                // Never hand the renderer a zero-sized layout, not even for one transient measure
                // pass. A 0x0 SurfaceView destroys and recreates its surface, and the Exynos hardware
                // H.264 decoder rejects the resulting setOutputSurface with BAD_INDEX — WebRTC then
                // quietly falls back to the SOFTWARE AVC decoder, which cannot keep up with a
                // 3840x2160 stream. That is the source of the dropped seconds on 4K: not the network,
                // a decoder demotion caused by a momentary zero-size layout.
                maxWidth <= 0.dp || maxHeight <= 0.dp -> Modifier.fillMaxSize()

                fitHeightFromWidth <= maxHeight -> Modifier.width(maxWidth).height(fitHeightFromWidth)

                else -> Modifier.width(maxHeight * videoAspectRatio).height(maxHeight)
            }

        // 1. WebRTC Renderer — always in tree so the surface exists before first frame.
        // We key it on the session to ensure a fresh renderer (and surface) if eglBase changes.
        key(baseUrl, cameraName, okHttpClient) {
            AndroidView(
                modifier =
                    rendererModifier.graphicsLayer {
                        scaleX = zoomPan.scale
                        scaleY = zoomPan.scale
                        translationX = zoomPan.offset.x
                        translationY = zoomPan.offset.y
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
                                        // Pan is clamped against the picture, not the tile, so the
                                        // clamp needs the real frame shape too.
                                        zoomPan.onAspectRatioChanged(videoAspectRatio)
                                    }
                                }
                            },
                        )
                        setEnableHardwareScaler(true)
                        setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        rendererHolder.set(this)
                        // The track may already be here — see attachVideoSink's note on the race.
                        attachVideoSink()
                    }
                },
                onRelease = { renderer ->
                    // Detach before releasing: a track still holding a released renderer as a sink
                    // hands frames to a dead surface.
                    runCatching { frameSinkHolder.get()?.let { videoTrackHolder.get()?.removeSink(it) } }
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

        // Hidden in PiP: the window is a few hundred pixels wide, so the overlay covers the picture
        // it's meant to describe. Restores on the way out — this reads the state, it doesn't set it.
        if (showStreamStats && !LocalIsInPip.current) {
            WebRtcStatsOverlay(
                pcHolder = pcHolder,
                cameraName = cameraName,
                retryCount = retryCount,
                // Clears the protocol/LIVE badge row above it; drag it anywhere from there.
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 6.dp),
            )
        }

        // Tile chrome is suppressed in PiP: the window is a few hundred pixels wide, so buttons
        // meant for a full-size tile just cover the picture. Android's PiP guidelines call for
        // minimal chrome, and the system already provides its own controls.
        if (!LocalIsInPip.current) {
            // Mute toggle — only when the camera actually publishes audio. Frigate's config says
            // whether it does; offering an unmute control for a silent camera is a dead button.
            if (audioEnabled) {
                IconButton(
                    onClick = { isMuted = !isMuted },
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("webrtc_mute_button"),
                ) {
                    Icon(
                        imageVector =
                            if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Bottom-left, above the mute button when there is one so the two never overlap.
            ZoomIndicator(
                state = zoomPan,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = if (audioEnabled) 60.dp else 12.dp),
            )

            IconButton(
                onClick = onToggleFullScreen,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 8.dp)
                        .testTag("webrtc_fullscreen_button"),
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
}

/**
 * Developer Options telemetry, sampled at 1 Hz off the PeerConnection.
 *
 * Deliberately its own composable rather than state in [WebRtcLiveTile]: a new sample every second
 * invalidates whatever scope holds it, and holding it in the tile meant recomposing the entire tile
 * — video layer, overlays and all — once per second, which visibly cost frames on a 4K stream
 * (dropped seconds in the camera's own burnt-in timestamp). Confined here, a sample invalidates only
 * this overlay, and the video path is untouched.
 *
 * Counters are polled rather than accumulated from callbacks because WebRTC's are cumulative:
 * bandwidth is the derivative of bytesReceived between two samples, so a fixed cadence is what makes
 * the number mean anything.
 */
@Composable
private fun WebRtcStatsOverlay(
    pcHolder: AtomicReference<PcSession?>,
    cameraName: String,
    retryCount: Int,
    modifier: Modifier = Modifier,
) {
    var statsSample by remember { mutableStateOf<StreamStats?>(null) }
    var statsHistory by remember { mutableStateOf(StreamStatsHistory()) }

    LaunchedEffect(cameraName, retryCount) {
        var lastBytes = 0L
        var lastSampleAt = 0L
        while (true) {
            delay(STATS_INTERVAL_MS)
            val session = pcHolder.get() ?: continue
            val report = session.awaitStats() ?: continue
            val now = System.currentTimeMillis()

            // "kind" is the current spec name; older libwebrtc builds emit "mediaType". Accept either,
            // and if neither video track is tagged, fall back to the sole inbound-rtp entry.
            val inboundEntries = report.statsMap.values.filter { it.type == "inbound-rtp" }
            val inbound =
                inboundEntries.firstOrNull { s ->
                    s.members["kind"] == "video" || s.members["mediaType"] == "video"
                } ?: inboundEntries.singleOrNull()

            // bytesReceived is an unsigned 64-bit value, which the JNI layer surfaces as a BigInteger
            // rather than a Long — hence Number, not a direct Long cast (that cast failing silently is
            // exactly how bandwidth ends up permanently blank). If inbound-rtp has no byte counter,
            // the transport-level entry does.
            val bytes =
                (inbound?.members?.get("bytesReceived") as? Number)?.toLong()
                    ?: (
                        report.statsMap.values
                            .firstOrNull { it.type == "transport" }
                            ?.members
                            ?.get("bytesReceived") as? Number
                    )?.toLong()
                    ?: 0L
            val elapsedSec = if (lastSampleAt == 0L) 0.0 else (now - lastSampleAt) / 1000.0
            val bandwidthKbps =
                if (elapsedSec > 0 && bytes >= lastBytes) {
                    (bytes - lastBytes) * 8.0 / 1000.0 / elapsedSec
                } else {
                    null
                }
            lastBytes = bytes
            lastSampleAt = now

            // RTT lives on the *selected* candidate pair; a non-succeeded pair's RTT is stale.
            val rttSeconds =
                report.statsMap.values
                    .firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
                    ?.members
                    ?.get("currentRoundTripTime") as? Number

            val codecId = inbound?.members?.get("codecId") as? String
            val codec =
                codecId
                    ?.let { report.statsMap[it]?.members?.get("mimeType") as? String }
                    ?.substringAfter('/')

            val width = (inbound?.members?.get("frameWidth") as? Number)?.toInt()
            val height = (inbound?.members?.get("frameHeight") as? Number)?.toInt()

            val sample =
                StreamStats(
                    streamType = "webrtc",
                    bandwidthKbps = bandwidthKbps,
                    latencyMs = rttSeconds?.let { it.toDouble() * 1000.0 },
                    framesTotal = (inbound?.members?.get("framesReceived") as? Number)?.toLong(),
                    framesDecoded = (inbound?.members?.get("framesDecoded") as? Number)?.toLong(),
                    framesDropped = (inbound?.members?.get("framesDropped") as? Number)?.toLong(),
                    // No read-ahead field: WebRTC is realtime and does not buffer ahead.
                    resolution = if (width != null && height != null) "${width}x$height" else null,
                    codec = codec,
                )
            statsSample = sample
            statsHistory += sample
        }
    }

    statsSample?.let { sample ->
        StreamStatsOverlay(stats = sample, history = statsHistory, modifier = modifier)
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

private suspend fun createOffer(session: PcSession): SessionDescription {
    val deferred = kotlinx.coroutines.CompletableDeferred<SessionDescription>()
    val started =
        session.use { pc ->
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
        }
    if (!started) throw IllegalStateException("PeerConnection disposed before offer")
    return deferred.await()
}

private suspend fun PcSession.setLocalDescriptionAwait(sdp: SessionDescription) {
    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    val started =
        use { pc ->
            pc.setLocalDescription(
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
        }
    if (!started) throw IllegalStateException("PeerConnection disposed before local description")
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
