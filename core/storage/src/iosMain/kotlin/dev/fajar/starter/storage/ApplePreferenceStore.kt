package dev.fajar.starter.storage

import platform.Foundation.NSUserDefaults

class ApplePreferenceStore : PreferenceStore {
    private val preferences = NSUserDefaults.standardUserDefaults

    override suspend fun readBoolean(key: String): Boolean? =
        if (preferences.objectForKey(key) == null) null else preferences.boolForKey(key)

    override suspend fun writeBoolean(key: String, value: Boolean): Boolean {
        preferences.setBool(value, forKey = key)
        return true
    }
}
