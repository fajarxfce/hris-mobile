package dev.fajar.starter.storage

import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopPreferenceStore : PreferenceStore {
    private val preferences = Preferences.userRoot().node("dev/fajar/fluent-starter")

    override suspend fun readBoolean(key: String): Boolean? =
        withContext(Dispatchers.IO) { preferences.get(key, null)?.toBooleanStrict() }

    override suspend fun writeBoolean(key: String, value: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            preferences.putBoolean(key, value)
            preferences.flush()
            true
        }
}
