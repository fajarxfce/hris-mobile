package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryTraversalRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.koin.dsl.module

/** Actual app/DI/SQLite, with explicit finite fictional provider bounds and original windows. */
class CollaborationIncomingHistoryRecoveryTest {
    @Test
    fun aGapBeyondTheOriginalWindowRecoversAcrossReopenWithoutMovingForegroundHistory() =
        runBlocking {
            withFixture { f ->
                val events =
                    (1..175).map { f.sdk.incoming(f.first, "Fictional delayed message $it") }
                f.sdk.historyWindowLimit = 21
                val owner = f.incomingGrant().owner
                val browse = key(f).copy(purpose = CollaborationHistoryPurpose.Browse)
                val repository = f.koin.get<CollaborationHistoryTraversalRepository>()
                assertEquals(
                    AppResult.Success(true),
                    repository.stage(
                        owner.localListAccess(),
                        browse,
                        null,
                        CollaborationProviderHistoryPage(
                            owner,
                            browse.deviceId,
                            browse.roomId,
                            null,
                            "browse-head",
                            "browse-next",
                            emptyList(),
                        ),
                    ),
                )
                val before = repository.progress(owner.localListAccess(), browse)
                f.reopen()
                f.withoutIncomingSelection()
                assertIs<SyncResult.Retry>(turn(f))
                assertNotNull(f.incomingOriginal(events.last()))
                assertNull(f.incomingOriginal(events.first()))
                f.reopen()
                f.withoutIncomingSelection()
                finish(f)
                for (event in events) assertEquals(event.text, f.incomingOriginal(event)?.text)
                assertEquals(
                    before,
                    f.koin
                        .get<CollaborationHistoryTraversalRepository>()
                        .progress(owner.localListAccess(), browse),
                )
                assertTrue(f.sdk.providerBounds.any { it.first != null })
                assertTrue(f.sdk.providerBounds.any { it.second != null })
                f.reopen()
                f.withoutIncomingSelection()
                assertEquals(events.first().text, f.incomingOriginal(events.first())?.text)
                released(f)
            }
        }

    @Test
    fun aMissingKeyDoesNotHideLaterMessagesOrGetForgottenAtTheEndOfAPendingPass() = runBlocking {
        withFixture { f ->
            val events = (1..75).map { f.sdk.incoming(f.first, "Fictional key recovery $it") }
            f.sdk.historyWindowLimit = 21
            f.sdk.withheldHistory += events.first().eventId
            var pending = false
            for (i in 1..90) {
                val result = turn(f)
                assertNotEquals(SyncResult.Complete, result)
                if (result is SyncResult.Retry && result.failure != null) {
                    pending = true
                    break
                }
            }
            assertTrue(pending)
            assertNull(f.incomingOriginal(events.first()))
            for (event in events.drop(1)) assertEquals(event.text, f.incomingOriginal(event)?.text)
            val later = (1..10).map { f.sdk.incoming(f.first, "Fictional newer message $it") }
            f.reopen()
            f.withoutIncomingSelection()
            repeat(9) { assertNotEquals(SyncResult.Complete, turn(f)) }
            for (event in later) assertEquals(event.text, f.incomingOriginal(event)?.text)
            f.sdk.withheldHistory.clear()
            finish(f)
            assertEquals(events.first().text, f.incomingOriginal(events.first())?.text)
            released(f)
        }
    }

    @Test
    fun interruptionAfterMetadataCommitResumesTheRetainedWaveAcrossContainerReopen() = runBlocking {
        withFixture { f ->
            val events = (1..65).map { f.sdk.incoming(f.first, "Fictional interrupted wave $it") }
            f.sdk.historyWindowLimit = 21
            val stored = f.koin.get<CollaborationHistoryTraversalRepository>()
            val entered = CompletableDeferred<Unit>()
            val held =
                object : CollaborationHistoryTraversalRepository by stored {
                    override suspend fun stage(
                        access: CollaborationLocalAccess,
                        traversal: CollaborationHistoryTraversal,
                        expected: CollaborationHistoryProgress?,
                        page: CollaborationProviderHistoryPage,
                        origin: CollaborationHistoryPageOrigin,
                    ): AppResult<Boolean> {
                        val result = stored.stage(access, traversal, expected, page, origin)
                        if (traversal.purpose == CollaborationHistoryPurpose.Incoming) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                        return result
                    }
                }
            f.koin.loadModules(
                listOf(module { single<CollaborationHistoryTraversalRepository> { held } })
            )
            val task = async { turn(f) }
            withTimeout(5.seconds) { entered.await() }
            task.cancelAndJoin()
            val committed =
                assertIs<AppResult.Success<CollaborationHistoryProgress?>>(
                        stored.progress(f.incomingGrant().owner.localListAccess(), key(f))
                    )
                    .value!!
            assertNotNull(committed.next)
            f.reopen()
            f.withoutIncomingSelection()
            finish(f)
            assertTrue(
                f.sdk.providerBounds.any {
                    it.first == committed.next && it.second == committed.incoming!!.until
                }
            )
            for (event in events) assertEquals(event.text, f.incomingOriginal(event)?.text)
            released(f)
        }
    }

    private suspend fun turn(f: CollaborationWorkerFixture) =
        f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant())

    private suspend fun finish(f: CollaborationWorkerFixture) {
        repeat(90) { if (turn(f) == SyncResult.Complete) return }
        fail("Incoming recovery did not finish within the fixture's finite turn bound")
    }

    private fun key(f: CollaborationWorkerFixture) =
        CollaborationHistoryTraversal(
            f.first,
            f.server.collaboration.roomId(f.first),
            f.server.collaboration.deviceId,
            CollaborationHistoryPurpose.Incoming,
        )

    private fun released(f: CollaborationWorkerFixture) {
        assertEquals(f.sdk.backgroundAcquisitions, f.sdk.backgroundCloses)
        assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        assertEquals(0, f.sdk.backgroundInterruptions.subscriptionCount.value)
        assertEquals(0, f.sdk.backgroundCredentialFailures.subscriptionCount.value)
        assertTrue(f.sdk.enqueued.isEmpty())
        assertEquals(0, f.sdk.deliveries)
    }

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            block(f)
        } finally {
            f.close()
        }
    }
}
