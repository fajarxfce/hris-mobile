package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceRecipientTransferWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

internal class SpaceRecipientTransferWorkerFixture(account: Int = 1) {
    val access =
        CollaborationSpaceLocalAccess(
            "recipient-session-$account",
            "https://hris.example.test/",
            id(account),
            id(2),
        )
    val sourceFacts =
        MutableStateFlow<AppResult<CollaborationSpaceRecipientOutboxVersion?>>(
            AppResult.Success(null)
        )
    val catalogFacts =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(CollaborationSpaceConversationCatalogVersion(id(9001), 0))
        )
    val destinationFacts =
        MutableStateFlow<AppResult<CollaborationSpaceInboxVersion?>>(
            AppResult.Success(CollaborationSpaceInboxVersion(id(9002), 0, 0))
        )
    val rows = linkedMapOf<String, CollaborationSpaceRecipientSubmission>()
    val pages = mutableListOf<String?>()
    val calls = mutableListOf<String>()
    val failure = Failure(FailureKind.Storage, "Storage unavailable")
    var sourceStarts = 0
    var sourceStops = 0
    var catalogStarts = 0
    var catalogStops = 0
    var destinationStarts = 0
    var destinationStops = 0
    var sourceRevision = -1L
    var beforeSource: suspend () -> Unit = {}
    var onPage: (suspend (String?, Int) -> AppResult<CollaborationSpaceRecipientSubmissionPage>)? =
        null
    var onTransfer:
        suspend (CollaborationSpaceRecipientSubmission) -> AppResult<
                CollaborationSpaceRecipientTransfer
            > =
        {
            complete(it)
        }

    fun id(n: Int) = "c9000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    fun message(n: Int) =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                id(3),
                id(100 + n),
                "Message $n",
                Instant.fromEpochMilliseconds(1_793_491_200_000L + n),
            ),
            id(1100 + n),
            Instant.fromEpochMilliseconds(1_793_491_200_000L + n),
        )

    fun add(n: Int): CollaborationSpaceRecipientSubmission =
        message(n).also {
            rows[it.id] = it
            sourceChanged()
        }

    fun sourceChanged() {
        sourceFacts.value =
            AppResult.Success(CollaborationSpaceRecipientOutboxVersion(id(9000), ++sourceRevision))
    }

    fun catalogChanged() {
        val current = (catalogFacts.value as AppResult.Success).value!!
        catalogFacts.value = AppResult.Success(current.copy(revision = current.revision + 1))
    }

    fun destinationChanged() {
        val current = (destinationFacts.value as AppResult.Success).value!!
        destinationFacts.value = AppResult.Success(current.copy(revision = current.revision + 1))
    }

    fun complete(
        value: CollaborationSpaceRecipientSubmission
    ): AppResult<CollaborationSpaceRecipientTransfer> {
        if (rows.remove(value.id) != null) sourceChanged()
        return AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(id(90)))
    }

    fun worker() =
        CollaborationSpaceRecipientTransferWorker(
            source = { owner ->
                flow {
                    assertEquals(access, owner)
                    sourceStarts++
                    try {
                        beforeSource()
                        emitAll(sourceFacts)
                    } finally {
                        sourceStops++
                    }
                }
            },
            catalog = { owner ->
                flow {
                    assertEquals(access, owner)
                    catalogStarts++
                    try {
                        emitAll(catalogFacts)
                    } finally {
                        catalogStops++
                    }
                }
            },
            destination = { owner ->
                flow {
                    assertEquals(access, owner)
                    destinationStarts++
                    try {
                        emitAll(destinationFacts)
                    } finally {
                        destinationStops++
                    }
                }
            },
            readPage = { owner, after, limit ->
                assertEquals(access, owner)
                assertEquals(8, limit)
                pages += after
                onPage?.invoke(after, limit)
                    ?: run {
                        val values =
                            rows.values
                                .filter { after == null || it.id > after }
                                .sortedBy { it.id }
                                .take(limit + 1)
                        val selected = values.take(limit)
                        AppResult.Success(
                            CollaborationSpaceRecipientSubmissionPage(
                                selected,
                                if (values.size > limit) selected.last().id else null,
                            )
                        )
                    }
            },
            transfer = { owner, submission ->
                assertEquals(access, owner)
                calls += submission.id
                onTransfer(submission)
            },
        )
}
