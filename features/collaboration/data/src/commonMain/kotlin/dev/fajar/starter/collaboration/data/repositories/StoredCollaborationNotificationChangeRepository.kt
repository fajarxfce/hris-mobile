package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.identityPartition
import dev.fajar.starter.collaboration.data.notifications.notificationSettingsPartition
import dev.fajar.starter.collaboration.data.readstate.readStatePartition
import dev.fajar.starter.collaboration.data.replica.listPartition
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationChangeRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

/** Metadata wakes a semantic revalidation; ciphertext is neither decoded nor returned. */
@Single
class StoredCollaborationNotificationChangeRepository(private val replicas: ReplicaStore) :
    CollaborationNotificationChangeRepository {
    override fun observe(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageFlow(
                flow {
                    val scope = historyScope(access)
                    val partitions =
                        listOf(
                            listPartition(access),
                            identityPartition(access),
                            historyPartition(access, conversationId),
                            notificationSettingsPartition(access, conversationId),
                            readStatePartition(access, conversationId),
                        )
                    emitAll(
                        combine(
                            partitions.map { partition ->
                                replicas
                                    .observePublishedCheckpoint(scope, partition)
                                    .distinctUntilChanged()
                                    .onCompletion { cause ->
                                        check(cause != null) { "Source observation completed" }
                                    }
                            }
                        ) {
                            Unit
                        }
                    )
                }
            )
            .flowOn(Dispatchers.Default)
            .buffer(0)
    // Do not distinct Unit output: every subsequent changed source must wake its owner.
}
