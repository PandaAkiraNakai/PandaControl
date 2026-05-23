package io.github.pandaakira.apppanda.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class BackendConfig(
    val baseUrl: String = "",
    val token: String = "",
) {
    /** El token es opcional cuando el backend acepta auth por identidad
     *  Tailscale (chequea peer IP + UserProfile.LoginName). */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

class Settings(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
    }

    val config: Flow<BackendConfig> = context.dataStore.data.map { prefs ->
        BackendConfig(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            token = prefs[Keys.TOKEN].orEmpty(),
        )
    }

    val pushEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.PUSH_ENABLED] ?: false
    }

    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[Keys.TOKEN] = token.trim()
        }
    }

    suspend fun setPushEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PUSH_ENABLED] = enabled }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
