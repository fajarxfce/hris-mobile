package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.ExistingCryptoIdentity
import org.matrix.rustcomponents.sdk.readExistingCryptoIdentity

/**
 * Synchronous native I/O completes before cancellation can release the caller's directory lease.
 */
class AndroidCollaborationSdkPrivateIdentitySource(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nativeRead: (String, String) -> ExistingCryptoIdentity =
        ::readExistingCryptoIdentity,
) : CollaborationSdkPrivateIdentitySource {
    override suspend fun read(
        databasePath: String,
        passphrase: String,
    ): CollaborationSdkIdentityData =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            val value = nativeRead(databasePath, passphrase)
            currentCoroutineContext().ensureActive()
            CollaborationSdkIdentityData(
                value.userId,
                value.deviceId,
                value.ed25519Key,
                value.curve25519Key,
            )
        }
}
