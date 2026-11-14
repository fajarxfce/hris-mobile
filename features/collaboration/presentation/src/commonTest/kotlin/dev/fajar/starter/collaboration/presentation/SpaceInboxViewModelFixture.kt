package dev.fajar.starter.collaboration.presentation

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spaceinbox.SpaceChatInboxViewModel
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class SpaceInboxViewModelFixture {
    val thread = SpaceChatThreadFixture()
    val access = thread.access
    val version = CollaborationSpaceInboxVersion(chatId(900), 4, 4)
    val versions =
        MutableStateFlow<AppResult<CollaborationSpaceInboxVersion?>>(AppResult.Success(version))
    val catalogVersion =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(CollaborationSpaceConversationCatalogVersion(chatId(901), 4))
        )
    val pairs =
        (0..2).map { index ->
            thread.metadata.copy(id = chatId(810 + index), secondAccountId = chatId(820 + index))
        }
    val contacts =
        pairs.associate { pair ->
            pair.secondAccountId to
                MutableStateFlow<AppResult<CollaborationSpaceContact?>>(
                    AppResult.Success(
                        CollaborationSpaceContact(
                            pair.secondAccountId,
                            "Colleague ${pair.id.takeLast(2)}",
                            listOf(CollaborationSpaceContactCompany(access.spaceId, "Employer")),
                            false,
                        )
                    )
                )
        }
    var versionReaders = 0
    var contactReaders = 0
    var metadataReads = 0
    var beforeContact: suspend (String) -> Unit = {}
    var beforeMetadata: suspend () -> Unit = {}
    private val directory =
        object : CollaborationSpaceDirectoryRepository by thread.directory {
            override fun observeContact(
                access: CollaborationSpaceLocalAccess,
                accountId: String,
            ): Flow<AppResult<CollaborationSpaceContact?>> = flow {
                contactReaders++
                try {
                    beforeContact(accountId)
                    emitAll(contacts.getValue(accountId))
                } finally {
                    contactReaders--
                }
            }
        }
    private val metadata =
        object : CollaborationSpaceConversationMetadataRepository by thread.content.metadata {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                metadataReads++
                beforeMetadata()
                return AppResult.Success(pairs.firstOrNull { it.id == conversationId })
            }
        }
    private val catalog =
        object : CollaborationSpaceConversationCatalogRepository {
            override fun observeVersion(access: CollaborationSpaceLocalAccess) = catalogVersion

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                rawLimit: Int,
            ): AppResult<CollaborationSpaceConversationCatalogRead> = error("No catalog scan")
        }
    private val progress =
        object : CollaborationSpaceInboxHistoryProgressRepository {
            override fun observeDestination(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceInboxVersion?>> = flow {
                versionReaders++
                try {
                    emitAll(versions)
                } finally {
                    versionReaders--
                }
            }

            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?> =
                error("No repair progress read")

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistoryMutation,
            ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?> = error("No repair mutation")
        }
    private val windows =
        object : CollaborationSpaceInboxWindowRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                anchor: CollaborationSpaceInboxWindowAnchor,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxWindow> =
                AppResult.Success(
                    CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null)
                )
        }

    fun model() =
        SpaceChatInboxViewModel(
            access,
            ObserveCollaborationSpaceInboxDestination(
                progress,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
            ReadCollaborationSpaceInboxWindow(
                windows,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
            ObserveCollaborationSpaceInboxLabels(
                metadata,
                catalog,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
        )

    fun deny() {
        thread.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(chatId(999), thread.denial))
    }

    fun open() {
        thread.gate.value = AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
    }
}
