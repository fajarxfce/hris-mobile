package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CancelSpaceTextTest {
    @Test
    fun queuedOriginalAndFreshDemandShareThePublicationWhileTheCapturedPassSurvives() = runTest {
        val f = SpaceTextCancellationFixture()
        val pass = CollaborationSpaceInboxRepairPass(f.repair.initial.latestDemand, "page-two")
        f.repair.stored = f.repair.initial.copy(pass = pass)
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        val publication = f.publications.single()
        assertTrue(validCollaborationSpaceInboxRepairPublication(publication))
        assertEquals(pass, publication.replacement.pass)
        assertEquals(f.repair.initial.completedDemand, publication.replacement.completedDemand)
        assertNotEquals(f.repair.initial.latestDemand, publication.replacement.latestDemand)
        assertEquals(f.expected.copy(state = CollaborationMessageState.Cancelled), f.stored)
        assertTrue(f.repair.replacements.isEmpty())
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun absentProgressCanBePublishedWithTheExactExistingOriginal() = runTest {
        val f = SpaceTextCancellationFixture()
        f.repair.stored = null
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        assertNull(f.publications.single().expected)
        assertTrue(validCollaborationSpaceInboxRepairProgress(assertNotNull(f.repair.stored)))
    }

    @Test
    fun exactCancelledReplayDoesNotAcquireOrRewriteLaterProgress() = runTest {
        val f = SpaceTextCancellationFixture()
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        val saved = f.repair.stored
        f.repair.onCheckpoint = { error("Replay must not acquire progress") }
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        assertEquals(saved, f.repair.stored)
        assertEquals(1, f.publications.size)
        assertEquals(1, f.repair.checkpointReads)
    }

    @Test
    fun anUncertainCommittedResponseStopsThenConfirmsTheExactArchiveOnExplicitRetry() = runTest {
        val f = SpaceTextCancellationFixture()
        val commit = f.onCancel
        f.onCancel = { publication ->
            assertEquals(AppResult.Success(true), commit(publication))
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.cancel()(f.access, f.expected))
        assertEquals(1, f.publications.size)
        f.repair.onCheckpoint = { error("Confirmed replay must not require progress") }
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        assertEquals(1, f.publications.size)
    }

    @Test
    fun threeCompareAndSetConflictsPreserveOneDemandAndNeverRewriteProgressSeparately() = runTest {
        val f = SpaceTextCancellationFixture()
        f.onCancel = { AppResult.Success(false) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
        )
        assertEquals(3, f.publications.size)
        assertEquals(3, f.reads)
        assertEquals(3, f.repair.checkpointReads)
        assertEquals(1, f.publications.map { it.replacement.latestDemand }.toSet().size)
        assertEquals(f.expected, f.stored)
        assertTrue(f.repair.replacements.isEmpty())
    }

    @Test
    fun checkpointContentionRereadsAndPreservesTheLatestCapturedPass() = runTest {
        val f = SpaceTextCancellationFixture()
        val commit = f.onCancel
        val newer =
            f.repair.initial.copy(
                generation = f.repair.id(30),
                latestDemand = f.repair.id(31),
                pass =
                    CollaborationSpaceInboxRepairPass(f.repair.initial.latestDemand, "saved-page"),
            )
        f.onCancel = { publication ->
            if (f.publications.size == 1) {
                f.repair.stored = newer
                AppResult.Success(false)
            } else commit(publication)
        }
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        assertEquals(2, f.publications.size)
        assertEquals(newer, f.publications.last().expected)
        assertEquals(newer.pass, f.repair.stored?.pass)
    }

    @Test
    fun missingOrChangedOriginalsAreConflictsWithoutCheckpointAcquisition() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceTextCancellationFixture()
            f.stored =
                if (missing) null
                else
                    f.expected.copy(
                        intent = f.expected.intent.copy(text = "Another retained original")
                    )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
            )
            assertEquals(0, f.repair.checkpointReads)
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun workChangedDuringContentionIsNotCancelledOnAnotherAttempt() = runTest {
        val f = SpaceTextCancellationFixture()
        f.onCancel = {
            f.stored = f.repair.sample.sent().copy(intent = f.expected.intent)
            assertTrue(validCollaborationSpaceMessageRecord(f.access, f.stored!!))
            AppResult.Success(false)
        }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
        )
        assertEquals(1, f.publications.size)
        assertEquals(2, f.reads)
    }

    @Test
    fun operationalFailureAtEachStorageBoundaryStopsWithoutAnotherAttempt() = runTest {
        for (boundary in 0..2) {
            val f = SpaceTextCancellationFixture()
            when (boundary) {
                0 -> f.onRead = { AppResult.Failed(f.base.storageFailure) }
                1 -> f.repair.onCheckpoint = { AppResult.Failed(f.base.storageFailure) }
                2 -> f.onCancel = { AppResult.Failed(f.base.storageFailure) }
            }
            assertEquals(AppResult.Failed(f.base.storageFailure), f.cancel()(f.access, f.expected))
            assertEquals(1, f.reads)
            assertEquals(if (boundary == 2) 1 else 0, f.publications.size)
        }
    }

    @Test
    fun expiredCredentialsAndAbsentCompanySelectionDoNotHideOwnedLocalCancellation() = runTest {
        val f = SpaceTextCancellationFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        assertEquals(AppResult.Success(Unit), f.cancel()(f.access, f.expected))
        assertEquals(1, f.publications.size)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun deniedOrReplacedAccountCannotCancelAnOwnedLocalOriginal() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceTextCancellationFixture()
            if (denied) f.base.deny() else f.base.identity.value = AppResult.Success(null)
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
            )
            assertEquals(0, f.reads)
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun denialOrAccountReplacementDuringEveryIoBoundaryCannotPublishSuccess() = runTest {
        for (boundary in 0..2) for (denied in listOf(false, true)) {
            val f = SpaceTextCancellationFixture()
            val replace = {
                if (denied) f.base.deny() else f.base.identity.value = AppResult.Success(null)
            }
            when (boundary) {
                0 -> f.onRead = {
                        replace()
                        AppResult.Success(f.stored)
                    }
                1 -> f.repair.onCheckpoint = {
                        replace()
                        AppResult.Success(f.repair.stored)
                    }
                2 -> f.onCancel = {
                        replace()
                        AppResult.Success(true)
                    }
            }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
            )
            assertEquals(if (boundary == 2) 1 else 0, f.publications.size)
        }
    }

    @Test
    fun cancellationWaitsForLateIoThenStopsWithoutRetry() = runTest {
        val f = SpaceTextCancellationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onCancel = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(false)
            }
        }
        val job = async { f.cancel()(f.access, f.expected) }
        entered.await()
        job.cancel()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.publications.size)
    }

    @Test
    fun malformedInputAndRetainedFactsFailBeforePublication() = runTest {
        val f = SpaceTextCancellationFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected.copy(revision = 1)))
                .failure
                .kind,
        )
        assertEquals(0, f.reads)
        f.stored = f.expected.copy(revision = -1)
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
        )
        f.stored = f.expected
        f.repair.stored = f.repair.initial.copy(generation = "invalid")
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.cancel()(f.access, f.expected)).failure.kind,
        )
        assertTrue(f.publications.isEmpty())
    }
}
