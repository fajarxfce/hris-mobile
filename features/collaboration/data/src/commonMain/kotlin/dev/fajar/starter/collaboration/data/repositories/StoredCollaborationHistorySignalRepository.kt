package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistorySignal
import dev.fajar.starter.collaboration.data.history.spaceHistoryScope
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistorySignalRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class StoredCollaborationHistorySignalRepository(private val replicas: ReplicaStore) :
    CollaborationHistorySignalRepository {
    override fun observe(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas.observePublishedCheckpoint(
                            spaceHistoryScope(access),
                            CollaborationHistorySignal.PARTITION,
                        )
                    )
                },
                mapValue = { raw ->
                    raw?.let {
                        CollaborationHistorySignal.generation(spaceHistoryScope(access), it)
                    }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
}
