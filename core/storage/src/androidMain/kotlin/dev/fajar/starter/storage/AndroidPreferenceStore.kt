package dev.fajar.starter.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPreferenceStore(context: Context) : PreferenceStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("fluent-starter", Context.MODE_PRIVATE)

    override suspend fun readBoolean(key: String): Boolean? =
        withContext(Dispatchers.IO) {
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        }

    override suspend fun writeBoolean(key: String, value: Boolean): Boolean =
        withContext(Dispatchers.IO) { preferences.edit().putBoolean(key, value).commit() }
}
