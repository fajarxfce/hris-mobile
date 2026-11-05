package dev.fajar.starter

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class CollaborationMetadataSyncIntegrationTest {
    @Test
    fun nativeFeedAndEncryptedReplicaResumeAcrossColdReopenAndPreserveOfflineRows() = metadataTest {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.reads.clear()
            val sync = f.koin.get<SyncCollaborationList>()
            assertTrue(
                assertIs<AppResult.Success<CollaborationListSyncOutcome>>(sync(f.workspaceId))
                    .value
                    .caughtUp
            )
            assertEquals(
                listOf("bootstrap", "changes"),
                f.server.collaboration.reads.map { it.substringAfterLast('/') },
            )
            val before = list(f)
            assertEquals(3, before.items.size)
            assertEquals(2, before.checkpoint!!.format)
            f.reopen()
            f.server.collaboration.reads.clear()
            assertEquals(before, list(f))
            assertTrue(
                assertIs<AppResult.Success<CollaborationListSyncOutcome>>(
                        f.koin.get<SyncCollaborationList>()(f.workspaceId)
                    )
                    .value
                    .caughtUp
            )
            assertEquals(
                listOf("changes"),
                f.server.collaboration.reads.map { it.substringAfterLast('/') },
            )
            f.server.collaboration.failureCode = "fixture_offline"
            val retained = list(f)
            assertIs<AppResult.Failed>(f.koin.get<SyncCollaborationList>()(f.workspaceId))
            assertEquals(retained, list(f))
            f.reopen()
            assertEquals(retained, list(f))
        } finally {
            f.close()
        }
    }

    @Test
    fun nativeChangesRenameDeleteAndRestoreWithoutRefetchingTheDirectory() = metadataTest {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            assertIs<AppResult.Success<*>>(f.koin.get<SyncCollaborationList>()(f.workspaceId))
            f.server.collaboration.reads.clear()
            f.server.collaboration.summaryUpdates +=
                CollaborationSummaryChangeDto(
                    1,
                    f.server.collaboration.summaryFact(f.first, 2, "Updated colleague"),
                )
            assertIs<AppResult.Success<*>>(f.koin.get<SyncCollaborationList>()(f.workspaceId))
            assertEquals(
                "Updated colleague",
                list(f).items.single { it.conversation.id == f.first }.peer.displayName,
            )
            f.server.collaboration.summaryUpdates +=
                CollaborationSummaryChangeDto(2, CollaborationSummaryFactDto(f.first, 3, null))
            assertIs<AppResult.Success<*>>(f.koin.get<SyncCollaborationList>()(f.workspaceId))
            assertTrue(list(f).items.none { it.conversation.id == f.first })
            f.reopen()
            assertTrue(list(f).items.none { it.conversation.id == f.first })
            f.server.collaboration.summaryUpdates +=
                CollaborationSummaryChangeDto(
                    3,
                    f.server.collaboration.summaryFact(f.first, 4, "Restored colleague"),
                )
            assertIs<AppResult.Success<*>>(f.koin.get<SyncCollaborationList>()(f.workspaceId))
            assertEquals(
                "Restored colleague",
                list(f).items.single { it.conversation.id == f.first }.peer.displayName,
            )
            assertEquals(
                listOf("changes", "changes", "changes"),
                f.server.collaboration.reads.map { it.substringAfterLast('/') },
            )
        } finally {
            f.close()
        }
    }

    private suspend fun list(f: CollaborationWorkerFixture) =
        assertIs<AppResult.Success<CollaborationLocalList>>(
                f.koin.get<ObserveCollaborationList>()(
                        f.workspaceId,
                        flowOf(CollaborationListSelection()),
                    )
                    .first()
            )
            .value
}

private fun metadataTest(block: suspend () -> Unit) = runTest {
    // Real Room/network I/O must not race the test scheduler advancing a thirty-second deadline.
    withContext(Dispatchers.Default) { block() }
}
