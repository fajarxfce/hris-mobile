package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

/** Observable repository fake shared only by composer policy tests. */
internal class ComposerOutboxTestStore : CollaborationComposerOutboxRepository {
    val entries = MutableStateFlow(emptyList<CollaborationComposerOutboxEntry>())
    var active = 0
    var starts = 0
    var removals = 0
    var beforeRemove: suspend () -> Unit = {}
    var removeFailure: Failure? = null

    override fun observe(
        access: CollaborationLocalAccess
    ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> = flow {
        starts++
        active++
        try {
            emitAll(entries.map { AppResult.Success(it) })
        } finally {
            active--
        }
    }

    override suspend fun get(access: CollaborationLocalAccess, id: String) =
        AppResult.Success(entries.value.firstOrNull { it.submission.id == id }?.submission)

    override suspend fun remove(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean> {
        beforeRemove()
        removeFailure?.let {
            return AppResult.Failed(it)
        }
        val found =
            entries.value.firstOrNull { it.submission.id == expected.id }
                ?: return AppResult.Success(false)
        if (found.submission != expected) return AppResult.Success(false)
        entries.value -= found
        removals++
        return AppResult.Success(true)
    }

    override suspend fun handoff(
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
    ): AppResult<CollaborationComposerHandoffResult> =
        error("No handoff in observation/cancellation tests")

    override suspend fun hold(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean> = error("No hold")

    override suspend fun pending(
        access: CollaborationLocalAccess,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationComposerSubmissionPage> = error("No scan")
}
