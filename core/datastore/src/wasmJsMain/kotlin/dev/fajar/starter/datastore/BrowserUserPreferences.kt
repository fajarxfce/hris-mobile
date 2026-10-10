package dev.fajar.starter.datastore

import androidx.datastore.core.CorruptionException
import dev.fajar.starter.datastore.proto.UserPreferences
import kotlinx.browser.localStorage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

fun createUserPreferences(namespace: String): UserPreferencesStore =
    BrowserUserPreferencesStore(namespace)

/** DataStore 1.2's Wasm factory is unimplemented. This adapter keeps the same protobuf contract. */
private class BrowserUserPreferencesStore(private val namespace: String) : UserPreferencesStore {
    private val key = "$namespace.user_preferences.pb"
    private val mutex = Mutex()
    private var closed = false
    private val changes =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }

    override val data = changes.takeWhile { !closed }.map { mutex.withLock { readSnapshot() } }

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        mutex.withLock {
            check(!closed) { "The preference store is closed." }
            val value = transform(readSnapshot())
            currentCoroutineContext().ensureActive()
            localStorage.setItem(key, UserPreferences.ADAPTER.encode(value).toByteString().base64())
            changes.tryEmit(Unit)
        }
    }

    /** Storage decoding and one-time legacy schema migration; no application policy. */
    private suspend fun readSnapshot(): UserPreferences {
        val encoded = localStorage.getItem(key)
        val current =
            if (encoded == null) UserPreferences()
            else {
                val bytes =
                    encoded.decodeBase64()
                        ?: throw CorruptionException("Invalid preference encoding.")
                UserPreferencesSerializer.readFrom(Buffer().write(bytes))
            }
        if (current.legacy_migrated) return current
        val migrated =
            current.copy(
                onboarding_completed =
                    current.onboarding_completed ||
                        (namespace == "fluent-starter.prod" &&
                            localStorage.getItem("fluent-starter.onboarding.complete") == "true"),
                legacy_migrated = true,
            )
        currentCoroutineContext().ensureActive()
        localStorage.setItem(key, UserPreferences.ADAPTER.encode(migrated).toByteString().base64())
        return migrated
    }

    override fun close() {
        closed = true
        changes.tryEmit(Unit)
    }
}
