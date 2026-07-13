package net.triton.frigateviewer.feature.cameras

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The process-wide WebRTC stack: one EglBase, one PeerConnectionFactory, one audio device module,
 * created on first use and **never disposed**.
 *
 * This exists because the previous design built and tore all three down *per tile*, on every camera
 * switch — and libwebrtc cannot survive that. Its NetworkMonitor is a process-global singleton that
 * holds raw native observer pointers registered by the factory, so disposing one factory while
 * another is being created leaves the monitor calling into freed memory. Tapping quickly between
 * channels crashed the process natively, two different ways:
 *
 *   - SIGSEGV, null dereference at 0x4 on the `ConnectivityThr` thread, with
 *     `org.webrtc.NetworkMonitor.notifyObservers` in the Java frames — the global monitor calling a
 *     dead factory's observer.
 *   - SIGBUS (BUS_ADRALN) on libwebrtc's `network_thread`, faulting inside the scudo heap — a
 *     use-after-free of factory-owned internals still in flight on that thread.
 *
 * Neither is catchable from Kotlin: by the time they fire, the damage is a dangling native pointer.
 * The fix has to be structural — make the factory as long-lived as libwebrtc already assumes it is,
 * and create/dispose only the PeerConnection, which genuinely is per-session.
 *
 * Never disposing is deliberate, not a leak to tidy up later: these are singletons for the life of
 * the process, and `PeerConnectionFactory.dispose()` is the very call that makes the crash possible.
 */
object WebRtcCore {
    /** Shared render context, so renderers can be created and released freely around it. */
    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory =
        factory ?: synchronized(this) {
            factory ?: build(context.applicationContext).also { factory = it }
        }

    private fun build(appContext: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )

        // WebRTC's default audio device module plays out with USAGE_VOICE_COMMUNICATION, which makes
        // Android treat a muted camera tile like an active phone call — ducking other apps' audio even
        // though this app emits no sound. Media attributes avoid that call-like routing.
        val audioModule =
            JavaAudioDeviceModule
                .builder(appContext)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                ).createAudioDeviceModule()

        return PeerConnectionFactory
            .builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
