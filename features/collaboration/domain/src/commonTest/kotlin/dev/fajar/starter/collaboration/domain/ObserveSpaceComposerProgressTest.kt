@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceComposerProgressTest {
    @Test
    fun absentRecordRetainsTheOriginalAndActualTerminalRecordsRemainReadable() = runTest {
        val f = SpaceComposerProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf(CollaborationSpaceComposerMessageProgress(f.first, null)),
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.single()
                )
                .value,
        )
        val device = CollaborationMessageDevice(f.id(10), f.id(11), f.id(12), "!room:example.test")
        val origin = CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64))
        val sent =
            f.record()
                .copy(
                    revision = 3,
                    state =
                        CollaborationMessageState.Sent(
                            device,
                            "original-transaction",
                            "${'$'}event",
                            origin,
                        ),
                )
        f.slot(f.first.id).value = AppResult.Success(sent)
        runCurrent()
        assertEquals(
            sent,
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.last()
                )
                .value
                .single()
                .record,
        )
        job.cancelAndJoin()
        f.slot(f.first.id).value =
            AppResult.Success(f.record().copy(state = CollaborationMessageState.Cancelled))
        val cancelled =
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    f.observe()(f.access, f.requested).first()
                )
                .value
                .single()
        assertEquals(CollaborationMessageState.Cancelled, cancelled.record?.state)
        assertEquals(f.first, cancelled.original)
        f.assertReadOnly()
    }

    @Test
    fun everyOriginalIntentFieldMustMatchTheAuthenticatedRecord() = runTest {
        val f = SpaceComposerProgressFixture()
        val original = f.record().intent
        for (changed in
            listOf(
                original.copy(id = f.id(20)),
                original.copy(owner = original.owner.copy(spaceId = f.id(21))),
                original.copy(conversationId = f.id(22)),
                original.copy(createdAt = original.createdAt + 1.seconds),
                original.copy(text = "Changed"),
                original.copy(replyToEventId = null),
                original.copy(forwarded = true),
            )) {
            f.slot(f.first.id).value = AppResult.Success(f.record().copy(intent = changed))
            assertIs<AppResult.Failed>(f.observe()(f.access, f.requested).first())
        }
        f.assertReadOnly()
    }

    @Test
    fun invalidRecordStateCannotBecomeAProgressSuccess() = runTest {
        val f = SpaceComposerProgressFixture()
        for (changed in
            listOf(
                f.record().copy(revision = 1),
                f.record().copy(revision = -1),
                f.record()
                    .copy(
                        revision = 2,
                        state =
                            CollaborationMessageState.Sent(
                                CollaborationMessageDevice(
                                    f.id(10),
                                    f.id(11),
                                    f.id(12),
                                    "!room:example.test",
                                ),
                                "tx",
                                "${'$'}event",
                            ),
                    ),
            )) {
            f.slot(f.first.id).value = AppResult.Success(changed)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.requested).first()).failure.kind,
            )
        }
        f.assertReadOnly()
    }

    @Test
    fun oversizedDuplicateAndMalformedRequestsStartNoRecordReads() = runTest {
        val f = SpaceComposerProgressFixture()
        for (request in
            listOf(
                List(129) { f.original(it + 10) },
                listOf(f.first, f.first),
                listOf(f.first.copy(original = f.first.original.copy(text = ""))),
            )) {
            assertIs<AppResult.Failed>(f.observe()(f.access, flowOf(request)).first())
        }
        assertTrue(f.starts.isEmpty())
        assertEquals(
            AppResult.Success(emptyList<CollaborationSpaceComposerMessageProgress>()),
            f.observe()(f.access, flowOf(emptyList())).first(),
        )
        f.assertReadOnly()
    }

    @Test
    fun aKnownFailureDoesNotWaitForAnotherRecordsInitialResult() = runTest {
        val f = SpaceComposerProgressFixture()
        f.requested.value = listOf(f.first, f.second)
        f.slot(f.first.id).value = AppResult.Failed(f.storage)
        f.customReads[f.second.id] = { flow { awaitCancellation() } }
        assertEquals(AppResult.Failed(f.storage), f.observe()(f.access, f.requested).first())
        assertEquals(f.starts, f.stops)
        f.assertReadOnly()
    }

    @Test
    fun firstSuccessContainsActualResultsFromEveryRequestedRecord() = runTest {
        val f = SpaceComposerProgressFixture()
        f.requested.value = listOf(f.first, f.second)
        val ready = CompletableDeferred<Unit>()
        f.customReads[f.second.id] = {
            flow {
                ready.await()
                emitAll(f.slot(f.second.id))
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        assertTrue(values.isEmpty())
        ready.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(f.first, f.second),
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.single()
                )
                .value
                .map { it.original },
        )
        job.cancelAndJoin()
        f.assertReadOnly()
    }

    @Test
    fun addingRemovingAndReorderingRequestsKeepsUnchangedReadersAlive() = runTest {
        val f = SpaceComposerProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        f.requested.value = listOf(f.first, f.second)
        runCurrent()
        assertEquals(mapOf(f.first.id to 1, f.second.id to 1), f.starts)
        f.requested.value = listOf(f.second, f.first)
        runCurrent()
        assertEquals(
            listOf(f.second, f.first),
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.last()
                )
                .value
                .map { it.original },
        )
        assertTrue(f.stops.isEmpty())
        f.requested.value = listOf(f.first)
        runCurrent()
        assertEquals(mapOf(f.second.id to 1), f.stops)
        assertEquals(1, f.starts[f.first.id])
        job.cancelAndJoin()
        assertEquals(f.starts, f.stops)
        f.assertReadOnly()
    }

    @Test
    fun equalRequestedValuesAndTokenRotationDoNotRestartOrRepublish() = runTest {
        val f = SpaceComposerProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val requested = flow {
            emit(listOf(f.first))
            emit(listOf(f.first.copy()))
            emitAll(f.requested)
        }
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, requested).toList(values)
            }
        runCurrent()
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(1, values.size)
        assertEquals(mapOf(f.first.id to 1), f.starts)
        job.cancelAndJoin()
        f.assertReadOnly()
    }

    @Test
    fun mutableNativeQueueSetsAreDetachedBeforeSuspendedAdmission() = runTest {
        val f = SpaceComposerProgressFixture()
        val before = mutableSetOf("known-tx")
        val state =
            CollaborationMessageState.Staging(
                CollaborationMessageDevice(f.id(10), f.id(11), f.id(12), "!room:example.test"),
                before,
                CollaborationMessageOrigin("@fictional:example.test", "b".repeat(64)),
            )
        f.slot(f.first.id).value = AppResult.Success(f.record().copy(revision = 1, state = state))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        f.currentAdmission = {
            if (++reads == 2) {
                entered.complete(Unit)
                release.await()
            }
            f.membership.gate.value
        }
        val result = async { f.observe()(f.access, f.requested).first() }
        entered.await()
        before += "late-tx"
        release.complete(Unit)
        val record =
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    result.await()
                )
                .value
                .single()
                .record
        assertEquals(
            setOf("known-tx"),
            assertIs<CollaborationMessageState.Staging>(record?.state).before,
        )
        f.assertReadOnly()
    }

    @Test
    fun changingAnOriginalWithTheSameIdReplacesItsReaderAndRejectsOldContent() = runTest {
        val f = SpaceComposerProgressFixture()
        f.slot(f.first.id).value = AppResult.Success(f.record())
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        f.requested.value =
            listOf(
                f.first.copy(original = f.first.original.copy(text = "Different immutable body"))
            )
        runCurrent()
        assertEquals(
            "client.collaboration_composer_changed",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
        assertEquals(2, f.starts[f.first.id])
        assertEquals(1, f.stops[f.first.id])
        job.cancelAndJoin()
        f.assertReadOnly()
    }
}
