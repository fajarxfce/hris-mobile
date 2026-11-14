package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceRecipientMessageIntent
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class RecipientChatThreadViewModelFixture {
    val editor = RecipientComposerViewModelFixture()
    val thread = editor.thread
    val access = editor.access
    val peer = editor.peer
    val metadataValue = thread.metadata.copy(secondAccountId = peer)
    val version =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(null)
        )
    val sourceRevision = MutableStateFlow(0L)
    var sourceReaders = 0
    var sourceStarts = 0
    var maxSourceReaders = 0
    var beforeSource: suspend () -> Unit = {}
    var metadataReads = 0
    var sourceFailure: Failure? = null
    val projection =
        object : CollaborationSpaceRecipientProjectionRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): Flow<AppResult<CollaborationSpaceRecipientProjection>> = flow {
                assertEquals(this@RecipientChatThreadViewModelFixture.access, access)
                assertEquals(peer, peerAccountId)
                sourceReaders++
                sourceStarts++
                maxSourceReaders = maxOf(maxSourceReaders, sourceReaders)
                try {
                    combine(editor.facts, sourceRevision) { draft, _ ->
                            sourceFailure?.let { AppResult.Failed(it) }
                                ?: when (draft) {
                                    is AppResult.Failed -> draft
                                    is AppResult.Success ->
                                        AppResult.Success(
                                            CollaborationSpaceRecipientProjection(
                                                draft.value,
                                                editor.originals.values.toList(),
                                            )
                                        )
                                }
                        }
                        .collect { captured ->
                            beforeSource()
                            emit(captured)
                        }
                } finally {
                    sourceReaders--
                }
            }
        }
    val catalog =
        object : CollaborationSpaceConversationCatalogRepository {
            override fun observeVersion(access: CollaborationSpaceLocalAccess) = version

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                rawLimit: Int,
            ): AppResult<CollaborationSpaceConversationCatalogRead> = error("No metadata inventory")
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository by thread.content.metadata {
            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(peer, peerAccountId)
                metadataReads++
                return AppResult.Success(metadataValue)
            }

            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(metadataValue.id, conversationId)
                return AppResult.Success(metadataValue)
            }
        }

    val room = "!room:example.test"
    val bindingFacts =
        MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(AppResult.Success(null))
    val historyFacts =
        MutableStateFlow<AppResult<CollaborationHistoryPage>>(
            AppResult.Success(CollaborationHistoryPage(emptyList(), null, roomId = room))
        )
    var bindingReaders = 0
    val bindings =
        object : CollaborationSpaceRoomBindingRepository by thread.content.bindings {
            override fun observeByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationSpaceRoomBinding?>> = flow {
                assertEquals(metadataValue.id, conversationId)
                bindingReaders++
                try {
                    emitAll(bindingFacts)
                } finally {
                    bindingReaders--
                }
            }
        }
    val history =
        object : CollaborationSpaceHistoryRepository by thread.content.history {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> {
                assertEquals(metadataValue.id, conversationId)
                assertEquals(room, roomId)
                return historyFacts
            }
        }

    fun bindRoom() {
        bindingFacts.value =
            AppResult.Success(
                CollaborationSpaceRoomBinding(
                    metadataValue,
                    CollaborationSpacePublishedRoom(
                        room,
                        "example.test",
                        CollaborationSpaceRoomOrigin.SPACE,
                        thread.base.now,
                    ),
                )
            )
    }

    fun publishHistory(record: CollaborationSpaceMessageRecord) {
        val sent = record.state as CollaborationMessageState.Sent
        val origin = requireNotNull(sent.origin)
        historyFacts.value =
            AppResult.Success(
                CollaborationHistoryPage(
                    listOf(
                        CollaborationHistoryMessage(
                            origin.sender,
                            record.intent.id,
                            sent.eventId,
                            record.intent.createdAt.toEpochMilliseconds(),
                            origin.contentHash,
                            record.intent.text,
                            listOf(sent.eventId),
                            false,
                        )
                    ),
                    null,
                    roomId = room,
                )
            )
    }

    fun associate() {
        version.value =
            AppResult.Success(CollaborationSpaceConversationCatalogVersion(chatId(601), 1))
    }

    fun publishSource() {
        sourceRevision.value++
    }

    fun sent(original: CollaborationSpaceRecipientSubmission) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceRecipientMessageIntent(access, original, metadataValue.id),
            3,
            CollaborationMessageState.Sent(
                CollaborationMessageDevice(
                    chatId(602),
                    chatId(603),
                    chatId(604),
                    "!room:example.test",
                ),
                "transaction-${original.id}",
                "${'$'}event-${original.id}",
                CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
            ),
        )

    fun model() =
        RecipientChatThreadViewModel(
            access,
            peer,
            ObserveCollaborationSpaceRecipientProjection(
                projection,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
            ObserveCollaborationSpaceRecipientDestination(
                catalog,
                metadata,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
            ObserveCollaborationSpaceThread(
                bindings,
                metadata,
                history,
                thread.original.history,
                thread.content.commands,
                thread.original.messages,
                thread.content.registrations,
                thread.original.identities,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
            ObserveCollaborationSpaceComposerProgress(
                thread.content.commands,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
            EditCollaborationSpaceRecipientDraft(
                editor.drafts,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
            SubmitCollaborationSpaceRecipientComposer(
                editor.outbox,
                editor.drafts,
                thread.content.commands,
                editor.directory,
                thread.base.sessions,
                thread.base.server,
            ),
        )
}
