package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CollaborationQueueNamespaceWorkerTest {
    @Test
    fun moreThanOneBatchOfRetainedOldWorkCannotStarveANewMessageAfterRestart() = runBlocking {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            val access =
                assertIs<AppResult.Success<CollaborationLocalAccess>>(
                        f.koin.get<ObserveCollaborationAccess>()(f.workspaceId).first()
                    )
                    .value
            val repository = f.koin.get<CollaborationMessageRepository>()
            val oldDevice =
                CollaborationMessageDevice(
                    "10000000-0000-4000-8000-000000000099",
                    f.server.collaboration.deviceId,
                    f.server.collaboration.bootstrapId,
                    f.server.collaboration.roomId(f.first),
                )
            val retained = mutableListOf<CollaborationMessageRecord>()
            repeat(10) { index ->
                val queued = f.queue(text = "Retained original $index")
                val staging =
                    CollaborationMessageState.Staging(
                        oldDevice,
                        emptySet(),
                        CollaborationMessageOrigin(f.sdk.sender, "a".repeat(64)),
                    )
                assertEquals(AppResult.Success(true), repository.update(access, queued, staging))
                val original = queued.copy(revision = queued.revision + 1, state = staging)
                if (index % 2 == 0) retained += original
                else {
                    val staged =
                        CollaborationMessageState.Staged(oldDevice, "old-$index", staging.origin)
                    assertEquals(
                        AppResult.Success(true),
                        repository.update(access, original, staged),
                    )
                    retained += original.copy(revision = original.revision + 1, state = staged)
                }
            }
            val current = f.queue(text = "Current message after older uncertainty")
            f.reopen()
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(listOf(current.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            val remaining =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first).first()
                    )
                    .value
            assertEquals(
                retained.associateBy { it.intent.id },
                remaining.associateBy { it.intent.id },
            )
            val history =
                assertIs<AppResult.Success<CollaborationHistoryPage>>(
                        f.koin.get<ObserveCollaborationHistory>()(f.workspaceId, f.first).first()
                    )
                    .value
            assertEquals(1, history.messages.count { it.logicalId == current.intent.id })
            f.reopen()
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(listOf(current.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
            assertTrue(f.server.unexpectedPaths.isEmpty())
        } finally {
            f.close()
        }
    }
}
