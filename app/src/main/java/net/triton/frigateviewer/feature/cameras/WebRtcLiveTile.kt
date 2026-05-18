package net.triton.frigateviewer.feature.cameras

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/**
 * Sub-second live tile via go2rtc WebSocket signaling.
 *
 * Wire protocol (go2rtc):
 *   client -> { type: "webrtc/offer", value: "<SDP>" }
 *   server -> { type: "webrtc/candidate", value: "<candidate>" }
 *   server -> { type: "webrtc/answer", value: "<SDP>" }
 *
 * We send the offer first then trickle any remote candidates that arrive.
 * The WHEP HTTP path is deliberately avoided — it doesn't support ICE trickle,
 * which kills NAT traversal.
 */
@Composable
fun WebRtcLiveTile(
    baseUrl: String,
    cameraName: String,
    modifier: Modifier = Modifier,
    onFatal: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    val pcfHolder = remember { AtomicReference<PeerConnectionFactory?>() }
    val pcHolder = remember { AtomicReference<PeerConnection?>() }
    val wsHolder = remember { AtomicReference<WebSocket?>() }
    val rendererHolder = remember { AtomicReference<SurfaceViewRenderer?>() }

    LaunchedEffect(baseUrl, cameraName) {
        withContext(Dispatchers.IO) {
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                val pcf = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .createPeerConnectionFactory()
                pcfHolder.set(pcf)

                val rtcConfig = PeerConnection.RTCConfiguration(
                    listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                ).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                }

                val pc = pcf.createPeerConnection(rtcConfig, object : EmptyPcObserver() {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate ?: return
                        val msg = buildJsonObject {
                            put("type", "webrtc/candidate")
                            put("value", candidate.sdp)
                        }
                        wsHolder.get()?.send(msg.toString())
                    }

                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                        val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                        track.setEnabled(true)
                        rendererHolder.get()?.let { track.addSink(it) }
                    }

                    override fun onAddStream(stream: MediaStream?) {
                        // Legacy plan B path — not used with unified plan, but defensive.
                        stream?.videoTracks?.firstOrNull()?.let { t ->
                            rendererHolder.get()?.let { t.addSink(it) }
                        }
                    }
                }) ?: run {
                    onFatal("PeerConnection creation failed")
                    return@withContext
                }
                pcHolder.set(pc)

                pc.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )

                val offer = createOffer(pc)
                pc.setLocalDescriptionAwait(offer)

                val wsUrl = baseUrl.replaceFirst(Regex("^http"), "ws") + "api/ws?src=$cameraName"
                val ws = OkHttpClient().newWebSocket(
                    Request.Builder().url(wsUrl).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val offerMsg = buildJsonObject {
                                put("type", "webrtc/offer")
                                put("value", offer.description)
                            }
                            webSocket.send(offerMsg.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = runCatching { Json.parseToJsonElement(text).let { it as JsonObject } }.getOrNull() ?: return
                            val type = obj["type"]?.toString()?.trim('"') ?: return
                            val value = obj["value"]?.toString()?.trim('"') ?: return
                            when (type) {
                                "webrtc/answer" -> {
                                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, value)
                                    pc.setRemoteDescription(emptySdpObserver(), sdp)
                                }
                                "webrtc/candidate" -> {
                                    val candidate = IceCandidate("", 0, value)
                                    pc.addIceCandidate(candidate)
                                }
                                else -> Log.d("WebRtcLiveTile", "unknown msg type=$type")
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.w("WebRtcLiveTile", "ws failure", t)
                            onFatal("WebSocket failure: ${t.message}")
                        }
                    },
                )
                wsHolder.set(ws)
            } catch (t: Throwable) {
                Log.e("WebRtcLiveTile", "fatal", t)
                onFatal(t.message ?: "WebRTC error")
            }
        }
    }

    DisposableEffect(baseUrl, cameraName) {
        onDispose {
            wsHolder.getAndSet(null)?.close(1000, "dispose")
            pcHolder.getAndSet(null)?.dispose()
            pcfHolder.getAndSet(null)?.dispose()
            rendererHolder.getAndSet(null)?.release()
            eglBase.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                rendererHolder.set(this)
            }
        },
    )
}

private suspend fun createOffer(pc: PeerConnection): SessionDescription {
    val deferred = kotlinx.coroutines.CompletableDeferred<SessionDescription>()
    pc.createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (sdp != null) deferred.complete(sdp)
            else deferred.completeExceptionally(IllegalStateException("Null SDP"))
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) { deferred.completeExceptionally(IllegalStateException(s)) }
        override fun onSetFailure(s: String?) {}
    }, MediaConstraints())
    return deferred.await()
}

private suspend fun PeerConnection.setLocalDescriptionAwait(sdp: SessionDescription) {
    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { deferred.complete(Unit) }
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) { deferred.completeExceptionally(IllegalStateException(s)) }
    }, sdp)
    deferred.await()
}

private fun emptySdpObserver() = object : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(s: String?) {}
    override fun onSetFailure(s: String?) {}
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
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
}
