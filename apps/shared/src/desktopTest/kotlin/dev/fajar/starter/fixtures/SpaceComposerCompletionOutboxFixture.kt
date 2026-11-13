package dev.fajar.starter.fixtures

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceComposerOutboxRepository
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

/** Read-only completion boundary. A finish operation cannot author, hold or retire submissions. */
internal class SpaceComposerCompletionOutboxFixture(
    private val owner: CollaborationSpaceLocalAccess
) {
    val submissions = linkedMapOf<String, CollaborationComposerSubmission>()
    val held = mutableSetOf<String>()
    var reads = 0
    var beforePending: suspend () -> Unit = {}
    var failure: Failure? = null
    var response: CollaborationComposerSubmissionPage? = null
    val repository =
        object : CollaborationSpaceComposerOutboxRepository {
            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> {
                assertEquals(owner, access)
                assertEquals(1, limit)
                assertEquals(null, after)
                reads++
                val snapshot = submissions.values.filter { it.id !in held }.sortedBy { it.id }
                beforePending()
                return failure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(
                        response
                            ?: CollaborationComposerSubmissionPage(
                                snapshot.take(limit),
                                if (snapshot.size > limit) snapshot[limit - 1].id else null,
                            )
                    )
            }

            override suspend fun handoff(
                access: CollaborationSpaceLocalAccess,
                submission: CollaborationComposerSubmission,
                expected: CollaborationDraft?,
            ): AppResult<CollaborationComposerHandoffResult> =
                error("Completion cannot change the editor")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationComposerSubmission?> =
                error("Completion uses one indexed page")

            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> =
                error("Completion has no collector")

            override suspend fun hold(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("Completion cannot hold originals")

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("Completion cannot retire originals")
        }
}
