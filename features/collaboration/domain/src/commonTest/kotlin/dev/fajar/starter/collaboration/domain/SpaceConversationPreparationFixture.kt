package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceConversation
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.assertIs
import kotlinx.coroutines.flow.*

internal class SpaceConversationPreparationFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val native = base.native
    val access = base.access
    val peer = native.contact.accountId
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    var active: AppResult<List<CollaborationSpaceConversationCommandRecord>> =
        AppResult.Success(emptyList())
    var canonical: AppResult<CollaborationSpaceConversationMetadata?> = AppResult.Success(null)
    var observations = 0
    var metadataReads = 0
    var admissionReads = 0
    val inserted = mutableListOf<CollaborationSpaceConversationCommand>()
    var beforeObserve: suspend () -> Unit = {}
    var beforeMetadata: suspend () -> Unit = {}
    var beforeSession: suspend () -> Unit = {}
    var beforeFind: suspend () -> Unit = {}
    var onInsert:
        suspend (CollaborationSpaceConversationCommand) -> AppResult<CollaborationSetupInsertion> =
        { command ->
            active = AppResult.Success(listOf(record(command)))
            AppResult.Success(CollaborationSetupInsertion.STORED)
        }
    val commands =
        object : CollaborationSpaceConversationCommandRepository {
            override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                observations++
                beforeObserve()
                emit(active)
            }

            override suspend fun find(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceConversationCommandRecord?> {
                beforeFind()
                return AppResult.Success(
                    (active as? AppResult.Success)?.value?.firstOrNull { it.command.id == id }
                )
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceConversationCommand,
            ): AppResult<CollaborationSetupInsertion> {
                inserted += command
                return onInsert(command)
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
                outcome: CollaborationSpaceConversationOutcome,
                attempts: Int,
            ): AppResult<Boolean> = error("Preparation never dispatches")

            override suspend fun prepareReplacement(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
                replacement: CollaborationSpaceConversationCommand.Room,
            ): AppResult<Boolean> = error("No implicit room replacement")

            override suspend fun archiveRecorded(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceConversationCommandRecord,
            ): AppResult<Boolean> = error("Preparation never archives")
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> = error("Exact peer lookup only")

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                check(peerAccountId == peer)
                metadataReads++
                beforeMetadata()
                return canonical
            }

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("Preparation never publishes canonical metadata")
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                admissionReads++
                return base.directory.admission(access)
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

    fun record(command: CollaborationSpaceConversationCommand = original()) =
        CollaborationSpaceConversationCommandRecord(
            command,
            0,
            0,
            CollaborationSpaceConversationOutcome.Pending,
        )

    fun original(index: Int = 1) =
        CollaborationSpaceConversationCommand.Open(
            "61000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
            CollaborationSpaceIdentityBinding(
                access.audience,
                access.accountId,
                access.spaceId,
                native.parent.nativeSessionId,
            ),
            peer,
            native.setup.now,
        )

    fun canonical() =
        CollaborationSpaceConversationMetadata(
            access.spaceId,
            "62000000-0000-4000-8000-000000000001",
            access.accountId,
            peer,
            peer,
            native.setup.now,
            0,
        )

    suspend fun discovery() =
        assertIs<AppResult.Success<CollaborationSpaceDiscovery>>(native.discovery()(native.parent))
            .value

    fun prepare() =
        PrepareCollaborationSpaceConversation(
            commands,
            metadata,
            directory,
            sessions,
            native.server,
            build,
            native.clock,
        )
}
