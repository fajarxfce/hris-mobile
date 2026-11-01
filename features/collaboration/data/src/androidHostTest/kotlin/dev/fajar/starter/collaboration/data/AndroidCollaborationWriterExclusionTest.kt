package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationMessageQueues
import dev.fajar.starter.collaboration.data.repositories.SdkChildResources
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationWriterExclusionTest {
    @Test
    fun preparedContentBindsTheOwnedSenderAndCannotBeSubstitutedBeforeEnqueue() = runTest {
        val f = WriterExclusionFixture(backgroundScope)
        val writer =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    f.queues.acquire("!room:test")
                )
                .value
        val id = "00000000-0000-4000-8000-000000000004"
        val text = " literal **text** 🙂 "
        val origin =
            assertIs<
                    AppResult.Success<
                        dev.fajar.starter.collaboration.domain.entities.CollaborationMessageOrigin
                    >
                >(
                    writer.prepareText(id, text)
                )
                .value
        assertEquals("@fixture:example.test", origin.sender)
        val before = f.room.calls.toList()
        for (changed in
            listOf(
                origin.copy(sender = "@other:example.test"),
                origin.copy(contentHash = "b".repeat(64)),
            )) {
            assertEquals(
                "client.collaboration_message_content_changed",
                assertIs<AppResult.Failed>(writer.enqueue(id, text, changed)).failure.code,
            )
        }
        assertEquals(
            "client.collaboration_message_content_changed",
            assertIs<AppResult.Failed>(writer.enqueue(id, "different", origin)).failure.code,
        )
        assertEquals(before, f.room.calls)
        assertIs<AppResult.Success<*>>(writer.close())
        assertIs<AppResult.Failed>(writer.prepareText(id, text))
        assertIs<AppResult.Success<*>>(f.children.close())
    }

    @Test
    fun anActiveWriterBlocksReopeningUntilItsOwnerClosesIt() = runTest {
        val f = WriterExclusionFixture(backgroundScope)
        val writer =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    f.queues.acquire("!room:test")
                )
                .value
        val before = f.room.calls.toList()
        assertBusy(f.queues.whileWriterIdle<Unit> { error("Must not close an active writer") })
        assertEquals(before, f.room.calls)
        assertIs<AppResult.Success<*>>(writer.close())
        assertEquals(
            AppResult.Success("idle"),
            f.queues.whileWriterIdle { AppResult.Success("idle") },
        )
        assertIs<AppResult.Success<*>>(f.children.close())
    }

    @Test
    fun aReopeningReservationBlocksBothAnotherReopenAndANewQueue() = runTest {
        val f = WriterExclusionFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recovery = async {
            f.queues.whileWriterIdle {
                entered.complete(Unit)
                release.await()
                AppResult.Success(Unit)
            }
        }
        entered.await()
        assertBusy(f.queues.acquire("!room:test"))
        assertBusy(f.queues.whileWriterIdle<Unit> { error("Must not overlap") })
        assertTrue(f.room.calls.isEmpty())
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(recovery.await())
        val writer =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    f.queues.acquire("!room:test")
                )
                .value
        assertIs<AppResult.Success<*>>(writer.close())
        assertIs<AppResult.Success<*>>(f.children.close())
    }

    @Test
    fun cancellationAndThrownFailureReleaseOnlyTheIdleReservation() = runTest {
        val f = WriterExclusionFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val recovery = launch {
            f.queues.whileWriterIdle<Unit> {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        recovery.cancelAndJoin()
        assertFailsWith<IllegalStateException> {
            f.queues.whileWriterIdle<Unit> { error("Fixture interrupted") }
        }
        val writer =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    f.queues.acquire("!room:test")
                )
                .value
        assertBusy(f.queues.whileWriterIdle { AppResult.Success(Unit) })
        assertIs<AppResult.Success<*>>(writer.close())
        assertIs<AppResult.Success<*>>(f.children.close())
    }

    @Test
    fun anUnfinishedQueueListenerRetainsExclusionUntilExplicitCleanupSucceeds() = runTest {
        val f = WriterExclusionFixture(backgroundScope)
        val writer =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    f.queues.acquire("!room:test")
                )
                .value
        f.room.task.finished = false
        assertIs<AppResult.Failed>(writer.snapshot())
        assertIs<AppResult.Failed>(writer.close())
        // The first bounded wait may finish before the owned cleanup attempt.
        // A following explicit close observes that attempt's completed failure.
        runCurrent()
        assertIs<AppResult.Failed>(writer.close())
        assertBusy(f.queues.whileWriterIdle<Unit> { error("Unfinished queue is still owned") })
        assertFalse("room.close" in f.room.calls)
        f.room.task.finished = true
        assertIs<AppResult.Success<*>>(writer.close())
        assertEquals(1, f.room.calls.count { it == "room.close" })
        assertIs<AppResult.Success<*>>(f.queues.whileWriterIdle { AppResult.Success(Unit) })
        assertIs<AppResult.Success<*>>(f.children.close())
    }

    private fun assertBusy(result: AppResult<*>) {
        assertEquals(
            "client.collaboration_queue_busy",
            assertIs<AppResult.Failed>(result).failure.code,
        )
    }
}

private class WriterExclusionFixture(lifetime: CoroutineScope) {
    val room = QueueRoom()
    val children = SdkChildResources(lifetime)
    val queues =
        AndroidCollaborationMessageQueues(
            CollaborationOwner(
                "local",
                "https://example.test",
                "account",
                "company",
                "00000000-0000-4000-8000-000000000001",
            ),
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003",
            "@fixture:example.test",
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room = room
            },
            lifetime,
            children,
            onInterrupted = {},
            failure = { null },
        )
}
