package dev.fajar.starter.securestorage

import com.github.javakeyring.Keyring
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OS credential store: Windows Credential Manager, macOS Keychain, or a Linux keyring. */
class DesktopCredentialStore(
    private val namespace: String,
    private val recordName: String = "session",
) : AtomicCredentialStore {
    init {
        require(namespace.length in 1..256 && namespace.none { it.isISOControl() })
        require(recordName.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
    }

    override val persistent = true
    private val client = lazy { Keyring.create() }
    private val access = Mutex()
    private val lockFile =
        File(
            System.getProperty("user.home"),
            ".compose-fluent/credential-locks/" +
                MessageDigest.getInstance("SHA-256")
                    .digest("${namespace.length}:$namespace:$recordName".toByteArray())
                    .joinToString("") { "%02x".format(it) } +
                ".lock",
        )

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                withCredentialRecordLock(lockFile) {
                    client.value.getPassword(namespace, recordName)
                }
            }
        }

    override suspend fun write(value: String?) =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                require(value == null || value.encodeToByteArray().size <= 65_536)
                withCredentialRecordLock(lockFile) {
                    if (value == null) {
                        if (client.value.getPassword(namespace, recordName) != null)
                            client.value.deletePassword(namespace, recordName)
                    } else client.value.setPassword(namespace, recordName, value)
                }
            }
        }

    override suspend fun writeIfAbsent(value: String): String =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                require(value.encodeToByteArray().size <= 65_536)
                withCredentialRecordLock(lockFile) {
                    client.value.getPassword(namespace, recordName)
                        ?: value.also { client.value.setPassword(namespace, recordName, it) }
                }
            }
        }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }
}
