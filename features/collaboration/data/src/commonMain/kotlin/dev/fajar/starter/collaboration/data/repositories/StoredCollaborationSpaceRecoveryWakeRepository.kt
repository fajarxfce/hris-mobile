@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.WorkWakeRecord
import dev.fajar.starter.database.WorkWakeStore
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class StoredCollaborationSpaceRecoveryWakeRepository(private val store: WorkWakeStore) :
    CollaborationSpaceRecoveryWakeRepository {
    override suspend fun pending() = safeStorageCall {
        store.get(CollaborationSpaceRecoveryWake.WORK_KEY)?.let { raw ->
            val wake = raw.toWake()
            wake.takeIf { raw.pending }
        }
    }

    override suspend fun request(): AppResult<CollaborationSpaceRecoveryWake> {
        val retained = safeStorageCall {
            val generation = Uuid.random().toString()
            store.request(CollaborationSpaceRecoveryWake.WORK_KEY, generation, MAX_KEYS)?.let { raw
                ->
                check(raw.generation == generation && raw.pending)
                raw.toWake()
            }
        }
        if (retained is AppResult.Failed) return retained
        return (retained as AppResult.Success).value?.let { AppResult.Success(it) }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Conflict,
                    "Could not retain chat recovery.",
                    code = "client.collaboration_space_recovery_capacity",
                )
            )
    }

    override suspend fun acknowledge(wake: CollaborationSpaceRecoveryWake) = safeStorageCall {
        check(Uuid.parse(wake.generation).toString() == wake.generation)
        store.acknowledge(CollaborationSpaceRecoveryWake.WORK_KEY, wake.generation)
    }

    private fun WorkWakeRecord.toWake(): CollaborationSpaceRecoveryWake {
        check(key == CollaborationSpaceRecoveryWake.WORK_KEY)
        check(Uuid.parse(generation).toString() == generation)
        return CollaborationSpaceRecoveryWake(generation)
    }

    private companion object {
        const val MAX_KEYS = 32
    }
}
