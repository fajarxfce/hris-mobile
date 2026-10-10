package dev.fajar.starter.securestorage

import com.github.javakeyring.Keyring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OS credential store: Windows Credential Manager, macOS Keychain, or a Linux keyring. */
class DesktopCredentialStore(
    private val namespace: String,
    private val recordName: String = "session",
) : CredentialStore {
    init {
        require(recordName.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
    }

    override val persistent = true
    private val client = lazy { Keyring.create() }
    private val access = Mutex()

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            access.withLock { client.value.getPassword(namespace, recordName) }
        }

    override suspend fun write(value: String?) =
        withContext(Dispatchers.IO) {
            access.withLock {
                if (value == null) {
                    if (client.value.getPassword(namespace, recordName) != null)
                        client.value.deletePassword(namespace, recordName)
                } else client.value.setPassword(namespace, recordName, value)
            }
        }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }
}
