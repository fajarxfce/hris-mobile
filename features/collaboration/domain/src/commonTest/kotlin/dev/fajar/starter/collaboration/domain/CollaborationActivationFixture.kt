package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ActivateCollaborationDevice
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class CollaborationActivationFixture(
    val command: CollaborationCommandFixture = CollaborationCommandFixture(),
    configureDevice: Boolean = true,
) {
    var supported = true
    var device: CollaborationDeviceSetup? = command.access.device
    var opened = false
    var inspectionHeld = false
    var inspectionCurrent = true
    var inspectionCloses = 0
    var openAtInspection = false
    var restores = 0
    var publications = 0
    var closes = 0
    var wake: CollaborationRecoveryWake? = null
    val scheduled = mutableListOf<String>()
    val installations = mutableListOf<CollaborationSetupCommandRecord>()
    var inventory =
        CollaborationSdkInventory(command.owner.setupBinding(), "device", false, false, false, null)
    var privateIdentity: CollaborationSdkPossession? = null
    var inspect: suspend () -> AppResult<CollaborationSdkInventory> = {
        AppResult.Success(inventory)
    }
    var possess: suspend () -> AppResult<CollaborationSdkPossession?> = {
        AppResult.Success(privateIdentity)
    }
    var schedule: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var onInstall: suspend () -> AppResult<CollaborationRuntimeLease> = { AppResult.Success(lease) }
    var onRestore: suspend () -> AppResult<CollaborationRuntimeLease?> = {
        AppResult.Success(lease)
    }
    val lease: CollaborationRuntimeLease
        get() = ownedLease

    private val ownedLease =
        object : CollaborationRuntimeLease {
            override val owner
                get() = command.owner

            override val deviceId = "device"
            override var installedOperationId = "original-bootstrap"
            override val credentialFailures = MutableStateFlow<Failure?>(null)

            override fun publish(): AppResult<Unit> {
                publications++
                opened = true
                return AppResult.Success(Unit)
            }

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No credential retry")

            override suspend fun close(): AppResult<Unit> {
                closes++
                return AppResult.Success(Unit)
            }
        }

    init {
        if (configureDevice) {
            command.access.onRead = { AppResult.Success(device) }
            command.response = {
                device = command.access.device
                AppResult.Success(CollaborationSetupReceipt("device", 1))
            }
            command.gatewayResponse = {
                AppResult.Success(
                    CollaborationGateway(
                        command.owner,
                        "device",
                        "head",
                        0,
                        "matrix-user",
                        "matrix-device",
                        "access",
                        "refresh",
                        1,
                        2,
                    )
                )
            }
        }
    }

    fun retainPrivateAccount() {
        inventory =
            inventory.copy(
                directoryPresent = true,
                stateDatabasePresent = true,
                cryptoDatabasePresent = true,
                credential =
                    CollaborationSdkCredentialInventory(
                        "matrix-user",
                        "matrix-device",
                        "original-bootstrap",
                        "original-bootstrap",
                        0,
                        true,
                        null,
                    ),
            )
        privateIdentity =
            CollaborationSdkPossession(
                inventory,
                CollaborationLocalDeviceIdentity("matrix-user", "matrix-device", "a".repeat(64)),
            )
    }

    val runtimes =
        object : CollaborationRuntimeRepository {
            override val supportsDeviceActivation
                get() = supported

            override suspend fun reserveInspection(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeInspection> {
                if (openAtInspection) return AppResult.Success(CollaborationRuntimeInspection.Open)
                check(!inspectionHeld)
                inspectionHeld = true
                return AppResult.Success(
                    object : CollaborationRuntimeInspection.Reserved {
                        override val owner = owner
                        override val deviceId = deviceId

                        override fun isCurrent() = inspectionCurrent

                        override fun close() {
                            check(inspectionHeld)
                            inspectionHeld = false
                            inspectionCloses++
                        }
                    }
                )
            }

            override suspend fun handoverIntent(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun stageHandover(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
                intent:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun updateRetention(
                retention:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
            ): AppResult<Unit> = closeOtherOwners(retention.foreground)

            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
            > = error("No implicit background runtime")

            override suspend fun readOwnReadReceipts(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
            > = error("No implicit read receipt lookup")

            override suspend fun sendReadReceipt(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                eventId: String,
                visibility:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
            ): AppResult<Unit> = error("No implicit read receipt delivery")

            override suspend fun synchronizeDevice(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit encryption activation")

            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit pusher activation")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No implicit history head lookup")

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No implicit history acquisition")

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
            > = error("No implicit queue acquisition")

            override suspend fun isOpen(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Boolean> = AppResult.Success(opened)

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit conversation entry")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("Installation cannot close unrelated owners")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> {
                assertEquals(this@CollaborationActivationFixture.command.owner, owner)
                check(!inspectionHeld)
                installations.add(command)
                ownedLease.installedOperationId = command.command.id
                return onInstall()
            }

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> {
                check(!inspectionHeld)
                restores++
                return onRestore()
            }
        }

    fun operation(
        runtime: CollaborationRuntimeRepository = runtimes,
        originalCommands: CollaborationSetupCommandRepository = command.commands,
        client: dev.fajar.starter.common.config.AppBuild = command.build,
    ) =
        ActivateCollaborationDevice(
            originalCommands,
            command.remote,
            runtime,
            command.access.sessions,
            command.access.workspaces,
            command.server,
            client,
            command.clock,
            command.access.admission.repository,
            object : CollaborationSdkInventoryRepository {
                override suspend fun inspect(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSdkInventory> {
                    check(inspectionHeld)
                    return inspect()
                }
            },
            object : CollaborationSdkPossessionRepository {
                override suspend fun readPrivateIdentity(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSdkPossession?> {
                    check(inspectionHeld)
                    return possess()
                }
            },
            object : CollaborationRecoveryWakeRepository {
                override suspend fun pending() = AppResult.Success(wake)

                override suspend fun request(): AppResult<CollaborationRecoveryWake> {
                    val next = CollaborationRecoveryWake("wake-${scheduled.size}")
                    wake = next
                    return AppResult.Success(next)
                }

                override suspend fun acknowledge(wake: CollaborationRecoveryWake) =
                    error("No wake acknowledgment")
            },
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertNotNull(wake)
                    scheduled += key
                    return schedule()
                }
            },
        )
}
