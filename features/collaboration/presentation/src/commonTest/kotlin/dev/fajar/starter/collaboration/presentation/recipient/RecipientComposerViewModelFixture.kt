package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.*

internal class RecipientComposerViewModelFixture {
    val thread = SpaceChatThreadFixture()
    val access = thread.access
    val peer = chatId(990)
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceRecipientDraft?>>(AppResult.Success(null))
    val originals = linkedMapOf<String, CollaborationSpaceRecipientSubmission>()
    val saved = mutableListOf<CollaborationSpaceRecipientDraft>()
    val handed = mutableListOf<CollaborationSpaceRecipientSubmission>()
    var starts = 0
    var readers = 0
    var maximumReaders = 0
    var contactReads = 0
    var currentAdmission: AppResult<CollaborationSpaceDirectoryAdmission>? = null
    var beforeDraftEmission: suspend (AppResult<CollaborationSpaceRecipientDraft?>) -> Unit = {}
    var beforeSave: suspend () -> Unit = {}
    var beforeHandoff: suspend () -> Unit = {}
    var afterHandoff: suspend () -> Unit = {}
    var handoffFailure: Failure? = null
    var originalReadFailure: Failure? = null
    val drafts =
        object : CollaborationSpaceRecipientDraftRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
                flow {
                    assertEquals(this@RecipientComposerViewModelFixture.access, access)
                    assertEquals(peer, peerAccountId)
                    starts++
                    readers++
                    maximumReaders = maxOf(maximumReaders, readers)
                    try {
                        facts.collect { value ->
                            beforeDraftEmission(value)
                            emit(value)
                        }
                    } finally {
                        readers--
                    }
                }

            override suspend fun get(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
                facts.value

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientDraft?,
                replacement: CollaborationSpaceRecipientDraft,
            ): AppResult<Boolean> {
                beforeSave()
                val current = (facts.value as AppResult.Success).value
                if (current != expected) return AppResult.Success(false)
                saved += replacement
                facts.value = AppResult.Success(replacement)
                return AppResult.Success(true)
            }
        }
    val outbox =
        object : CollaborationSpaceRecipientOutboxRepository {
            override fun observeVersion(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceRecipientOutboxVersion?>> =
                error("The composer does not own transfer")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceRecipientSubmission?> =
                originalReadFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(originals[id])

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceRecipientSubmissionPage> =
                error("No full outbox read by the editor")

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientSubmission,
            ): AppResult<Boolean> = error("The editor cannot retire durable sources")

            override suspend fun handoff(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientDraft?,
                submission: CollaborationSpaceRecipientSubmission,
            ): AppResult<CollaborationComposerHandoffResult> {
                beforeHandoff()
                val current = (facts.value as AppResult.Success).value
                if (current != expected)
                    return AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                handed += submission
                originals[submission.id] = submission
                facts.value = AppResult.Success(submission.nextDraft)
                afterHandoff()
                return handoffFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationComposerHandoffResult.STORED)
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by thread.directory {
            override suspend fun admission(access: CollaborationSpaceLocalAccess) =
                currentAdmission ?: thread.gate.value

            override fun observeContact(
                access: CollaborationSpaceLocalAccess,
                accountId: String,
            ): Flow<AppResult<CollaborationSpaceContact?>> = flow {
                assertEquals(peer, accountId)
                contactReads++
                emit(
                    AppResult.Success(
                        CollaborationSpaceContact(
                            peer,
                            "Bima",
                            listOf(
                                CollaborationSpaceContactCompany(chatId(992), "Example Company")
                            ),
                            false,
                        )
                    )
                )
            }
        }

    fun model() =
        RecipientComposerViewModel(
            access,
            peer,
            ObserveCollaborationSpaceRecipientDraft(
                drafts,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
            EditCollaborationSpaceRecipientDraft(
                drafts,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
            SubmitCollaborationSpaceRecipientComposer(
                outbox,
                drafts,
                thread.content.commands,
                directory,
                thread.base.sessions,
                thread.base.server,
            ),
        )
}
