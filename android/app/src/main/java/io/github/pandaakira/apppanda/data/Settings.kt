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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Un PC gestionable. Cada perfil apunta a un backend apppanda distinto
 *  (típicamente otra IP de la misma tailnet de Tailscale). El usuario cambia
 *  de perfil para controlar otra máquina sin reconfigurar nada. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

data class BackendConfig(
    val baseUrl: String = "",
    val token: String = "",
) {
    /** El token es opcional cuando el backend acepta auth por identidad
     *  Tailscale (chequea peer IP + UserProfile.LoginName). */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

/** Tema visual seleccionado. [specJson] es un `ThemeDef` serializado (colores
 *  + fuente + iconos + formas); vacío = usar el tema incluido (cyberpunk). Se
 *  guarda completo para aplicarlo al instante al arrancar, sin backend. */
data class SelectedTheme(
    val name: String = "",
    val specJson: String = "",
) {
    val isCustom: Boolean get() = specJson.isNotBlank()
}

class Settings(private val context: Context) {

    private val pjson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val profileListSerializer = ListSerializer(Profile.serializer())

    private object Keys {
        // Backend legacy (pre-perfiles). Se conserva solo para migrar 1→N.
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
        // Multi-perfil: lista de PCs serializada + id del activo.
        val PROFILES = stringPreferencesKey("profiles_json")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        // Categorías de notificación silenciadas (ids de NotifCategory).
        // Guardamos las APAGADAS para que cualquier categoría nueva que se
        // agregue después aparezca encendida por defecto.
        val MUTED_NOTIFS = stringSetPreferencesKey("muted_notifs")
        // Tema visual seleccionado: nombre para mostrar + spec completa.
        val THEME_NAME = stringPreferencesKey("theme_name")
        val THEME_SPEC = stringPreferencesKey("theme_spec")
    }

    private fun decodeProfiles(raw: String?): List<Profile> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { pjson.decodeFromString(profileListSerializer, raw) }.getOrDefault(emptyList())

    private fun encodeProfiles(list: List<Profile>): String =
        pjson.encodeToString(profileListSerializer, list)

    /** Resuelve cuál perfil está activo dado un set de prefs. Si el id
     *  guardado ya no existe (se borró), cae al primero de la lista. */
    private fun resolveActive(profiles: List<Profile>, storedId: String?): Profile? =
        profiles.firstOrNull { it.id == storedId } ?: profiles.firstOrNull()

    // ─── Perfiles ────────────────────────────────────────────────────────

    val profiles: Flow<List<Profile>> = context.dataStore.data.map { prefs ->
        decodeProfiles(prefs[Keys.PROFILES])
    }

    val activeProfileId: Flow<String> = context.dataStore.data.map { prefs ->
        resolveActive(decodeProfiles(prefs[Keys.PROFILES]), prefs[Keys.ACTIVE_PROFILE])?.id.orEmpty()
    }

    val activeProfile: Flow<Profile?> = context.dataStore.data.map { prefs ->
        resolveActive(decodeProfiles(prefs[Keys.PROFILES]), prefs[Keys.ACTIVE_PROFILE])
    }

    /** Config del backend ACTIVO. Deriva del perfil activo; si todavía no hay
     *  perfiles (instalación vieja aún sin migrar), cae al backend legacy. Así
     *  todo el resto de la app sigue consumiendo `config` sin cambios. */
    val config: Flow<BackendConfig> = context.dataStore.data.map { prefs ->
        val active = resolveActive(decodeProfiles(prefs[Keys.PROFILES]), prefs[Keys.ACTIVE_PROFILE])
        if (active != null) {
            BackendConfig(active.baseUrl, active.token)
        } else {
            BackendConfig(prefs[Keys.BASE_URL].orEmpty(), prefs[Keys.TOKEN].orEmpty())
        }
    }

    /** Migración 1→N: si hay un backend legacy guardado pero todavía no
     *  existen perfiles, crea el primero a partir de él y lo activa.
     *  Idempotente: no hace nada si ya hay perfiles. */
    suspend fun migrateLegacyIfNeeded() {
        context.dataStore.edit { prefs ->
            if (decodeProfiles(prefs[Keys.PROFILES]).isNotEmpty()) return@edit
            val legacyUrl = prefs[Keys.BASE_URL].orEmpty()
            if (legacyUrl.isBlank()) return@edit
            val p = Profile(Profile.newId(), "Mi PC", legacyUrl, prefs[Keys.TOKEN].orEmpty())
            prefs[Keys.PROFILES] = encodeProfiles(listOf(p))
            prefs[Keys.ACTIVE_PROFILE] = p.id
        }
    }

    /** Agrega o reemplaza (por id) un perfil. Si no había un activo válido,
     *  deja a este como activo. */
    suspend fun upsertProfile(profile: Profile) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[Keys.PROFILES]).toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            if (idx >= 0) list[idx] = profile else list.add(profile)
            prefs[Keys.PROFILES] = encodeProfiles(list)
            if (list.none { it.id == prefs[Keys.ACTIVE_PROFILE] }) {
                prefs[Keys.ACTIVE_PROFILE] = profile.id
            }
        }
    }

    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[Keys.PROFILES]).filterNot { it.id == id }
            prefs[Keys.PROFILES] = encodeProfiles(list)
            if (prefs[Keys.ACTIVE_PROFILE] == id) {
                val next = list.firstOrNull()?.id
                if (next != null) prefs[Keys.ACTIVE_PROFILE] = next else prefs.remove(Keys.ACTIVE_PROFILE)
            }
        }
    }

    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit { prefs ->
            if (decodeProfiles(prefs[Keys.PROFILES]).any { it.id == id }) {
                prefs[Keys.ACTIVE_PROFILE] = id
            }
        }
    }

    // ─── Push / notifs / tema ──────────────────────────────────────────────

    val pushEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.PUSH_ENABLED] ?: false
    }

    /** Ids de NotifCategory silenciados. Vacío = todas las categorías activas. */
    val mutedNotifs: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.MUTED_NOTIFS] ?: emptySet()
    }

    /** Tema visual seleccionado. Sin nada guardado = tema incluido. */
    val selectedTheme: Flow<SelectedTheme> = context.dataStore.data.map { prefs ->
        SelectedTheme(
            name = prefs[Keys.THEME_NAME].orEmpty(),
            specJson = prefs[Keys.THEME_SPEC].orEmpty(),
        )
    }

    /** Aplica un tema: guarda nombre + spec completa (ThemeDef JSON). */
    suspend fun saveTheme(name: String, specJson: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_NAME] = name
            prefs[Keys.THEME_SPEC] = specJson
        }
    }

    /** Vuelve al tema incluido (cyberpunk). */
    suspend fun clearTheme() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.THEME_NAME)
            prefs.remove(Keys.THEME_SPEC)
        }
    }

    /** Activa/silencia una categoría de notificación por su id. */
    suspend fun setNotifEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.MUTED_NOTIFS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) cur.remove(id) else cur.add(id)
            prefs[Keys.MUTED_NOTIFS] = cur
        }
    }

    /** Compat: actualiza el backend del perfil ACTIVO (conservando su nombre),
     *  o crea uno llamado "Mi PC" si todavía no hay ninguno. Lo usan el
     *  onboarding y cualquier flujo legacy que guarde host+token sueltos. */
    suspend fun save(baseUrl: String, token: String) {
        val url = baseUrl.trim().trimEnd('/')
        val tok = token.trim()
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[Keys.PROFILES]).toMutableList()
            val active = resolveActive(list, prefs[Keys.ACTIVE_PROFILE])
            if (active != null) {
                val idx = list.indexOfFirst { it.id == active.id }
                list[idx] = active.copy(baseUrl = url, token = tok)
                prefs[Keys.ACTIVE_PROFILE] = active.id
            } else {
                val p = Profile(Profile.newId(), "Mi PC", url, tok)
                list.add(p)
                prefs[Keys.ACTIVE_PROFILE] = p.id
            }
            prefs[Keys.PROFILES] = encodeProfiles(list)
        }
    }

    suspend fun setPushEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PUSH_ENABLED] = enabled }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
