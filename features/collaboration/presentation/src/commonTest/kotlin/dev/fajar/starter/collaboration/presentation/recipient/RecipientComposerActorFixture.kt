package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope

internal class RecipientComposerActorFixture(scope: CoroutineScope) {
    val access =
        CollaborationSpaceLocalAccess("session", "https://example.test/", chatId(1), chatId(2))
    val peer = chatId(3)
    var now = Instant.parse("2026-11-14T00:00:00Z")
    var persisted: CollaborationSpaceRecipientDraft? = null
    val retained = linkedMapOf<String, CollaborationSpaceRecipientSubmission>()
    val saves = mutableListOf<RecipientComposerDraftWrite>()
    val submits = mutableListOf<RecipientComposerSubmissionWrite>()
    val publications = mutableListOf<RecipientComposerEditingState>()
    val storage = Failure(FailureKind.Storage, "Fictional storage interruption")
    var beforeSave: suspend () -> Unit = {}
    var beforeSubmit: suspend () -> Unit = {}
    var saveFailure: Failure? = null
    var submitFailure: Failure? = null
    var mapSaved: (CollaborationSpaceRecipientDraft) -> CollaborationSpaceRecipientDraft = { it }
    var mapSubmitted:
        (CollaborationSpaceRecipientSubmission) -> CollaborationSpaceRecipientSubmission =
        {
            it
        }
    private var serial = 100
    val actor =
        RecipientComposerEditingSession(
            scope,
            access,
            peer,
            save = { request ->
                saves += request
                beforeSave()
                persisted = request.replacement
                saveFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(mapSaved(request.replacement))
            },
            submit = { request ->
                submits += request
                beforeSubmit()
                retained[request.original.id] = request.original
                persisted = request.original.nextDraft
                submitFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(mapSubmitted(request.original))
            },
            onState = { publications += it },
            clock =
                object : Clock {
                    override fun now() = this@RecipientComposerActorFixture.now
                },
            revision = { chatId(++serial) },
        )
}
