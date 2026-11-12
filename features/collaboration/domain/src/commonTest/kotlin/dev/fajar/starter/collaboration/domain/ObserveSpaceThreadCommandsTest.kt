@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceThreadCommandsTest {
    @Test
    fun aKnownCommandFailureDoesNotWaitForAnotherCommandStore() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        f.commands.spaceFacts.value = AppResult.Failed(f.base.storageFailure)
        f.commands.readOriginal = { flow { awaitCancellation() } }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadSnapshot>>(
                AppResult.Failed(f.base.storageFailure)
            ),
            values,
        )
        job.cancelAndJoin()
    }

    @Test
    fun originalAndCanonicalCommandsWithTheSameIdRemainDistinct() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val canonical = f.commands.spaceRecord()
        val original = f.commands.originalRecord()
        assertEquals(canonical.intent.id, original.intent.id)
        f.commands.spaceFacts.value = AppResult.Success(listOf(canonical))
        f.commands.originalFacts.value = AppResult.Success(listOf(original))
        val snapshot =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observe()(f.access, f.id).first()
                )
                .value
        assertEquals(listOf(canonical), snapshot.spaceCommands)
        assertEquals(listOf(original), snapshot.originalCommands)
        assertEquals(f.page(), snapshot.history.page)
        assertEquals(f.id, snapshot.history.conversationId)
        assertEquals(f.room.position.conversationId, original.intent.conversationId)
        assertEquals(1, f.commands.spaceStarts)
        assertEquals(1, f.commands.originalStarts)
    }

    @Test
    fun spaceRoomReadsCanonicalCommandsWithoutTouchingCompanyMessages() = runTest {
        val f = SpaceThreadHistoryFixture()
        val command = f.commands.spaceRecord()
        f.commands.spaceFacts.value = AppResult.Success(listOf(command))
        f.commands.readOriginal = { error("Space room must not read a Company ledger") }
        val snapshot =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observe()(f.access, f.id).first()
                )
                .value
        assertEquals(listOf(command), snapshot.spaceCommands)
        assertTrue(snapshot.originalCommands.isEmpty())
        assertEquals(0, f.commands.originalStarts)
        assertEquals(0, f.originalReads)
    }

    @Test
    fun pendingAndSentCommandChangesPublishWithoutAnotherHistoryRead() = runTest {
        val f = SpaceThreadHistoryFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        val queued = f.commands.spaceRecord()
        f.commands.spaceFacts.value = AppResult.Success(listOf(queued))
        runCurrent()
        val sent =
            queued.copy(
                revision = 1,
                state =
                    CollaborationMessageState.Sent(
                        f.commands.device(),
                        "transaction",
                        "\$sent",
                        CollaborationMessageOrigin("@self:matrix.example.test", "a".repeat(64)),
                    ),
            )
        f.commands.spaceFacts.value = AppResult.Success(listOf(sent))
        runCurrent()
        assertEquals(3, values.size)
        val snapshots =
            values.map {
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(it).value
            }
        assertEquals(
            listOf(emptyList(), listOf(queued), listOf(sent)),
            snapshots.map { it.spaceCommands },
        )
        assertTrue(snapshots.all { it.history.page == f.page() })
        assertEquals(1, f.spaceReads)
        assertEquals(1, f.commands.spaceStarts)
        job.cancelAndJoin()
    }

    @Test
    fun canonicalStagingIsFrozenWhileTheOriginalStoreIsStillReading() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val before = mutableSetOf("previous")
        val records =
            mutableListOf(
                f.commands
                    .spaceRecord()
                    .copy(
                        revision = 1,
                        state =
                            CollaborationMessageState.Staging(
                                f.commands.device(),
                                before,
                                CollaborationMessageOrigin(
                                    "@self:matrix.example.test",
                                    "a".repeat(64),
                                ),
                            ),
                    )
            )
        f.commands.spaceFacts.value = AppResult.Success(records)
        f.commands.readOriginal = {
            flow {
                entered.complete(Unit)
                release.await()
                emit(AppResult.Success(emptyList()))
            }
        }
        val result = async { f.observe()(f.access, f.id).first() }
        entered.await()
        runCurrent()
        before += "changed"
        records.clear()
        release.complete(Unit)
        val snapshot =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(result.await())
                .value
        assertEquals(
            setOf("previous"),
            assertIs<CollaborationMessageState.Staging>(snapshot.spaceCommands.single().state)
                .before,
        )
    }

    @Test
    fun aFailedCommandReadIsNotPublishedAsAnEmptyQueue() = runTest {
        for (canonical in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(true)
            if (canonical) f.commands.spaceFacts.value = AppResult.Failed(f.base.storageFailure)
            else f.commands.originalFacts.value = AppResult.Failed(f.base.storageFailure)
            assertEquals(
                AppResult.Failed(f.base.storageFailure),
                f.observe()(f.access, f.id).first(),
            )
            assertEquals(1, f.commands.spaceStops)
            assertEquals(1, f.commands.originalStops)
            assertEquals(1, f.stops)
        }
    }
}
