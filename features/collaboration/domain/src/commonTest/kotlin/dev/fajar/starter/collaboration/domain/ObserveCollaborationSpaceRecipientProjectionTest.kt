@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRecipientProjectionTest {
    @Test
    fun retainedContentStaysLocalAndIndependentOfCredentialAge() = runTest {
        val f = SpaceRecipientProjectionFixture()
        f.base.native.setup.now += 300.days
        assertEquals(AppResult.Success(f.retained), f.observe()(f.access, f.peer).first())
        assertEquals(1, f.reads)
        assertEquals(1, f.stops)
        assertEquals(0, f.owner.contactReads)
        assertTrue(f.base.requests.isEmpty())
    }

    @Test
    fun confirmedDenialSurvivesFailureUntilNewObservedAdmission() = runTest {
        val f = SpaceRecipientProjectionFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientProjection>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.retained), values.last())
        f.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.stops)
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.reads)
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.retained), values.last())
        assertEquals(2, f.reads)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun accountLossCancelsTheReaderAndRejectsLaterContent() = runTest {
        val f = SpaceRecipientProjectionFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientProjection>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.stops)
        f.facts.value = AppResult.Success(f.retained.copy(draft = null))
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.reads)
        job.cancelAndJoin()
    }

    @Test
    fun capturedOriginalsCannotBeMutatedDuringTheFinalGuard() = runTest {
        val f = SpaceRecipientProjectionFixture()
        val supplied = mutableListOf(f.original)
        f.facts.value = AppResult.Success(f.retained.copy(submissions = supplied))
        var guards = 0
        f.base.beforeAdmission = { if (++guards == 2) supplied.clear() }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.observe()(f.access, f.peer).first()
                )
                .value
        assertEquals(listOf(f.original), result.submissions)
        assertTrue(supplied.isEmpty())
    }

    @Test
    fun postReadDenialNeverPublishesTheCapturedPrivateProjection() = runTest {
        val f = SpaceRecipientProjectionFixture()
        var guards = 0
        f.base.beforeAdmission = { if (++guards == 2) f.base.deny() }
        val result = f.observe()(f.access, f.peer).first()
        assertEquals(AppResult.Failed(f.base.denied), result)
    }

    @Test
    fun malformedPeersDuplicateIdsAndOversizedInventoriesFailClosed() = runTest {
        val f = SpaceRecipientProjectionFixture()
        val foreign =
            f.original.copy(original = f.original.original.copy(peerAccountId = f.access.accountId))
        for (value in
            listOf(
                f.retained.copy(
                    draft = f.original.original.copy(peerAccountId = f.access.accountId)
                ),
                f.retained.copy(submissions = listOf(foreign)),
                f.retained.copy(submissions = listOf(f.original, f.original)),
                f.retained.copy(submissions = List(129) { f.original }),
            )) {
            f.facts.value = AppResult.Success(value)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.peer).first()).failure.kind,
            )
        }
    }

    @Test
    fun invalidRecipientNeverOpensItsContentReader() = runTest {
        val f = SpaceRecipientProjectionFixture()
        for (peer in listOf("invalid", f.access.accountId)) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe()(f.access, peer).first()).failure.kind,
            )
        }
        assertEquals(0, f.reads)
    }

    @Test
    fun disposalJoinsSuspendedContentWithoutRestartingIt() = runTest {
        val f = SpaceRecipientProjectionFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.content = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).collect() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        runCurrent()
        assertEquals(1, f.reads)
        assertEquals(1, f.stops)
    }
}
