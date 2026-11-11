package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationIncomingHistoryLocatorRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

/** Reads existing published index metadata; no second catalog, content key, or native lifetime. */
@Single
class StoredCollaborationIncomingHistoryLocatorRepository(private val replicas: ReplicaStore) :
    CollaborationIncomingHistoryLocatorRepository {
    override suspend fun read(access: CollaborationLocalAccess, after: String?, limit: Int) =
        safeStorageCall {
            val scope = historyScope(access)
            val prefix = COLLABORATION_HISTORY_PREFIX + access.companyId + "/"
            val mapper = CollaborationIncomingHistoryLocatorPageMapper(scope, prefix, after, limit)
            mapper.map(
                replicas.observePublishedCheckpoints(scope, prefix, after, limit + 1).first()
            )
        }

    override suspend fun read(access: CollaborationSpaceLocalAccess, after: String?, limit: Int) =
        safeStorageCall {
            val scope = spaceHistoryScope(access)
            val prefix = COLLABORATION_HISTORY_PREFIX + "space/v1/" + access.spaceId + "/"
            val mapper = CollaborationIncomingHistoryLocatorPageMapper(scope, prefix, after, limit)
            mapper.map(
                replicas.observePublishedCheckpoints(scope, prefix, after, limit + 1).first()
            )
        }
}
