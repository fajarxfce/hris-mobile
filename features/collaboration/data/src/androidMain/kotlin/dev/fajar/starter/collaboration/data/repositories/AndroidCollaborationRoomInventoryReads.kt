package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationRoomInventorySource
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.toSpaceRoomInventory
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIncomingRoomId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIncomingSource
import dev.fajar.starter.common.result.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Each finite local read is a child of the installed native parent, independently of its waiter.
 */
internal class AndroidCollaborationRoomInventoryReads(
    private val originalSource: CollaborationSpaceIncomingSource,
    private val source: CollaborationRoomInventorySource,
    private val children: SdkChildResources,
    private val failure: () -> Failure?,
) {
    suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        afterRoomId: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceRoomInventoryPage> {
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (
            limit !in 1..64 ||
                !validCollaborationSpaceIncomingSource(originalSource) ||
                (afterRoomId != null && !validCollaborationSpaceIncomingRoomId(afterRoomId))
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid chat request."))
        val captured = AtomicReference<CollaborationSpaceRoomInventoryPage?>()
        val acquired =
            children.acquire(
                captured,
                initialize@{ target ->
                    failure()?.let {
                        return@initialize AppResult.Failed(it)
                    }
                    val read =
                        safeAndroidCollaborationCall(AndroidCollaborationOperation.RoomInventory) {
                            source
                                .read(afterRoomId, limit)
                                .toSpaceRoomInventory(runtime, originalSource, afterRoomId, limit)
                        }
                    currentCoroutineContext().ensureActive()
                    failure()?.let {
                        return@initialize AppResult.Failed(
                            collaborationSdkAcquisitionFailure(read, it)
                        )
                    }
                    when (read) {
                        is AppResult.Failed -> read
                        is AppResult.Success -> {
                            target.set(read.value)
                            AppResult.Success(Unit)
                        }
                    }
                },
                { it.set(null) },
            )
        if (acquired is AppResult.Failed) {
            currentCoroutineContext().ensureActive()
            return failure()?.let {
                AppResult.Failed(collaborationSdkAcquisitionFailure(acquired, it))
            } ?: acquired
        }
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                currentCoroutineContext().ensureActive()
                val page = captured.getAndSet(null)
                if (page == null)
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Chat is unavailable.",
                            code = "client.collaboration_inventory_interrupted",
                        )
                    )
                else AppResult.Success(page)
            } finally {
                disposed = child.close()
            }
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
        }
        if (disposed is AppResult.Failed)
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, disposed.failure))
        return result
    }
}
