package net.triton.frigateviewer.core.network

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Per-server custom trust anchors.
 *
 * Frigate users routinely deploy with self-signed certs. We do NOT trust-all — instead,
 * the user imports a single PEM via Settings → Server → "Pin certificate". We build
 * a TrustManager that accepts that exact cert (plus system CAs).
 *
 * NEVER use this with a permissive TrustManager. NEVER expose a "trust everything" toggle.
 */
object TrustConfig {
    fun applyPinnedCertificate(
        builder: OkHttpClient.Builder,
        pemBytes: ByteArray?,
    ): OkHttpClient.Builder {
        if (pemBytes == null || pemBytes.isEmpty()) return builder
        val cf = CertificateFactory.getInstance("X.509")
        val cert = pemBytes.inputStream().use { cf.generateCertificate(it) as X509Certificate }
        val ks =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("frigate-pinned", cert)
            }
        val tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
            }
        val tms = tmf.trustManagers
        check(tms.size == 1 && tms[0] is X509TrustManager) { "Unexpected TrustManager configuration" }
        val tm = tms[0] as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return builder.sslSocketFactory(sslContext.socketFactory, tm)
    }

    fun applyAllowUntrusted(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tm =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {}

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        val sslContext =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(tm), null)
            }
        return builder
            .sslSocketFactory(sslContext.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
    }
}
