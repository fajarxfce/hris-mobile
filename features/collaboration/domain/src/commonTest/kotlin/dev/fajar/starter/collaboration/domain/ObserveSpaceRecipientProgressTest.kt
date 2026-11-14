@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceRecipientProgressTest {
    @Test
    fun missingAndTerminalFactsPreserveTheRecipientOriginalWithoutDelivery() = runTest {
        val f = SpaceRecipientProgressFixture()
        val absent =
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    f.observe().first()
                )
                .value
                .single()
        assertEquals(f.first, absent.original)
        assertNull(absent.record)
        val device =
            CollaborationMessageDevice(
                f.base.id(10),
                f.base.id(11),
                f.base.id(12),
                "!room:example.test",
            )
        val sent =
            f.record()
                .copy(
                    revision = 3,
                    state =
                        CollaborationMessageState.Sent(
                            device,
                            "recipient-transaction",
                            "${'$'}recipient",
                            CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
                        ),
                )
        for (record in listOf(sent, f.record().copy(state = CollaborationMessageState.Cancelled))) {
            f.base.slot(f.first.id).value = AppResult.Success(record)
            val read =
                assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                        f.observe().first()
                    )
                    .value
                    .single()
            assertEquals(f.first, read.original)
            assertEquals(record, read.record)
        }
        assertEquals(f.base.starts, f.base.stops)
        f.base.assertReadOnly()
    }

    @Test
    fun everyRecipientProvenanceFieldAndCanonicalTargetMustMatch() = runTest {
        val f = SpaceRecipientProgressFixture()
        val intent = f.record().intent
        val origin = requireNotNull(intent.recipientOrigin)
        for (changed in
            listOf(
                intent.copy(recipientOrigin = null),
                intent.copy(recipientOrigin = origin.copy(nextRevision = f.base.id(50))),
                intent.copy(recipientOrigin = origin.copy(peerAccountId = f.base.id(51))),
                intent.copy(
                    recipientOrigin = origin.copy(draftSavedAt = origin.draftSavedAt - 1.seconds)
                ),
                intent.copy(conversationId = f.base.id(52)),
                intent.copy(text = "Another original"),
                intent.copy(createdAt = intent.createdAt + 1.seconds),
            )) {
            f.base.slot(f.first.id).value = AppResult.Success(f.record().copy(intent = changed))
            assertIs<AppResult.Failed>(f.observe().first())
        }
        f.base.assertReadOnly()
    }

    @Test
    fun addingAnotherOriginalKeepsTheFirstRecordSubscription() = runTest {
        val f = SpaceRecipientProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        f.requested.value = listOf(f.first, f.second)
        runCurrent()
        assertEquals(
            listOf(f.first, f.second),
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    values.last()
                )
                .value
                .map { it.original },
        )
        assertEquals(mapOf(f.first.id to 1, f.second.id to 1), f.base.starts)
        f.base.slot(f.first.id).value = AppResult.Success(f.record())
        runCurrent()
        assertEquals(1, f.base.starts[f.first.id])
        job.cancelAndJoin()
        assertEquals(f.base.starts, f.base.stops)
        f.base.assertReadOnly()
    }

    @Test
    fun removingAPendingOriginalJoinsItAndRetainsTheOtherReader() = runTest {
        val f = SpaceRecipientProgressFixture()
        f.requested.value = listOf(f.first, f.second)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.base.customReads[f.first.id] = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        entered.await()
        f.requested.value = listOf(f.second)
        runCurrent()
        assertTrue(stopped.isCompleted)
        assertEquals(
            listOf(f.second),
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    values.last()
                )
                .value
                .map { it.original },
        )
        assertEquals(1, f.base.starts[f.second.id])
        job.cancelAndJoin()
        assertEquals(f.base.starts, f.base.stops)
    }

    @Test
    fun quotaDuplicatesAndWrongRecipientNeverOpenRecordReaders() = runTest {
        val f = SpaceRecipientProgressFixture()
        for (originals in
            listOf(
                List(129) { f.original(it) },
                listOf(f.first, f.first),
                listOf(
                    f.first.copy(original = f.first.original.copy(peerAccountId = f.base.id(55)))
                ),
                listOf(f.first.copy(nextRevision = "invalid")),
            )) {
            assertIs<AppResult.Failed>(
                f.observer().recipients(f.access, f.canonical, flowOf(originals)).first()
            )
        }
        assertTrue(f.base.starts.isEmpty())
        val full = List(128) { f.original(it) }
        val read =
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    f.observer().recipients(f.access, f.canonical, flowOf(full)).first()
                )
                .value
        assertEquals(full, read.map { it.original })
        assertEquals(128, f.base.starts.size)
        assertEquals(f.base.starts, f.base.stops)
    }

    @Test
    fun invalidCanonicalMetadataIsRejectedEvenForAnEmptyRequest() = runTest {
        val f = SpaceRecipientProgressFixture()
        assertIs<AppResult.Failed>(
            f.observer()
                .recipients(
                    f.access,
                    f.canonical.copy(spaceId = f.base.id(99)),
                    flowOf(emptyList()),
                )
                .first()
        )
        assertTrue(f.base.starts.isEmpty())
    }

    @Test
    fun aStorageFailureDoesNotRestartItsReaderWhileTimePasses() = runTest {
        val f = SpaceRecipientProgressFixture()
        f.base.slot(f.first.id).value = AppResult.Failed(f.base.storage)
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.storage), values.single())
        assertEquals(1, f.base.starts[f.first.id])
        f.base.slot(f.first.id).value = AppResult.Success(f.record())
        runCurrent()
        assertEquals(
            f.record(),
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    values.last()
                )
                .value
                .single()
                .record,
        )
        assertEquals(1, f.base.starts[f.first.id])
        job.cancelAndJoin()
    }

    @Test
    fun changingAccountCancelsPendingReadsAndRejectsLaterContent() = runTest {
        val f = SpaceRecipientProgressFixture()
        val entered = CompletableDeferred<Unit>()
        f.base.customReads[f.first.id] = {
            flow {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        entered.await()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.single()).failure.kind,
        )
        assertEquals(f.base.starts, f.base.stops)
        f.base.slot(f.first.id).value = AppResult.Success(f.record())
        runCurrent()
        assertEquals(1, values.size)
        job.cancelAndJoin()
    }

    @Test
    fun confirmedDenialRemainsLatchedThroughFailureUntilAdmittedAgain() = runTest {
        val f = SpaceRecipientProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        assertIs<AppResult.Success<*>>(
            f.base.membership.directory.restrict(f.access, f.base.membership.denied)
        )
        runCurrent()
        assertEquals(f.base.membership.denied, assertIs<AppResult.Failed>(values.last()).failure)
        assertEquals(f.base.starts, f.base.stops)
        f.base.membership.gate.value = AppResult.Failed(f.base.storage)
        runCurrent()
        assertEquals(f.base.membership.denied, assertIs<AppResult.Failed>(values.last()).failure)
        assertEquals(1, f.base.starts[f.first.id])
        f.base.membership.gate.value = AppResult.Success(f.base.membership.open)
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        assertEquals(2, f.base.starts[f.first.id])
        job.cancelAndJoin()
    }

    @Test
    fun inputIsDetachedBeforeTheFinalAdmissionRead() = runTest {
        val f = SpaceRecipientProgressFixture()
        val supplied = mutableListOf(f.first, f.second)
        var guards = 0
        f.base.currentAdmission = {
            if (++guards == 2) supplied.clear()
            f.base.membership.gate.value
        }
        val read =
            assertIs<AppResult.Success<List<CollaborationSpaceRecipientMessageProgress>>>(
                    f.observer().recipients(f.access, f.canonical, flowOf(supplied)).first()
                )
                .value
        assertEquals(listOf(f.first, f.second), read.map { it.original })
        assertTrue(supplied.isEmpty())
        assertEquals(f.base.starts, f.base.stops)
    }

    @Test
    fun endedSessionClosesRecipientReadersAndRedactsTheLastResult() = runTest {
        val f = SpaceRecipientProgressFixture()
        val finish = CompletableDeferred<Unit>()
        f.identityFacts = {
            flow {
                emit(f.base.identity.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.base.starts, f.base.stops)
        job.cancelAndJoin()
    }

    @Test
    fun endedAdmissionClosesRecipientReadersAndRedactsTheLastResult() = runTest {
        val f = SpaceRecipientProgressFixture()
        val finish = CompletableDeferred<Unit>()
        f.admissionFacts = {
            flow {
                emit(f.base.membership.gate.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceRecipientMessageProgress>>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.base.starts, f.base.stops)
        job.cancelAndJoin()
    }

    @Test
    fun canonicalEntryAlsoClosesReadersWhenItsSessionEnds() = runTest {
        val f = SpaceRecipientProgressFixture()
        val finish = CompletableDeferred<Unit>()
        f.identityFacts = {
            flow {
                emit(f.base.identity.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job = backgroundScope.launch { f.observer()(f.access, f.base.requested).toList(values) }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.base.starts, f.base.stops)
        job.cancelAndJoin()
    }

    @Test
    fun cancellationJoinsEveryPendingRecipientRecordReader() = runTest {
        val f = SpaceRecipientProgressFixture()
        f.requested.value = listOf(f.first, f.second)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        f.base.customReads[f.first.id] = {
            flow {
                first.complete(Unit)
                awaitCancellation()
            }
        }
        f.base.customReads[f.second.id] = {
            flow {
                second.complete(Unit)
                awaitCancellation()
            }
        }
        val job = backgroundScope.launch { f.observe().collect() }
        first.await()
        second.await()
        job.cancelAndJoin()
        assertEquals(f.base.starts, f.base.stops)
        runCurrent()
        assertEquals(2, f.base.starts.size)
    }
}
