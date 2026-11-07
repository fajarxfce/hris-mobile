package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReplayCollaborationSpaceConversationOpen
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlinx.coroutines.flow.flowOf

internal class SpaceConversationOpenReplayFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val native = base.native
    val owner = base.owner
    val access = base.access
    val peer = native.contact.accountId
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    val command =
        CollaborationSpaceConversationCommand.Open(
            "81000000-0000-4000-8000-000000000001",
            CollaborationSpaceIdentityBinding(
                access.audience,
                access.accountId,
                access.spaceId,
                owner.parent.nativeSessionId,
            ),
            peer,
            native.setup.now,
        )
    val receipt = CollaborationSetupReceipt("82000000-0000-4000-8000-000000000001", 0)
    var active: CollaborationSpaceConversationCommandRecord? =
        CollaborationSpaceConversationCommandRecord(
            command,
            0,
            0,
            CollaborationSpaceConversationOutcome.Pending,
        )
    var archived: CollaborationSpaceConversationCommandRecord? = null
    var local: CollaborationSpaceConversationMetadata? = null
    val opens = mutableListOf<Pair<CollaborationSpaceOwner, String>>()
    val gets = mutableListOf<Pair<CollaborationSpaceOwner, String>>()
    val updates = mutableListOf<CollaborationSpaceConversationOutcome>()
    var archives = 0
    var metadataWrites = 0
    var beforeFind: suspend () -> Unit = {}
    var beforeSession: suspend () -> Unit = {}
    var beforeMetadataRead: suspend () -> Unit = {}
    var onOpen: suspend () -> AppResult<CollaborationSetupReceipt> = { AppResult.Success(receipt) }
    var onGet:
        suspend (CollaborationSpaceOwner, String) -> AppResult<CollaborationSpaceConversation> =
        { owner, id ->
            AppResult.Success(conversation(owner, id))
        }
    var onUpdate:
        suspend (
            CollaborationSpaceConversationCommandRecord, CollaborationSpaceConversationOutcome, Int,
        ) -> AppResult<Boolean> =
        { expected, outcome, attempts ->
            commitUpdate(expected, outcome, attempts)
        }
    var onArchive: suspend (CollaborationSpaceConversationCommandRecord) -> AppResult<Boolean> = {
        commitArchive(it)
    }
    var onMetadataWrite: suspend (CollaborationSpaceConversation) -> AppResult<Boolean> = {
        local = it.localMetadata()
        AppResult.Success(true)
    }

    val commands =
        object : CollaborationSpaceConversationCommandRepository {
            override fun observeAll(access: CollaborationSpaceLocalAccess) =
                flowOf(AppResult.Success(listOfNotNull(active)))

            override suspend fun find(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceConversationCommandRecord?> {
                beforeFind()
                return AppResult.Success((active ?: archived)?.takeIf { it.command.id == id })
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceConversationCommand,
            ): AppResult<CollaborationSetupInsertion> =
                error("Replay never allocates or inserts a new command")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
                outcome: CollaborationSpaceConversationOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                updates += outcome
                return onUpdate(expected, outcome, attempts)
            }

            override suspend fun archiveRecorded(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
            ): AppResult<Boolean> {
                archives++
                return onArchive(expected)
            }
        }
    val conversations =
        object : CollaborationSpaceConversationRepository {
            override suspend fun list(
                owner: CollaborationSpaceOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceConversationPage> =
                error("Replay never scans remote conversations")

            override suspend fun get(
                owner: CollaborationSpaceOwner,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversation> {
                gets += owner to conversationId
                return onGet(owner, conversationId)
            }

            override suspend fun open(
                owner: CollaborationSpaceOwner,
                operation: String,
                peerAccountId: String,
            ): AppResult<CollaborationSetupReceipt> {
                assertEquals(command.id, operation)
                assertEquals(peer, peerAccountId)
                assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(
                    assertNotNull(active).outcome
                )
                assertTrue(assertNotNull(active).attempts > 0)
                opens += owner to operation
                return onOpen()
            }
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                beforeMetadataRead()
                return AppResult.Success(local)
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> =
                error("Replay reads the recorded canonical ID")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> {
                metadataWrites++
                assertIs<CollaborationSpaceConversationOutcome.Recorded>(
                    assertNotNull(active ?: archived).outcome
                )
                return onMetadataWrite(conversation)
            }
        }
    val sessions =
        object : SessionRepository by native.setup.sessions {
            override suspend fun current():
                AppResult<dev.fajar.starter.identity.domain.entities.Session?> {
                beforeSession()
                return native.setup.sessions.current()
            }
        }

    fun commitUpdate(
        expected: CollaborationSpaceConversationCommandRecord,
        outcome: CollaborationSpaceConversationOutcome,
        attempts: Int,
    ): AppResult<Boolean> {
        if (active != expected) return AppResult.Success(false)
        assertTrue(
            validCollaborationSpaceConversationCommandTransition(expected, outcome, attempts)
        )
        active =
            expected.copy(revision = expected.revision + 1, attempts = attempts, outcome = outcome)
        assertTrue(validCollaborationSpaceConversationCommandRecord(access, assertNotNull(active)))
        return AppResult.Success(true)
    }

    fun commitArchive(expected: CollaborationSpaceConversationCommandRecord): AppResult<Boolean> {
        assertTrue(
            matchesRecordedCollaborationSpaceConversation(access, expected, assertNotNull(local))
        )
        if (archived == expected) return AppResult.Success(true)
        if (active != expected) return AppResult.Success(false)
        archived = expected
        active = null
        return AppResult.Success(true)
    }

    fun conversation(owner: CollaborationSpaceOwner = this.owner, id: String = receipt.id) =
        CollaborationSpaceConversation(
            owner,
            id,
            access.accountId,
            peer,
            peer,
            command.createdAt,
            0,
        )

    fun recorded() =
        CollaborationSpaceConversationCommandRecord(
            command,
            2,
            1,
            CollaborationSpaceConversationOutcome.Recorded(receipt),
        )

    fun replay() =
        ReplayCollaborationSpaceConversationOpen(
            commands,
            conversations,
            metadata,
            base.directory,
            sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )
}
