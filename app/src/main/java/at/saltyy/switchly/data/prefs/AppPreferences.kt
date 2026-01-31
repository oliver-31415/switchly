package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Small app-level user preferences.
 *
 * Note: Most of Switchly's existing stores are still SharedPreferences-based (and that's totally OK).
 * This DataStore is meant for *lightweight* flags that shouldn't be scattered across activities.
 */
private val Context.appPreferencesDataStore by preferencesDataStore(name = "switchly_datastore")

class AppPreferences(private val context: Context) {

    private val ds = context.appPreferencesDataStore

    val lastUpdatePromptedVersionCode: Flow<Long> = ds.data
        .map { prefs -> prefs[KEY_LAST_UPDATE_PROMPTED_VERSION] ?: -1L }

    suspend fun setLastUpdatePromptedVersionCode(versionCode: Long) {
        ds.edit { it[KEY_LAST_UPDATE_PROMPTED_VERSION] = versionCode }
    }

    /**
     * If true, the app should ask for POST_NOTIFICATIONS when needed (Android 13+).
     * We keep this as a user-respectful "ask once" switch.
     */
    val notificationsPermissionAsked: Flow<Boolean> = ds.data
        .map { prefs -> prefs[KEY_NOTIF_PERMISSION_ASKED] ?: false }

    suspend fun setNotificationsPermissionAsked(asked: Boolean) {
        ds.edit { it[KEY_NOTIF_PERMISSION_ASKED] = asked }
    }

    companion object {
        private val KEY_LAST_UPDATE_PROMPTED_VERSION = longPreferencesKey("last_update_prompted_version_code")
        private val KEY_NOTIF_PERMISSION_ASKED = booleanPreferencesKey("notif_permission_asked")
    }
}
