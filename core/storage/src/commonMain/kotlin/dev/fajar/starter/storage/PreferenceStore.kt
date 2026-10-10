package dev.fajar.starter.storage

/** Raw preference I/O. This is already the datasource contract. */
interface PreferenceStore {
    suspend fun readBoolean(key: String): Boolean?

    suspend fun writeBoolean(key: String, value: Boolean): Boolean
}
