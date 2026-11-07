package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdvanceCollaborationSpaceRuntimeTransfer
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class SpaceRuntimeTransferFixture {
    val setup = SpaceSetupFixture()
    val events = mutableListOf<String>()
    var retained: CollaborationRuntimeHandoverIntent? = null
    val requests = mutableListOf<CollaborationSpaceRuntimeTransfer>()
    var beforeIntent: suspend () -> AppResult<CollaborationRuntimeHandoverIntent?> = {
        AppResult.Success(retained)
    }
    var beforeStage:
        suspend (CollaborationExistingRuntimeHandover) -> AppResult<
                CollaborationRuntimeHandoverIntent
            > =
        {
            assertEquals(setup.registered.command.legacy, it.original)
            if (retained != null && retained != it.intent)
                AppResult.Failed(setup.base.storageFailure)
            else {
                retained = it.intent
                AppResult.Success(it.intent)
            }
        }
    var publication:
        suspend (CollaborationSpaceRuntimeTransfer) -> AppResult<
                CollaborationSpaceLegacyPublication
            > =
        {
            AppResult.Success(observation(it))
        }
    var freeze:
        suspend (CollaborationSpaceRuntimeTransfer) -> AppResult<CollaborationSetupReceipt> =
        {
            assertEquals(retained, it.intent)
            AppResult.Success(CollaborationSetupReceipt(setup.registration.sdkDevice, 1))
        }
    var drain:
        suspend (CollaborationSpaceRuntimeTransfer) -> AppResult<CollaborationSpaceRuntimeDrain> =
        {
            AppResult.Success(
                CollaborationSpaceRuntimeDrain(
                    it.intent.spaceId,
                    it.intent.deviceId,
                    1,
                    0,
                    null,
                    setup.native.setup.now,
                )
            )
        }

    init {
        setup.registration.beforeCurrent = { events += "registration" }
        setup.registration.beforeStatus = {
            events += "status"
            AppResult.Success(
                CollaborationSpaceDeviceObservation(setup.metadataOwner, setup.status)
            )
        }
    }

    val runtime =
        object : CollaborationRuntimeRepository by CollaborationDeliveryFixture().runtimes {
            override suspend fun handoverIntent(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeHandoverIntent?> {
                assertEquals(setup.registration.sdkOwner, owner)
                assertEquals(setup.registration.sdkDevice, deviceId)
                events += "intent"
                return beforeIntent()
            }

            override suspend fun stageExistingHandover(
                currentParent: CollaborationNativeParent,
                target: CollaborationExistingRuntimeHandover,
            ): AppResult<CollaborationRuntimeHandoverIntent> {
                assertEquals(setup.owner.parent, currentParent)
                events += "stage"
                return beforeStage(target)
            }
        }

    val transfers =
        object : CollaborationSpaceRuntimeTransferRepository {
            override suspend fun publication(
                owner: CollaborationSpaceOwner,
                transfer: CollaborationSpaceRuntimeTransfer,
            ): AppResult<CollaborationSpaceLegacyPublication> {
                assertEquals(setup.owner, owner)
                events += "publication"
                requests += transfer
                return this@SpaceRuntimeTransferFixture.publication(transfer)
            }

            override suspend fun freeze(
                owner: CollaborationSpaceOwner,
                transfer: CollaborationSpaceRuntimeTransfer,
            ): AppResult<CollaborationSetupReceipt> {
                assertEquals(setup.owner, owner)
                events += "freeze"
                requests += transfer
                return freeze(transfer)
            }

            override suspend fun drain(
                owner: CollaborationSpaceOwner,
                transfer: CollaborationSpaceRuntimeTransfer,
            ): AppResult<CollaborationSpaceRuntimeDrain> {
                assertEquals(setup.owner, owner)
                events += "drain"
                requests += transfer
                return drain(transfer)
            }
        }

    fun observation(transfer: CollaborationSpaceRuntimeTransfer) =
        CollaborationSpaceLegacyPublication(
            transfer.intent.spaceId,
            transfer.intent.deviceId,
            transfer.intent.expectedDeviceVersion,
            transfer.legacy.binding.companyId,
            transfer.legacy.deviceId,
            transfer.intent.fingerprint,
            setup.native.setup.now,
            setup.native.setup.now + 30.seconds,
        )

    fun advance() =
        AdvanceCollaborationSpaceRuntimeTransfer(
            setup.registration.commands,
            setup.registration.devices,
            transfers,
            runtime,
            setup.base.directory,
            setup.base.sessions,
            setup.native.server,
            setup.build,
            setup.native.clock,
            setup.native.time,
        )

    suspend fun run() = advance()(setup.owner, setup.registrationId)

    fun replaceSession() =
        setup.registration.session(setup.native.session.copy(id = "another-login"))
}
