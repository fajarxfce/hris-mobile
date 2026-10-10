package dev.fajar.starter.securestorage

import com.github.javakeyring.Keyring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OS credential store: Windows Credential Manager, macOS Keychain, or a Linux keyring. */
class DesktopCredentialStore(private val namespace: String) : CredentialStore {
    override val persistent = true
    private val client = lazy { Keyring.create() }
    private val access = Mutex()

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            access.withLock { client.value.getPassword(namespace, "session") }
        }

    override suspend fun write(value: String?) =
        withContext(Dispatchers.IO) {
            access.withLock {
                if (value == null) {
                    if (client.value.getPassword(namespace, "session") != null)
                        client.value.deletePassword(namespace, "session")
                } else client.value.setPassword(namespace, "session", value)
            }
        }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }
}
