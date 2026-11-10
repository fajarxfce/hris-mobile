@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpacePendingMessages
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ReadCollaborationSpacePendingMessagesTest {
    @Test
    fun completeInventoryIncludesUnboundConversationsWithoutTransportOrSelectedCompany() = runTest {
        val f = Fixture()
        f.base.native.setup.now += 1000.days
        f.base.native.setup.workspace = null
        f.base.canonical = null
        f.rows =
            listOf(
                f.record(1),
                f.record(2).let { it.copy(intent = it.intent.copy(conversationId = f.id(99))) },
            )
        assertEquals(f.rows, f.pending())
        assertEquals(1, f.base.observations)
        assertEquals(1, f.base.stops)
        f.noSideEffects()
    }

    @Test
    fun emptyAccountIsCompleteWithoutConsultingRoomMetadata() = runTest {
        val f = Fixture()
        assertTrue(f.pending().isEmpty())
        assertEquals(1, f.base.observations)
        f.noSideEffects()
    }

    @Test
    fun all128PendingCommandsAreReturnedRatherThanAVisiblePage() = runTest {
        val f = Fixture()
        f.rows = (1..128).map(f::record)
        assertEquals(f.rows, f.pending())
        f.noSideEffects()
    }

    @Test
    fun queuedStagingAndStagedRemainPendingWhileValidTerminalFactsDoNot() = runTest {
        val f = Fixture()
        val staged = f.staged
        val queued = f.record(1)
        val staging =
            f.record(2)
                .copy(
                    revision = 1,
                    state =
                        CollaborationMessageState.Staging(
                            staged.device,
                            setOf("prior"),
                            staged.origin,
                        ),
                )
        val mapped = f.record(3).copy(revision = 2, state = staged)
        val sent =
            f.record(4)
                .copy(
                    revision = 3,
                    state =
                        CollaborationMessageState.Sent(
                            staged.device,
                            staged.transactionId,
                            "\$accepted",
                            staged.origin,
                        ),
                )
        val cancelled = f.record(5).copy(state = CollaborationMessageState.Cancelled)
        f.rows = listOf(sent, queued, cancelled, mapped, staging)
        assertEquals(listOf(queued, staging, mapped), f.pending())
        assertEquals(sent, f.rows.first())
        f.noSideEffects()
    }

    @Test
    fun createdTimeAndOriginalIdGiveStableOrderingAcrossStorageEmissions() = runTest {
        val f = Fixture()
        val first = f.record(1)
        val sameTime =
            f.record(2).copy(intent = f.record(2).intent.copy(createdAt = first.intent.createdAt))
        val earlier =
            f.record(3)
                .copy(
                    intent = f.record(3).intent.copy(createdAt = first.intent.createdAt - 1.seconds)
                )
        f.rows = listOf(sameTime, first, earlier)
        val expected = listOf(earlier, first, sameTime)
        assertEquals(expected, f.pending())
        f.rows = f.rows.reversed()
        assertEquals(expected, f.pending())
    }

    @Test
    fun invalidTerminalRecordCannotDisappearThroughThePendingFilter() = runTest {
        val f = Fixture()
        f.rows = listOf(f.record(1).copy(revision = 1, state = CollaborationMessageState.Cancelled))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.noSideEffects()
    }

    @Test
    fun duplicateIdsOrOverflowNeverBecomeAClaimOfCompleteInventory() = runTest {
        for (duplicate in listOf(false, true)) {
            val f = Fixture()
            f.rows = if (duplicate) listOf(f.record(1), f.record(1)) else (1..129).map(f::record)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
            f.noSideEffects()
        }
    }

    @Test
    fun foreignAccountSpaceOrAudienceInAnyRowInvalidatesTheWholeCapture() = runTest {
        for (dimension in 0..2) {
            val f = Fixture()
            val record = f.record(2)
            val owner = record.intent.owner
            val foreign =
                when (dimension) {
                    0 -> owner.copy(accountId = f.id(99))
                    1 -> owner.copy(spaceId = f.id(99))
                    else -> owner.copy(audience = "https://foreign.example.test/")
                }
            f.rows = listOf(f.record(1), record.copy(intent = record.intent.copy(owner = foreign)))
            assertIs<AppResult.Failed>(f.read())
            f.noSideEffects()
        }
    }

    @Test
    fun malformedStagingBaselineIsRejectedBeforeItCanReachAWorker() = runTest {
        for (before in listOf(setOf("bad transaction"), (1..129).map { "tx-$it" }.toSet())) {
            val f = Fixture()
            f.rows =
                listOf(
                    f.record(1)
                        .copy(
                            revision = 1,
                            state =
                                CollaborationMessageState.Staging(
                                    f.staged.device,
                                    before,
                                    f.staged.origin,
                                ),
                        )
                )
            assertIs<AppResult.Failed>(f.read())
            f.noSideEffects()
        }
    }

    @Test
    fun captureDetachesBothTheRecordListAndStagingBaselineBeforePostReadAdmission() = runTest {
        val f = Fixture()
        val before = mutableSetOf("original-baseline")
        val record =
            f.record(1)
                .copy(
                    revision = 1,
                    state =
                        CollaborationMessageState.Staging(f.staged.device, before, f.staged.origin),
                )
        val raw = mutableListOf(record)
        f.rows = raw
        f.beforeSession = {
            if (f.base.observations == 1) {
                before.clear()
                raw.clear()
            }
        }
        val captured = f.pending().single()
        assertEquals(record.intent, captured.intent)
        assertEquals(
            setOf("original-baseline"),
            assertIs<CollaborationMessageState.Staging>(captured.state).before,
        )
        f.noSideEffects()
    }

    @Test
    fun denialBeforeReadDoesNotSubscribeToCommandStorage() = runTest {
        val f = Fixture()
        f.base.base.deny()
        assertEquals(f.base.base.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertEquals(0, f.base.observations)
        f.noSideEffects()
    }

    @Test
    fun denialObservedAfterStoragePreventsPublishingEvenAnEmptyInventory() = runTest {
        val f = Fixture()
        f.base.base.beforeAdmission = { if (f.base.observations == 1) f.base.base.deny() }
        assertEquals(f.base.base.denied, assertIs<AppResult.Failed>(f.read()).failure)
        f.noSideEffects()
    }

    @Test
    fun replacingTheLocalSessionDuringStoragePreventsPublication() = runTest {
        val f = Fixture()
        f.beforeRead = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.noSideEffects()
    }

    @Test
    fun changedNativeParentOrAudienceIsRejectedBeforeReadingCommands() = runTest {
        for (foreignAudience in listOf(false, true)) {
            val f = Fixture()
            val original = f.base.room.owner
            val parent =
                if (foreignAudience)
                    original.parent.copy(audience = "https://foreign.example.test/")
                else original.parent.copy(nativeSessionId = f.id(99))
            assertIs<AppResult.Failed>(f.useCase(original.copy(parent = parent)))
            assertEquals(0, f.base.observations)
            f.noSideEffects()
        }
    }

    @Test
    fun storageAndAdmissionFailuresArePropagatedWithoutRemoteFallback() = runTest {
        for (duringStorage in listOf(false, true)) {
            val f = Fixture()
            if (duringStorage) f.readFailure = f.base.base.storageFailure
            else f.base.base.gate.value = AppResult.Failed(f.base.base.storageFailure)
            assertEquals(f.base.base.storageFailure, assertIs<AppResult.Failed>(f.read()).failure)
            f.noSideEffects()
        }
    }

    @Test
    fun emptyFlowCannotBeConfusedWithAnEmptyInventory() = runTest {
        val f = Fixture()
        f.base.readFacts = { emptyFlow() }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.noSideEffects()
    }

    @Test
    fun cancellationStopsTheOwnedObservationWithoutPublishingPartialWork() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.read() }
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, f.base.stops)
        f.noSideEffects()
    }

    @Test
    fun boundedReadTimesOutRatherThanTreatingUnavailableStorageAsIdle() = runTest {
        val f = Fixture()
        f.beforeRead = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertEquals(1, f.base.stops)
        f.noSideEffects()
    }

    private class Fixture {
        val base = SpaceMessageUseCaseFixture()
        val staged =
            MixedOriginalQueueReadFixture().record.state as CollaborationMessageState.Staged
        var rows = emptyList<CollaborationSpaceMessageRecord>()
        var readFailure: Failure? = null
        var beforeRead: suspend () -> Unit = {}
        var beforeSession: suspend () -> Unit = {}
        private val messages =
            object : CollaborationSpaceMessageRepository by base.messages {
                override fun observe(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String?,
                ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> {
                    assertEquals(base.access, access)
                    assertNull(conversationId)
                    return base.messages.observe(access, conversationId)
                }
            }
        private val sessions =
            object : SessionRepository by base.sessions {
                override suspend fun current(): AppResult<Session?> {
                    beforeSession()
                    return base.sessions.current()
                }
            }
        val useCase =
            ReadCollaborationSpacePendingMessages(
                messages,
                base.base.directory,
                sessions,
                base.native.server,
            )

        init {
            base.readFacts = {
                flow {
                    beforeRead()
                    emit(readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(rows))
                }
            }
        }

        fun id(index: Int) = "e1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

        fun record(index: Int) =
            base.record().let {
                it.copy(
                    intent =
                        it.intent.copy(
                            id = id(index),
                            createdAt = it.intent.createdAt + index.seconds,
                        )
                )
            }

        suspend fun read() = useCase(base.room.owner)

        suspend fun pending() =
            assertIs<AppResult.Success<List<CollaborationSpaceMessageRecord>>>(read()).value

        fun noSideEffects() {
            assertTrue(base.writes.isEmpty() && base.schedules.isEmpty())
            assertEquals(0, base.reads)
            assertEquals(0, base.canonicalReads)
            assertEquals(0, base.room.bindingReads)
            assertTrue(base.base.requests.isEmpty())
        }
    }
}
