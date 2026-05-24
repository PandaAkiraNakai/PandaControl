package io.github.pandaakira.apppanda.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        // Categorías de notificación silenciadas (ids de NotifCategory).
        // Guardamos las APAGADAS para que cualquier categoría nueva que se
        // agregue después aparezca encendida por defecto.
        val MUTED_NOTIFS = stringSetPreferencesKey("muted_notifs")
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

    /** Ids de NotifCategory silenciados. Vacío = todas las categorías activas. */
    val mutedNotifs: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.MUTED_NOTIFS] ?: emptySet()
    }

    /** Activa/silencia una categoría de notificación por su id. */
    suspend fun setNotifEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.MUTED_NOTIFS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) cur.remove(id) else cur.add(id)
            prefs[Keys.MUTED_NOTIFS] = cur
        }
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
