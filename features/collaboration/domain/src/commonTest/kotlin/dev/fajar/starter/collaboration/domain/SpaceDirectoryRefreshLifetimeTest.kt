@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceDirectoryRefreshLifetimeTest {
    @Test
    fun admissionStorageDelayCannotRenewTheInitiallyCapturedParentDeadline() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        val initialTime = f.native.setup.now
        f.native.setup.session =
            f.native.session.copy(
                binding =
                    f.native.session.binding.copy(
                        expiresAtEpochMillis = (initialTime + 10.seconds).toEpochMilliseconds()
                    )
            )
        f.beforeAdmission = {
            f.native.setup.session = f.native.session
            f.native.setup.now = initialTime + 1.seconds
            f.native.time += 11.seconds
        }
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure.kind,
        )
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.begins + f.appends + f.admits)
    }

    @Test
    fun waitingContinuationReadsNewDenialBeforeReturningATerminalNoop() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.native.onPage = {
            entered.complete(Unit)
            resume.await()
            AppResult.Success(f.native.page)
        }
        f.beforeAdmit = {
            f.deny()
            AppResult.Success(true)
        }
        val refresh = f.refresh()
        val first = async { refresh(f.owner) }
        entered.await()
        val second = async { refresh(f.owner) }
        runCurrent()
        resume.complete(Unit)
        assertEquals(f.denied, assertIs<AppResult.Failed>(first.await()).failure)
        assertEquals(f.denied, assertIs<AppResult.Failed>(second.await()).failure)
        assertEquals(1, f.requests.size)
        assertEquals(1, f.admits)
    }

    @Test
    fun replacedOwnerBeforeRemoteAfterRemoteAndAfterStorageNeverSucceedsOrReopens() = runTest {
        for (phase in 0..3) {
            val f = SpaceDirectoryUseCaseFixture()
            fun replace() {
                f.native.setup.session = f.native.session.copy(id = "replacement")
            }
            when (phase) {
                0 -> f.beforeCheckpoint = { replace() }
                1 -> f.native.onPage = {
                        replace()
                        AppResult.Success(f.native.page)
                    }
                2 -> f.beforeBegin = {
                        replace()
                        AppResult.Success(true)
                    }
                else -> f.beforeAppend = {
                        replace()
                        AppResult.Success(true)
                    }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure.kind,
            )
            assertEquals(if (phase == 0) 0 else 1, f.requests.size)
            assertEquals(if (phase == 3) 1 else 0, f.appends)
            assertEquals(0, f.admits)
        }
    }

    @Test
    fun monotonicInitialParentAndWallRollbackBoundResponsesAndStoragePublication() = runTest {
        for (mode in 0..3) {
            val f = SpaceDirectoryUseCaseFixture()
            val initialTime = f.native.setup.now
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis = (initialTime + 10.seconds).toEpochMilliseconds()
                        )
                )
            fun expire() {
                f.native.setup.session = f.native.session
                when (mode) {
                    0 -> {
                        f.native.time += 11.seconds
                        f.native.setup.now = initialTime + 1.seconds
                    }
                    1 -> f.native.setup.now = initialTime - 1.seconds
                    else -> f.native.setup.now = initialTime + 11.seconds
                }
            }
            if (mode == 3)
                f.beforeAppend = {
                    expire()
                    AppResult.Success(true)
                }
            else
                f.native.onPage = {
                    expire()
                    AppResult.Success(f.native.page)
                }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure.kind,
            )
            assertEquals(if (mode == 3) 1 else 0, f.begins)
            assertEquals(0, f.admits)
        }
    }

    @Test
    fun tokenRotationWithinOriginalLifetimeStillPublishes() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.native.onPage = {
            f.native.setup.now += 2.seconds
            f.native.time += 2.seconds
            f.native.setup.session =
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            AppResult.Success(f.native.page)
        }
        assertEquals(AppResult.Success(true), f.refresh()(f.owner))
        assertEquals(1, f.admits)
    }

    @Test
    fun cancellationStopsPendingRemoteAndCheckpointWithoutPublishing() = runTest {
        for (storage in listOf(false, true)) {
            val f = SpaceDirectoryUseCaseFixture()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            suspend fun pending(): Nothing =
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            if (storage) f.beforeCheckpoint = { pending() } else f.native.onPage = { pending() }
            val request = async { f.refresh()(f.owner) }
            entered.await()
            request.cancelAndJoin()
            stopped.await()
            assertTrue(request.isCancelled)
            assertEquals(0, f.begins + f.appends + f.admits)
        }
    }

    @Test
    fun oneTimeoutIncludesWaitingForAnotherRefreshAndDoesNotStartASecondRequest() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        f.native.onPage = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val refresh = f.refresh()
        val first = async { refresh(f.owner) }
        entered.await()
        val second = async { refresh(f.owner) }
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(second.await()).failure.kind)
        assertEquals(15_000, testScheduler.currentTime)
        assertEquals(1, f.requests.size)
        assertEquals(0, f.begins + f.appends + f.admits)
    }

    @Test
    fun cancellationAfterNoncooperativeRemoteReturnCannotPublish() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.native.onPage = {
            currentCoroutineContext().cancel()
            AppResult.Success(f.native.page)
        }
        val request = async { f.refresh()(f.owner) }
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(0, f.begins + f.appends + f.admits)
    }
}
