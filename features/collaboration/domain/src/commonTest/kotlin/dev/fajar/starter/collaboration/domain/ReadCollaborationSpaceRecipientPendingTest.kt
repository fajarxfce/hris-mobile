@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReadCollaborationSpaceRecipientPendingTest {
    @Test
    fun readsOnlyTheRequestedLocalPageWithoutContactOrTransportAcquisition() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        assertEquals(f.page, f.read()(f.access, limit = 4))
        assertEquals<List<Pair<String?, Int>>>(listOf(null to 4), f.pages)
        assertEquals(0, f.draft.contactReads)
        assertEquals(0, f.source.destinationReads)
        assertTrue(f.base.requests.isEmpty())
    }

    @Test
    fun invalidScopeCursorAndBoundsStopBeforeReading() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        for (limit in listOf(0, 9, Int.MAX_VALUE)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read()(f.access, limit = limit)).failure.kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read()(f.access, after = "invalid")).failure.kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read()(f.access.copy(spaceId = "wrong"))).failure.kind,
        )
        assertTrue(f.pages.isEmpty())
    }

    @Test
    fun malformedOrNonProgressingPagesNeverEscape() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val original = f.source.submission
        val later =
            original.copy(
                original = original.original.copy(revision = f.base.generation),
                nextRevision = "80000000-0000-4000-8000-000000000001",
            )
        for (page in
            listOf(
                CollaborationSpaceRecipientSubmissionPage(List(9) { original }, null),
                CollaborationSpaceRecipientSubmissionPage(listOf(original, original), null),
                CollaborationSpaceRecipientSubmissionPage(listOf(later, original), null),
                CollaborationSpaceRecipientSubmissionPage(emptyList(), original.id),
                CollaborationSpaceRecipientSubmissionPage(listOf(original), later.id),
                CollaborationSpaceRecipientSubmissionPage(listOf(original), original.id),
                CollaborationSpaceRecipientSubmissionPage(
                    listOf(
                        original.copy(
                            original = original.original.copy(peerAccountId = f.access.accountId)
                        )
                    ),
                    null,
                ),
            )) {
            f.page = AppResult.Success(page)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
            )
        }
        f.page =
            AppResult.Success(CollaborationSpaceRecipientSubmissionPage(listOf(original), null))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.read()(f.access, after = original.id)).failure.kind,
        )
    }

    @Test
    fun pageIsDetachedBeforePostReadAdmissionCanSuspend() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val rows = mutableListOf(f.source.submission)
        f.page = AppResult.Success(CollaborationSpaceRecipientSubmissionPage(rows, null))
        f.afterPage = {
            f.base.beforeAdmission = {
                rows.clear()
                yield()
            }
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                    f.read()(f.access)
                )
                .value
        assertEquals(listOf(f.source.submission), result.submissions)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun denialBeforeReadOrAfterFailedReadOverridesStorageFailure() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.read()(f.access))
        assertTrue(f.pages.isEmpty())
        val g = SpaceRecipientRecoveryFixture()
        g.page = AppResult.Failed(g.base.storageFailure)
        g.afterPage = { g.base.deny() }
        assertEquals(AppResult.Failed(g.base.denied), g.read()(g.access))
        assertEquals(1, g.pages.size)
    }

    @Test
    fun replacementAccountCannotReceiveTheOldPage() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        f.afterPage = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
        )
        assertEquals(1, f.pages.size)
    }

    @Test
    fun cancellationPropagatesAndClosesThePendingRead() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.beforePage = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val job = async { f.read()(f.access) }
        entered.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(stopped)
        assertEquals(1, f.pages.size)
    }

    @Test
    fun stalledReadHasAFiniteDeadlineAndNoAutomaticSecondRead() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        f.beforePage = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
        )
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(1, f.pages.size)
    }
}
