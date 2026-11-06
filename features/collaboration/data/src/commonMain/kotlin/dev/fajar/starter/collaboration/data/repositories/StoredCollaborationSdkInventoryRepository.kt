package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkInventorySource
import dev.fajar.starter.collaboration.data.mappers.toCredentialInventory
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSdkInventoryRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Map original protected-record and locked file facts. No SDK client or recovery policy. */
class StoredCollaborationSdkInventoryRepository(
    private val inventories: CollaborationSdkInventorySource,
    private val credentialFactory: (String) -> AtomicCredentialStore,
    private val server: IdentityServer,
    private val endpoint: CollaborationSdkEndpoint,
) : CollaborationSdkInventoryRepository {
    override suspend fun inspect(owner: CollaborationOwner, deviceId: String) = safeStorageCall {
        val scope = collaborationSdkScope(owner, deviceId, server, endpoint)
        val directory = inventories.inspect(scope.storeId)
        var credentials: AtomicCredentialStore? = null
        var failure: Throwable? = null
        try {
            require(directory == null || directory.storeId == scope.storeId)
            val store = credentialFactory(scope.credentialRecordName)
            credentials = store
            check(store.persistent) { "SDK inventory requires protected persistent storage." }
            val codec = CollaborationSdkRecordCodec(scope)
            val record = store.read()?.let(codec::decode)
            currentCoroutineContext().ensureActive()
            CollaborationSdkInventory(
                owner.setupBinding(),
                deviceId,
                directory != null,
                directory?.stateDatabasePresent == true,
                directory?.cryptoDatabasePresent == true,
                record?.toCredentialInventory(codec),
            )
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val credentialClose = runCatching { credentials?.close() }.exceptionOrNull()
            val directoryClose = runCatching { directory?.close() }.exceptionOrNull()
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
