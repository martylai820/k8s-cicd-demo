package com.martylai.smbserver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "server_config")

/**
 * Persists and retrieves ServerConfig using Jetpack DataStore.
 */
class ServerConfigRepository(private val context: Context) {

    private object Keys {
        val PORT        = intPreferencesKey("port")
        val SHARE_NAME  = stringPreferencesKey("share_name")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val SHARE_PATH  = stringPreferencesKey("share_path")
        val REQUIRE_AUTH = booleanPreferencesKey("require_auth")
        val USERNAME    = stringPreferencesKey("username")
        val PASSWORD    = stringPreferencesKey("password")
        val READ_ONLY   = booleanPreferencesKey("read_only")
    }

    val config: Flow<ServerConfig> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences())
            else throw e
        }
        .map { prefs ->
            ServerConfig(
                port        = prefs[Keys.PORT]        ?: ServerConfig.DEFAULT.port,
                shareName   = prefs[Keys.SHARE_NAME]  ?: ServerConfig.DEFAULT.shareName,
                serverName  = prefs[Keys.SERVER_NAME] ?: ServerConfig.DEFAULT.serverName,
                sharePath   = prefs[Keys.SHARE_PATH]  ?: ServerConfig.DEFAULT.sharePath,
                requireAuth = prefs[Keys.REQUIRE_AUTH] ?: ServerConfig.DEFAULT.requireAuth,
                username    = prefs[Keys.USERNAME]    ?: ServerConfig.DEFAULT.username,
                password    = prefs[Keys.PASSWORD]    ?: ServerConfig.DEFAULT.password,
                readOnly    = prefs[Keys.READ_ONLY]   ?: ServerConfig.DEFAULT.readOnly
            )
        }

    suspend fun save(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PORT]         = config.port
            prefs[Keys.SHARE_NAME]   = config.shareName
            prefs[Keys.SERVER_NAME]  = config.serverName
            prefs[Keys.SHARE_PATH]   = config.sharePath
            prefs[Keys.REQUIRE_AUTH] = config.requireAuth
            prefs[Keys.USERNAME]     = config.username
            prefs[Keys.PASSWORD]     = config.password
            prefs[Keys.READ_ONLY]    = config.readOnly
        }
    }
}
