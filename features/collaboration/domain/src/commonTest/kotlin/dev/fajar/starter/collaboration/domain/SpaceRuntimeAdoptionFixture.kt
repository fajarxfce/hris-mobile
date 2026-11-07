package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

internal class SpaceRuntimeAdoptionFixture {
    val transfer = SpaceRuntimeTransferFixture()
    val setup = transfer.setup
    val operation = "10000000-0000-4000-8000-000000000099"
    val request =
        CollaborationSpaceBootstrap(
            operation,
            setup.registered.command.binding,
            CollaborationNativePlatform.ANDROID,
            setup.build.number,
            setup.registration.receipt.id,
            1,
            null,
            setup.registration.provider,
            setup.registration.sdkIdentity.matrixDeviceId,
        )
    val gateway = setup.gateway(request)
    val record =
        CollaborationSpaceSetupRecord(
            CollaborationSpaceSetupCommand(
                setup.registrationId,
                request.platform,
                request.clientBuild,
                setup.native.setup.now,
                request,
            ),
            2,
            1,
            CollaborationSpaceSetupOutcome.Bootstrapped(gateway),
        )
    var installs = 0
    var publications = 0
    var closes = 0
    var spaceCloses = 0
    var open = false
    var leaseOwner = setup.owner
    var leaseDevice = request.deviceId
    var leaseOperation = operation
    val credentialFailures = MutableStateFlow<Failure?>(null)
    var beforePublish: () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var beforeClose: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var beforeSpaceClose: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    val lease =
        object : CollaborationSpaceRuntimeLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val installedOperationId
                get() = leaseOperation

            override val credentialFailures = this@SpaceRuntimeAdoptionFixture.credentialFailures

            override fun publish(): AppResult<Unit> {
                publications++
                val result = beforePublish()
                if (result is AppResult.Success) open = true
                return result
            }

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No implicit credential retry")

            override suspend fun close(): AppResult<Unit> {
                closes++
                open = false
                return beforeClose()
            }
        }
    var beforeInstall: suspend () -> AppResult<CollaborationSpaceRuntimeLease> = {
        AppResult.Success(lease)
    }
    var beforeIsOpen: suspend () -> Unit = {}
    val runtimes =
        object : CollaborationSpaceRuntimeRepository {
            override suspend fun inspectOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueSnapshot> =
                error("No implicit original queue read")

            override suspend fun isSpaceOpen(
                owner: CollaborationSpaceOwner,
                deviceId: String,
                operationId: String,
            ): AppResult<Boolean> {
                assertEquals(setup.owner, owner)
                assertEquals(request.deviceId, deviceId)
                assertEquals(operation, operationId)
                beforeIsOpen()
                return AppResult.Success(open)
            }

            override suspend fun installOriginal(
                owner: CollaborationSpaceOwner,
                original: CollaborationExistingRuntimeHandover,
                setup: CollaborationSpaceSetupRecord,
            ): AppResult<CollaborationSpaceRuntimeLease> {
                assertEquals(this@SpaceRuntimeAdoptionFixture.setup.owner, owner)
                assertEquals(record, setup)
                assertEquals(transfer.retained, original.intent)
                assertEquals(
                    this@SpaceRuntimeAdoptionFixture.setup.registered.command.legacy,
                    original.original,
                )
                installs++
                return beforeInstall()
            }

            override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> {
                assertEquals(setup.owner, owner)
                spaceCloses++
                open = false
                return beforeSpaceClose()
            }
        }

    init {
        transfer.retained =
            CollaborationRuntimeHandoverIntent(
                "20000000-0000-4000-8000-000000000099",
                setup.owner.spaceId,
                request.deviceId,
                0,
                setup.registration.sdkIdentity.fingerprint,
            )
        setup.records.value = AppResult.Success(record)
        setup.status =
            setup
                .activeStatus()
                .copy(
                    gateway =
                        CollaborationSpaceGatewayStatus(
                            gateway.sessionId,
                            gateway.version,
                            request.clientBuild,
                            Instant.fromEpochMilliseconds(gateway.accessExpiresAtEpochMillis),
                            Instant.fromEpochMilliseconds(gateway.expiresAtEpochMillis),
                            null,
                        )
                )
    }

    fun useCase() =
        AdoptCollaborationSpaceRuntime(
            setup.commands,
            setup.registration.commands,
            setup.registration.devices,
            transfer.runtime,
            runtimes,
            setup.base.directory,
            setup.base.sessions,
            setup.native.server,
            setup.build,
            setup.native.clock,
            setup.native.time,
        )

    suspend fun run() = useCase()(setup.owner, setup.registrationId, operation)
}
