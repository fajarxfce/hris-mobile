package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReopenCollaborationHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class HistoryReopeningFixture {
    val delivery = CollaborationDeliveryFixture()
    val local = delivery.base
    val events = mutableListOf<String>()
    var prepared: AppResult<Boolean> = AppResult.Success(true)
    var afterPrepare: suspend () -> Unit = {}
    var afterRestore: suspend () -> Unit = {}
    var restored: AppResult<CollaborationRuntimeLease?>? = null
    var leaseOwner = delivery.owner
    var leaseDevice = delivery.device.deviceId
    var closes = 0
    val failures = MutableStateFlow<Failure?>(null)
    val lease =
        object : CollaborationRuntimeLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val installedOperationId = delivery.device.bootstrapOperationId
            override val credentialFailures = failures

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No implicit write retry")

            override suspend fun close(): AppResult<Unit> {
                assertTrue(currentCoroutineContext().isActive)
                closes++
                events += "close"
                return AppResult.Success(Unit)
            }
        }

    init {
        delivery.control.beforeRemote = { events += it }
    }

    private val runtimes =
        object : CollaborationRuntimeRepository by delivery.runtimes {
            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> {
                assertEquals(delivery.owner, owner)
                assertEquals(delivery.device.deviceId, deviceId)
                assertEquals(delivery.device.roomId, matrixRoomId)
                assertEquals("\$anchor", anchorEventId)
                events += "prepare"
                afterPrepare()
                return prepared
            }

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> {
                assertEquals(delivery.owner, owner)
                assertEquals(delivery.device.deviceId, deviceId)
                events += "restore"
                afterRestore()
                return restored ?: AppResult.Success(lease)
            }
        }
    val useCase =
        ReopenCollaborationHistory(
            runtimes,
            delivery.lists,
            delivery.control.remote,
            local.setup.remote,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun reopen() =
        useCase("selection", delivery.device.deviceId, delivery.conversation, "\$anchor")

    fun result(value: CollaborationHistoryRuntimePreparation) =
        AppResult.Success(CollaborationRead(delivery.owner, value))
}
