package com.aisandbox.android.net

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the single active [ServerProfile] in DataStore Preferences.
 *
 * <p>UC04 says "one server profile at a time" — the store holds at most
 * one entry. The data is keyed under [SERVER_PROFILE_KEY] as a
 * JSON-encoded blob; tiny enough that we don't need a separate
 * Proto-DataStore.
 *
 * <p>Why preferences and not [androidx.security.crypto.EncryptedSharedPreferences]:
 * the *secret* is the client cert (which lives in the Android KeyStore,
 * not here). The data this store holds — server URL, server cert pin,
 * displayable CN, expiry — is non-secret. Keeping it in plain DataStore
 * keeps the read path synchronous-enough on cold start.
 */
class ServerProfileStore(private val context: Context) {

    /** Latest persisted profile, or `null` when no profile has been imported. */
    val profile: Flow<ServerProfile?> =
        context.serverProfileDataStore.data.map { prefs ->
            prefs[SERVER_PROFILE_KEY]?.let { raw ->
                runCatching { JSON.decodeFromString<ServerProfile>(raw) }.getOrNull()
            }
        }

    /** One-shot read for non-coroutine contexts (e.g. service onStartCommand). */
    suspend fun current(): ServerProfile? = profile.first()

    /** Overwrite the active profile (single-tenant — there is no second slot). */
    suspend fun save(profile: ServerProfile) {
        val raw = JSON.encodeToString(profile)
        context.serverProfileDataStore.edit { it[SERVER_PROFILE_KEY] = raw }
    }

    /** Wipe the profile — UC04-7 cert-revoke path + the "replace existing" confirm. */
    suspend fun clear() {
        context.serverProfileDataStore.edit { it.remove(SERVER_PROFILE_KEY) }
    }

    companion object {
        private val SERVER_PROFILE_KEY = stringPreferencesKey("server_profile_v1")
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Top-level `Context.serverProfileDataStore` accessor (preferences delegate). */
private val Context.serverProfileDataStore: androidx.datastore.core.DataStore<Preferences>
        by preferencesDataStore(name = "server_profile")
