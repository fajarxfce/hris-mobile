package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkInventorySource
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkPrivateIdentitySource
import dev.fajar.starter.collaboration.data.mappers.toCredentialInventory
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSdkPossessionRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Coordinate existing-only resources and map private-account identity; no SDK client or policy. */
class StoredCollaborationSdkPossessionRepository(
    private val inventories: CollaborationSdkInventorySource,
    private val identities: CollaborationSdkPrivateIdentitySource,
    private val credentialFactory: (String) -> AtomicCredentialStore,
    private val server: IdentityServer,
    private val endpoint: CollaborationSdkEndpoint,
) : CollaborationSdkPossessionRepository {
    override suspend fun readPrivateIdentity(owner: CollaborationOwner, deviceId: String) =
        safeStorageCall {
            val scope = collaborationSdkScope(owner, deviceId, server, endpoint)
            val directory = inventories.inspect(scope.storeId) ?: return@safeStorageCall null
            var credentials: AtomicCredentialStore? = null
            var failure: Throwable? = null
            try {
                require(directory.storeId == scope.storeId)
                if (!directory.cryptoDatabasePresent) return@safeStorageCall null
                val store = credentialFactory(scope.credentialRecordName)
                credentials = store
                check(store.persistent) {
                    "Private account reading requires protected persistent storage."
                }
                val codec = CollaborationSdkRecordCodec(scope)
                val record = store.read()?.let(codec::decode) ?: return@safeStorageCall null
                currentCoroutineContext().ensureActive()
                val identity =
                    identities
                        .read(directory.databasePath, record.passphrase)
                        .toLocalDeviceIdentity()
                currentCoroutineContext().ensureActive()
                CollaborationSdkPossession(
                    CollaborationSdkInventory(
                        owner.setupBinding(),
                        deviceId,
                        true,
                        directory.stateDatabasePresent,
                        directory.cryptoDatabasePresent,
                        record.toCredentialInventory(codec),
                    ),
                    identity,
                )
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                val credentialClose = runCatching { credentials?.close() }.exceptionOrNull()
                val directoryClose = runCatching { directory.close() }.exceptionOrNull()
                val closing = credentialClose ?: directoryClose
                if (credentialClose != null && directoryClose != null)
                    credentialClose.addSuppressed(directoryClose)
                if (closing != null) {
                    val original = failure
                    if (original != null) original.addSuppressed(closing) else throw closing
                }
            }
        }
}
