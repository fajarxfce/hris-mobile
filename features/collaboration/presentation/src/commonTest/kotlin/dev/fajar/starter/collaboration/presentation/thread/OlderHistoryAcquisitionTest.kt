package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

/** Exercise the domain owner directly using the same repositories as the actual chat route. */
class OlderHistoryAcquisitionTest {
    @Test
    fun aContinuationCannotSkipMessagesInAReplacementTraversal() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.pages.addAll(metadata(f, 120).chunked(20))
        val acquire = f.createAcquireOlderCollaborationHistory()
        val first =
            assertIs<CollaborationOlderHistoryOutcome.Loaded>(
                assertIs<AppResult.Success<CollaborationOlderHistoryOutcome>>(
                        acquire("selection", key(f), CollaborationOlderHistoryPosition())
                    )
                    .value
            )
        assertNotNull(first.position.after)
        f.past.references = listOf(CollaborationHistoryReference(0, f.past.event(301)))
        f.past.progress = CollaborationHistoryProgress(chatId(61), 0, null, 1)
        val resumed =
            assertIs<CollaborationOlderHistoryOutcome.Loaded>(
                assertIs<AppResult.Success<CollaborationOlderHistoryOutcome>>(
                        acquire("selection", key(f), first.position)
                    )
                    .value
            )
        assertEquals("Older message 301", f.historyRows.value.single().text)
        assertEquals(listOf("\$older-301"), f.past.anchors)
        assertTrue(resumed.exhausted)
    }

    @Test
    fun metadataDiscoveryIsFiniteAndAnExplicitContinuationReachesTheEnd() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.pages.addAll(metadata(f, 300).chunked(20))
        f.past.pages.add(emptyList())
        val acquire = f.createAcquireOlderCollaborationHistory()
        val first =
            assertIs<CollaborationOlderHistoryOutcome.Loaded>(
                assertIs<AppResult.Success<CollaborationOlderHistoryOutcome>>(
                        acquire("selection", key(f), CollaborationOlderHistoryPosition())
                    )
                    .value
            )
        assertFalse(first.exhausted)
        assertEquals(5, f.past.requestedPositions.size)
        assertTrue(f.past.anchors.isEmpty())
        var position = first.position
        var exhausted = false
        repeat(5) {
            if (!exhausted) {
                val result =
                    assertIs<CollaborationOlderHistoryOutcome.Loaded>(
                        assertIs<AppResult.Success<CollaborationOlderHistoryOutcome>>(
                                acquire("selection", key(f), position)
                            )
                            .value
                    )
                position = result.position
                exhausted = result.exhausted
            }
        }
        assertTrue(exhausted)
        assertEquals(16, f.past.requestedPositions.size)
        assertTrue(f.past.anchors.isEmpty())
    }

    @Test
    fun cancellingThePendingProviderReadDoesNotStageOrRetryIt() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.pages.add(metadata(f, 3))
        val entered = CompletableDeferred<Unit>()
        f.past.beforeProvider = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch {
            f.createAcquireOlderCollaborationHistory()(
                "selection",
                key(f),
                CollaborationOlderHistoryPosition(),
            )
            error("Cancelled history must not publish")
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(1, f.past.requestedPositions.size)
        assertEquals(0, f.past.stages)
        assertNull(f.past.progress)
        assertTrue(f.past.anchors.isEmpty())
    }

    @Test
    fun accountLossOrCompanyDenialWhileReadingCannotPublishTheOldPage() = runTest {
        for (deny in listOf(false, true)) {
            val f = ChatThreadFixture().also { it.online() }
            f.past.pages.add(metadata(f, 3))
            f.past.beforeProvider = {
                if (deny) f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
                else f.base.session.value = null
            }
            val result =
                f.createAcquireOlderCollaborationHistory()(
                    "selection",
                    key(f),
                    CollaborationOlderHistoryPosition(),
                )
            assertIs<AppResult.Failed>(result)
            if (deny) assertEquals(f.base.denied, result.failure)
            assertEquals(1, f.past.requestedPositions.size)
            assertEquals(0, f.past.stages)
            assertNull(f.past.progress)
        }
    }

    @Test
    fun changedRoomDuringAcquisitionCannotStageItsOldReferences() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.pages.add(metadata(f, 3))
        f.past.beforeProvider = { f.room = f.room.copy(matrixRoomId = "!other:example.test") }
        assertIs<AppResult.Failed>(
            f.createAcquireOlderCollaborationHistory()(
                "selection",
                key(f),
                CollaborationOlderHistoryPosition(),
            )
        )
        assertEquals(1, f.past.requestedPositions.size)
        assertEquals(0, f.past.stages)
    }

    @Test
    fun aFailedAtomicStageDoesNotFetchAnotherPageOrClaimExhaustion() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.pages.add(metadata(f, 3))
        f.past.stageFailure = Failure(FailureKind.Storage, "Fixture stage interruption")
        assertEquals(
            AppResult.Failed(f.past.stageFailure!!),
            f.createAcquireOlderCollaborationHistory()(
                "selection",
                key(f),
                CollaborationOlderHistoryPosition(),
            ),
        )
        assertEquals(1, f.past.stages)
        assertEquals(1, f.past.requestedPositions.size)
        assertNull(f.past.progress)
    }

    @Test
    fun replacingTheTraversalDuringTheReadCannotPublishThePreviousEnd() = runTest {
        val f = ChatThreadFixture().also { it.online() }
        f.past.references =
            metadata(f, 2).mapIndexed { i, event ->
                CollaborationHistoryReference(i.toLong(), event)
            }
        f.past.progress = CollaborationHistoryProgress(chatId(60), 0, null, 2)
        var reads = 0
        f.past.beforeReferences = {
            if (++reads == 2)
                f.past.progress = requireNotNull(f.past.progress).copy(generation = chatId(61))
        }
        val result =
            assertIs<CollaborationOlderHistoryOutcome.Loaded>(
                assertIs<AppResult.Success<CollaborationOlderHistoryOutcome>>(
                        f.createAcquireOlderCollaborationHistory()(
                            "selection",
                            key(f),
                            CollaborationOlderHistoryPosition(),
                        )
                    )
                    .value
            )
        assertFalse(result.exhausted)
        assertTrue(f.past.requestedPositions.isEmpty())
        assertTrue(f.past.anchors.isEmpty())
    }

    private fun key(f: ChatThreadFixture) =
        CollaborationHistoryTraversal(f.conversationId, f.source.roomId, f.source.deviceId)

    private fun metadata(f: ChatThreadFixture, count: Int) =
        (1..count).map { f.past.event(it, readable = false).copy(type = "m.room.member") }
}
