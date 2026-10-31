package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.RequestConfig
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder

/**
 * Raw SDK construction. Its caller owns admission, protected credentials and the directory lease.
 */
class AndroidCollaborationSdkClientSource {
    suspend fun create(
        directory: CollaborationSdkStoreLease,
        passphrase: String,
        initialSession: Session,
        delegate: ClientSessionDelegate,
    ): Client {
        require(initialSession.slidingSyncVersion == SlidingSyncVersion.NONE)
        require(initialSession.oauthData == null)
        currentCoroutineContext().ensureActive()
        return build(directory, passphrase, initialSession.homeserverUrl, delegate)
    }

    suspend fun restorePaused(client: Client, initialSession: Session) {
        require(initialSession.slidingSyncVersion == SlidingSyncVersion.NONE)
        require(initialSession.oauthData == null)
        // The repository already owns this client before any restore/cancellation can occur.
        client.enableAllSendQueues(false)
        client.restoreSession(initialSession)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun build(
        directory: CollaborationSdkStoreLease,
        passphrase: String,
        homeserver: String,
        delegate: ClientSessionDelegate,
    ): Client =
        SqliteStoreBuilder(directory.databasePath, directory.cachePath).use { initial ->
            initial.passphrase(passphrase).use { encrypted ->
                configuredBuilder(encrypted, homeserver, delegate).use { it.build() }
            }
        }

    private fun configuredBuilder(
        store: SqliteStoreBuilder,
        homeserver: String,
        delegate: ClientSessionDelegate,
    ): ClientBuilder {
        var current = ClientBuilder()
        fun replace(transform: (ClientBuilder) -> ClientBuilder) {
            val previous = current
            current = transform(previous)
            previous.close()
        }
        try {
            replace { it.homeserverUrl(homeserver) }
            replace { it.disableWellKnownLookup(true) }
            replace { it.slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE) }
            replace { it.autoEnableBackups(false) }
            replace { it.autoEnableCrossSigning(false) }
            replace { it.enableAutomaticBackPagination(false) }
            replace { it.requestConfig(RequestConfig(0uL, 15_000uL, 8uL, 0uL)) }
            replace { it.sqliteStore(store) }
            replace { it.setSessionDelegate(delegate) }
            return current
        } catch (failure: Throwable) {
            runCatching { current.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}
