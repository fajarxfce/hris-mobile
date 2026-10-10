package dev.fajar.starter.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import dev.fajar.starter.datastore.proto.UserPreferences
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

object UserPreferencesSerializer : OkioSerializer<UserPreferences> {
    override val defaultValue = UserPreferences()

    override suspend fun readFrom(source: BufferedSource): UserPreferences {
        // Read I/O is outside the decode boundary: an I/O failure is not corrupt data.
        val bytes = source.readByteArray()
        return try {
            UserPreferences.ADAPTER.decode(bytes)
        } catch (exception: IOException) {
            throw CorruptionException("Invalid user preferences protobuf.", exception)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Invalid user preferences protobuf.", exception)
        }
    }

    override suspend fun writeTo(t: UserPreferences, sink: BufferedSink) {
        UserPreferences.ADAPTER.encode(sink, t)
    }
}
