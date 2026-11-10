@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingWake
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIncomingWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.WorkWakeRecord
import dev.fajar.starter.database.WorkWakeStore
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

/** Same physical wake-table capacity for this lane's atomic and standalone producers. */
internal const val INCOMING_WAKE_MAX_KEYS = 32

@Single
class StoredCollaborationSpaceIncomingWakeRepository(private val store: WorkWakeStore) :
    CollaborationSpaceIncomingWakeRepository {
    override suspend fun pending() = safeStorageCall {
        store.get(CollaborationSpaceIncomingWake.WORK_KEY)?.let { raw ->
            raw.incomingWake().takeIf { raw.pending }
        }
    }

    override suspend fun request(): AppResult<CollaborationSpaceIncomingWake> {
        val retained = safeStorageCall {
            val generation = Uuid.random().toString()
            store
                .request(
                    CollaborationSpaceIncomingWake.WORK_KEY,
                    generation,
                    INCOMING_WAKE_MAX_KEYS,
                )
                ?.let { raw ->
                    check(raw.generation == generation && raw.pending)
                    raw.incomingWake()
                }
        }
        if (retained is AppResult.Failed) return retained
        return (retained as AppResult.Success).value?.let { AppResult.Success(it) }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Conflict,
                    "Could not retain incoming chat work.",
                    code = "client.collaboration_incoming_capacity",
                )
            )
    }

    override suspend fun acknowledge(wake: CollaborationSpaceIncomingWake) = safeStorageCall {
        require(validCollaborationMessageId(wake.generation))
        store.acknowledge(CollaborationSpaceIncomingWake.WORK_KEY, wake.generation)
    }

    private fun WorkWakeRecord.incomingWake(): CollaborationSpaceIncomingWake {
        check(
            key == CollaborationSpaceIncomingWake.WORK_KEY &&
                validCollaborationMessageId(generation)
        )
        return CollaborationSpaceIncomingWake(generation)
    }
}
