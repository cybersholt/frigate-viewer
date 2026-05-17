package net.triton.frigateviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent server list + active selection.
 *
 * Settings stored in plaintext Preferences DataStore.
 * Credentials (username only persisted here; password lives in [CredentialStore]).
 *
 * Migration policy: every schema change adds a new key and migration block.
 * NEVER mutate persisted JSON shape in place — bug class from prior RN app.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val json: Json,
) {

    private val serversKey = stringPreferencesKey(KEY_SERVERS)
    private val activeKey = stringPreferencesKey(KEY_ACTIVE)

    val servers: Flow<List<Server>> = store.data.map { prefs ->
        val raw = prefs[serversKey] ?: return@map emptyList()
        runCatching { json.decodeFromString(ListSerializer(Server.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    val activeServerId: Flow<String?> = store.data.map { it[activeKey] }

    suspend fun all(): List<Server> = servers.first()

    suspend fun activeServer(): Server? {
        val id = activeServerId.first() ?: return null
        return all().firstOrNull { it.id == id }
    }

    suspend fun upsert(server: Server) {
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current += server
        persist(current)
    }

    suspend fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }

    suspend fun setActive(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(activeKey) else prefs[activeKey] = id
        }
    }

    private suspend fun persist(list: List<Server>) {
        val encoded = json.encodeToString(ListSerializer(Server.serializer()), list)
        store.edit { it[serversKey] = encoded }
    }

    companion object {
        private const val KEY_SERVERS = "servers_json_v1"
        private const val KEY_ACTIVE = "active_server_id_v1"
    }
}
