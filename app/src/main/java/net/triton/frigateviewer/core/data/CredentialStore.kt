package net.triton.frigateviewer.core.data

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secrets layer.
 *
 *   Layout on disk:
 *     filesDir/secrets/<serverId>.bin   -- AEAD-encrypted blob containing the password / JWT
 *     filesDir/secrets/<serverId>.pem   -- pinned certificate (not secret, but co-located)
 *
 *   Master key wrapped by Android Keystore (Tink AndroidKeysetManager).
 *
 *   We deliberately avoid EncryptedSharedPreferences — deprecated in security-crypto 1.1.0+
 *   and known to corrupt keysets on certain OEM devices (see researcher memo).
 */
@Singleton
class CredentialStore @Inject constructor(
    private val context: Context,
) {

    init { AeadConfig.register() }

    private val mutex = Mutex()

    private val aead: Aead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_PREF, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    private fun secretsDir() = File(context.filesDir, "secrets").apply { mkdirs() }
    private fun blobFile(serverId: String) = File(secretsDir(), "$serverId.bin")
    private fun certFile(serverId: String) = File(secretsDir(), "$serverId.pem")

    suspend fun setPassword(serverId: String, password: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ct = aead.encrypt(password.toByteArray(Charsets.UTF_8), AAD)
            blobFile(serverId).writeBytes(ct)
        }
    }

    suspend fun setBearer(serverId: String, jwt: String) = setPassword(serverId, BEARER_PREFIX + jwt)

    /**
     * Returns the value of the Authorization header to send, based on stored secret + auth mode.
     * Caller supplies the [Server] context via [ServerRepository] outside this class.
     * Here we just decrypt; mode logic lives in [ServerRepository].
     */
    suspend fun rawSecret(serverId: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = blobFile(serverId)
            if (!f.exists()) return@withContext null
            runCatching {
                String(aead.decrypt(f.readBytes(), AAD), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    /** Build Authorization header given the server's auth mode. */
    suspend fun authHeader(serverId: String): String? {
        val secret = rawSecret(serverId) ?: return null
        return when {
            secret.startsWith(BEARER_PREFIX) -> "Bearer ${secret.removePrefix(BEARER_PREFIX)}"
            else -> "Basic " + Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8))
        }
        // NOTE: Basic header above assumes secret already contains "user:pass". Callers that
        // store only the password must include the username inline before calling setPassword().
    }

    suspend fun delete(serverId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            blobFile(serverId).delete()
            certFile(serverId).delete()
        }
    }

    suspend fun pinnedCert(serverId: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = certFile(serverId)
        if (f.exists()) f.readBytes() else null
    }

    suspend fun setPinnedCert(serverId: String, pem: ByteArray) = withContext(Dispatchers.IO) {
        certFile(serverId).writeBytes(pem)
    }

    companion object {
        private const val KEYSET_PREF = "frigate_viewer_keyset"
        private const val KEYSET_PREF_FILE = "frigate_viewer_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://frigate_viewer_master_key"
        private const val BEARER_PREFIX = "JWT:"
        private val AAD = "FrigateViewer/v1".toByteArray(Charsets.UTF_8)
    }
}
