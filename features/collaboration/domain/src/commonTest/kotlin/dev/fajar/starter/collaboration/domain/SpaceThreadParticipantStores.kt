package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.*

/** Exact local participant reads; any write, per-device selection or other namespace fails. */
internal class SpaceThreadParticipantStores(private val room: SpaceRoomBindingPreparationFixture) {
    val peer =
        if (room.conversation.firstAccountId == room.access.accountId)
            room.conversation.secondAccountId
        else room.conversation.firstAccountId
    val contact =
        CollaborationSpaceContact(
            peer,
            "Fictional colleague",
            listOf(CollaborationSpaceContactCompany(room.position.companyId, "Fictional company")),
            false,
        )
    val selected =
        CollaborationSpaceIdentity(
            room.access.spaceId,
            room.access.accountId,
            "matrix.example.test",
            "@hrisg_${room.access.spaceId.replace("-", "")}_${room.access.accountId.replace("-", "")}:matrix.example.test",
            CollaborationSpaceIdentityOrigin.SPACE,
            null,
            0,
            room.conversation.createdAt,
        )
    val original =
        CollaborationSelfIdentity(
            "@hris_${room.position.companyId.replace("-", "")}_${room.access.accountId.replace("-", "")}:matrix.example.test"
        )
    val contacts =
        MutableStateFlow<AppResult<CollaborationSpaceContact?>>(AppResult.Success(contact))
    val registrations =
        MutableStateFlow<AppResult<List<CollaborationSpaceRegistrationRecord>>>(
            AppResult.Success(listOf(record()))
        )
    var readContacts: () -> Flow<AppResult<CollaborationSpaceContact?>> = { contacts }
    var readRegistrations: () -> Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>> = {
        registrations
    }
    var readOriginal: suspend () -> AppResult<CollaborationSelfIdentity?> = {
        AppResult.Success(original)
    }
    var contactStarts = 0
    var contactStops = 0
    var registrationStarts = 0
    var registrationStops = 0
    var originalReads = 0
    var originalStops = 0

    fun observeContact(access: CollaborationSpaceLocalAccess, accountId: String) = flow {
        assertEquals(room.access, access)
        assertEquals(peer, accountId)
        contactStarts++
        try {
            emitAll(readContacts())
        } finally {
            contactStops++
        }
    }

    val commands =
        object : CollaborationSpaceRegistrationCommandRepository {
            override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                assertEquals(room.access, access)
                registrationStarts++
                try {
                    emitAll(readRegistrations())
                } finally {
                    registrationStops++
                }
            }

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): Flow<AppResult<CollaborationSpaceRegistrationRecord?>> =
                error("No native-parent selection")

            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> = error("No native-parent lookup")

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceRegistrationCommand,
            ): AppResult<CollaborationSetupInsertion> = error("No registration write")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRegistrationRecord,
                outcome: CollaborationSpaceRegistrationOutcome,
                attempts: Int,
            ): AppResult<Boolean> = error("No registration write")
        }
    val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationSelfIdentity?> {
                assertEquals(CollaborationSpaceRoomOrigin.LEGACY, room.binding.room.origin)
                assertEquals(
                    CollaborationLocalAccess(
                        room.access.sessionId,
                        room.access.audience,
                        room.access.accountId,
                        room.position.companyId,
                    ),
                    access,
                )
                originalReads++
                try {
                    return readOriginal()
                } finally {
                    originalStops++
                }
            }

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ): AppResult<Boolean> = error("No identity write")
        }

    fun record() =
        CollaborationSpaceRegistrationRecord(
            CollaborationSpaceRegistrationCommand(
                "d0000000-0000-4000-8000-000000000001",
                CollaborationSpaceDeviceBinding(
                    room.access.audience,
                    room.access.accountId,
                    room.access.spaceId,
                    room.owner.parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                5,
                room.conversation.createdAt,
                selected,
                "Fictional device",
                CollaborationSpaceDeviceOrigin.SPACE,
                null,
            ),
            0,
            0,
            CollaborationSpaceRegistrationOutcome.Pending,
        )
}
