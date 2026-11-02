package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdvanceCollaborationIncomingReferences
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class IncomingHistoryAdvanceFixture {
    val local = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            local.setup.session!!.id,
            local.server.audience,
            local.setup.session!!.user.id,
            local.summary!!.conversation.companyId,
        )
    val scope = CollaborationLocalScope.Captured(access)
    val key =
        CollaborationHistoryTraversal(
            local.summary!!.conversation.id,
            "!room:test",
            HistoryReferenceReconciliationFixture.id,
            CollaborationHistoryPurpose.Incoming,
        )
    val source =
        CollaborationHistoryReferences(
            CollaborationHistoryProgress(
                HistoryReferenceReconciliationFixture.id,
                1,
                "provider-next",
                21,
                CollaborationIncomingHistoryPosition("head", null),
            ),
            (0L..19L).map { i ->
                CollaborationHistoryReference(
                    i,
                    CollaborationProviderHistoryEvent(
                        "\$event-$i",
                        "@fictional:test",
                        i,
                        "m.room.encrypted",
                    ),
                )
            },
            "after-19",
        )
    var calls = 0
    var active = 0
    var reject = false
    var failure: Failure? = null
    var saved: CollaborationHistoryReferences? = null
    var retry: Boolean? = null
    var before: suspend () -> Unit = {}
    var after: suspend () -> Unit = {}
    val repository =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
            ): AppResult<Boolean> {
                assertEquals(this@IncomingHistoryAdvanceFixture.access, access)
                assertEquals(key, traversal)
                calls++
                active++
                try {
                    before()
                    if (!reject) {
                        saved = source
                        retry = retryRequired
                    }
                    after()
                    return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(!reject)
                } finally {
                    active--
                }
            }

            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> = error("No extra progress read")

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> = error("No implicit observer")

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> = error("No implicit page acquisition")

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No content acknowledgement")
        }
    val useCase =
        AdvanceCollaborationIncomingReferences(
            repository,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )
}
