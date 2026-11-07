package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceRoomRequest
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.flow

internal class SpaceRoomPreparationFixture {
    val resolution = SpaceRoomRequestResolutionFixture()
    val local = resolution.local
    val native = local.native
    val base = local.base
    val access = local.access
    val owner = local.owner
    val conversation = resolution.conversation
    val original = resolution.command
    val peer = local.peer
    var active: List<CollaborationSpaceConversationCommandRecord> = emptyList()
    val archives = mutableMapOf<String, CollaborationSpaceConversationCommandRecord>()
    val inserted = mutableListOf<CollaborationSpaceConversationCommand.Room>()
    val replacements = mutableListOf<CollaborationSpaceConversationCommand.Room>()
    val findIds = mutableListOf<String>()
    var roomReads = 0
    var deviceReads = 0
    var observations = 0
    var beforeObserve: suspend () -> Unit = {}
    var beforeFind: suspend (String) -> Unit = {}
    var onInsert:
        suspend (CollaborationSpaceConversationCommand.Room) -> AppResult<
                CollaborationSetupInsertion
            > =
        {
            active = listOf(record(it))
            AppResult.Success(CollaborationSetupInsertion.STORED)
        }
    var onReplace:
        suspend (
            CollaborationSpaceConversationCommandRecord, CollaborationSpaceConversationCommand.Room,
        ) -> AppResult<Boolean> =
        { previous, next ->
            commit(previous, next)
        }
    var view = CollaborationSpaceRoomView(conversation, null, null)
    var onRoom: suspend () -> AppResult<CollaborationSpaceRoomView> = { AppResult.Success(view) }
    val device =
        CollaborationSpaceDeviceStatus(
            original.intent.request.deviceId,
            12,
            CollaborationDeviceState.ACTIVE,
            CollaborationNativePlatform.ANDROID,
            CollaborationSpaceDeviceOrigin.LEGACY,
            "Fixture",
            CollaborationSpaceProvisioningStatus(
                0,
                CollaborationProvisioningState.SUCCEEDED,
                1,
                native.setup.now - 60.seconds,
                CollaborationSpaceProvisioningOutcome(
                    0,
                    CollaborationProvisioningState.SUCCEEDED,
                    native.setup.now - 120.seconds,
                    null,
                ),
                null,
            ),
            native.setup.now,
            null,
        )
    var onDevice: suspend () -> AppResult<CollaborationSpaceDeviceObservation> = {
        AppResult.Success(CollaborationSpaceDeviceObservation(owner, device))
    }
    val commands =
        object : CollaborationSpaceConversationCommandRepository by local.commands {
            override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                observations++
                beforeObserve()
                emit(AppResult.Success(active))
            }

            override suspend fun find(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceConversationCommandRecord?> {
                findIds += id
                beforeFind(id)
                return AppResult.Success(active.firstOrNull { it.command.id == id } ?: archives[id])
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceConversationCommand,
            ): AppResult<CollaborationSetupInsertion> {
                val room = assertIs<CollaborationSpaceConversationCommand.Room>(command)
                inserted += room
                return onInsert(room)
            }

            override suspend fun prepareReplacement(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
                replacement: CollaborationSpaceConversationCommand.Room,
            ): AppResult<Boolean> {
                replacements += replacement
                return onReplace(expected, replacement)
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
                outcome: CollaborationSpaceConversationOutcome,
                attempts: Int,
            ): AppResult<Boolean> =
                error("Preparation never dispatches or changes an original outcome")

            override suspend fun archiveRecorded(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
            ): AppResult<Boolean> = error("Preparation does not publish room metadata")
        }
    val rooms =
        object : CollaborationSpaceRoomRepository by resolution.rooms {
            override suspend fun get(
                conversation: CollaborationSpaceConversation
            ): AppResult<CollaborationSpaceRoomView> {
                roomReads++
                assertEquals(this@SpaceRoomPreparationFixture.conversation, conversation)
                return onRoom()
            }

            override suspend fun fenceRequest(
                conversation: CollaborationSpaceConversation,
                original: CollaborationSpaceRoomRequestIntent,
            ): AppResult<CollaborationSpaceRoomRequestResolution> =
                error("Preparation never creates a fence")
        }
    val devices =
        object : CollaborationSpaceDeviceRepository {
            override suspend fun status(
                owner: CollaborationSpaceOwner
            ): AppResult<CollaborationSpaceDeviceObservation> {
                deviceReads++
                assertEquals(this@SpaceRoomPreparationFixture.owner, owner)
                return onDevice()
            }

            override suspend fun register(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceRegistrationCommand,
            ): AppResult<CollaborationSetupReceipt> = error("Preparation never enrolls devices")
        }

    fun record(command: CollaborationSpaceConversationCommand.Room = original) =
        CollaborationSpaceConversationCommandRecord(
            command,
            0,
            0,
            CollaborationSpaceConversationOutcome.Pending,
        )

    fun fenced() = resolution.resolved()

    fun commit(
        previous: CollaborationSpaceConversationCommandRecord,
        next: CollaborationSpaceConversationCommand.Room,
    ): AppResult<Boolean> {
        assertTrue(validCollaborationSpaceRoomHandoff(access, previous, next))
        if (active.singleOrNull() != previous) return AppResult.Success(false)
        archives[previous.command.id] = previous.copy(successorId = next.id)
        active = listOf(record(next))
        return AppResult.Success(true)
    }

    fun terminal(
        state: CollaborationProvisioningState = CollaborationProvisioningState.FAILED,
        account: String = peer,
        revision: Long = 9,
    ) =
        CollaborationSpaceRoomStatus(
            CollaborationSpaceRoomAttempt(account, revision),
            state,
            1,
            native.setup.now - 120.seconds,
            native.setup.now - 120.seconds,
            native.setup.now + 60.seconds,
            native.setup.now - 60.seconds,
            if (state == CollaborationProvisioningState.FAILED)
                CollaborationSpaceRoomFailure.PROVIDER_UNAVAILABLE
            else null,
            CollaborationSpaceRoomOutcome(
                revision,
                state,
                native.setup.now - 60.seconds,
                if (state == CollaborationProvisioningState.FAILED)
                    CollaborationSpaceRoomFailure.PROVIDER_UNAVAILABLE
                else null,
            ),
        )

    fun prepare() =
        PrepareCollaborationSpaceRoomRequest(
            commands,
            rooms,
            devices,
            base.directory,
            local.sessions,
            native.server,
            local.build,
            native.clock,
            native.time,
        )
}
