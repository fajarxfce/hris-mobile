@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.WorkWakeRecord
import dev.fajar.starter.database.WorkWakeStore
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class StoredCollaborationRecoveryWakeRepository(private val store: WorkWakeStore) :
    CollaborationRecoveryWakeRepository {
    override suspend fun pending() = safeStorageCall {
        store.get(CollaborationRecoveryWake.WORK_KEY)?.let { raw ->
            val wake = raw.toWake()
            wake.takeIf { raw.pending }
        }
    }

    override suspend fun request(): AppResult<CollaborationRecoveryWake> {
        val retained = safeStorageCall {
            val generation = Uuid.random().toString()
            store.request(CollaborationRecoveryWake.WORK_KEY, generation, MAX_KEYS)?.let { raw ->
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
                    code = "client.collaboration_recovery_capacity",
                )
            )
    }

    override suspend fun acknowledge(wake: CollaborationRecoveryWake) = safeStorageCall {
        check(Uuid.parse(wake.generation).toString() == wake.generation)
        store.acknowledge(CollaborationRecoveryWake.WORK_KEY, wake.generation)
    }

    private fun WorkWakeRecord.toWake(): CollaborationRecoveryWake {
        check(key == CollaborationRecoveryWake.WORK_KEY)
        check(Uuid.parse(generation).toString() == generation)
        return CollaborationRecoveryWake(generation)
    }

    private companion object {
        const val MAX_KEYS = 32
    }
}
