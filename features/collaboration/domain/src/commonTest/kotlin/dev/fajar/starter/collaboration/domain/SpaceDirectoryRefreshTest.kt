@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceDirectoryRefreshTest {
    @Test
    fun terminalCursorRemainsTerminalAcrossUseCaseRecreationAndExplicitRestartIsOnePage() =
        runTest {
            val f = SpaceDirectoryUseCaseFixture()
            assertEquals(AppResult.Success(true), f.refresh()(f.owner))
            val completed = assertNotNull(f.checkpoint)
            assertTrue(completed.complete)
            repeat(3) { assertEquals(AppResult.Success(false), f.refresh()(f.owner)) }
            assertEquals(listOf<Pair<String?, Int>>(null to 50), f.requests)
            assertEquals(1, f.admits)
            val replacement = f.page(1, 50, false)
            f.native.onPage = { AppResult.Success(replacement) }
            assertEquals(AppResult.Success(true), f.refresh()(f.owner, restart = true))
            val staged = assertNotNull(f.checkpoint)
            assertNotEquals(completed.generation, staged.generation)
            assertFalse(staged.complete)
            assertFalse(staged.publishPartial)
            assertEquals(2, f.requests.size)
            val tail = f.page(51, 1, true)
            f.native.onPage = { AppResult.Success(tail) }
            assertEquals(AppResult.Success(true), f.refresh()(f.owner, restart = true))
            assertEquals(replacement.nextCursor to 50, f.requests.last())
            assertEquals(staged.generation, assertNotNull(f.checkpoint).generation)
            assertEquals(51, assertNotNull(f.checkpoint).count)
            assertEquals(2, f.begins)
        }

    @Test
    fun interruptedAppendRetainsExactCursorForAnExplicitRetry() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        val page = f.page(1, 50, false)
        f.native.onPage = { AppResult.Success(page) }
        assertIs<AppResult.Success<*>>(f.refresh()(f.owner))
        val saved = f.checkpoint
        f.native.onPage = { AppResult.Success(f.page(51, 1, true)) }
        f.beforeAppend = { AppResult.Failed(f.storageFailure) }
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
        assertEquals(saved, f.checkpoint)
        f.beforeAppend = { AppResult.Success(true) }
        assertEquals(AppResult.Success(true), f.refresh()(f.owner))
        assertEquals(listOf(null to 50, page.nextCursor to 50, page.nextCursor to 50), f.requests)
        assertEquals(1, f.begins)
    }

    @Test
    fun priorDenialSurvivesPendingNetworkFailuresTimeoutStorageFailuresAndCompleteNoop() = runTest {
        for (mode in 0..4) {
            val f = SpaceDirectoryUseCaseFixture()
            f.deny()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            f.native.onPage = {
                entered.complete(Unit)
                resume.await()
                when (mode) {
                    0 -> AppResult.Failed(f.unavailable)
                    1 -> awaitCancellation()
                    else -> AppResult.Success(f.native.page)
                }
            }
            if (mode == 2) f.beforeBegin = { AppResult.Failed(f.storageFailure) }
            if (mode == 3) f.beforeAppend = { AppResult.Failed(f.storageFailure) }
            if (mode == 4) f.beforeAdmit = { AppResult.Failed(f.storageFailure) }
            val request = async { f.refresh()(f.owner) }
            entered.await()
            assertEquals(
                f.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.gate.value)
                    .value
                    .denial,
            )
            resume.complete(Unit)
            assertEquals(f.denied, assertIs<AppResult.Failed>(request.await()).failure)
            assertEquals(
                f.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.gate.value)
                    .value
                    .denial,
            )
        }
        val f = SpaceDirectoryUseCaseFixture()
        f.checkpoint = f.stored
        f.deny()
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.admits)
    }

    @Test
    fun newDenialWinsWhenAnOlderAdmittedPageFinishes() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.deny()
        val newer = f.denied.copy(code = "mfa_required")
        f.beforeAdmit = {
            f.deny(newer, "60000000-0000-4000-8000-000000000002")
            AppResult.Success(true)
        }
        assertEquals(newer, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
        assertEquals(
            newer,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.gate.value)
                .value
                .denial,
        )
        assertEquals(1, f.appends)
    }

    @Test
    fun serverDenialReachesGateEvenAtCancellationAndCleanupHasItsOwnBound() = runTest {
        for (cancel in listOf(false, true)) {
            val f = SpaceDirectoryUseCaseFixture()
            f.native.onPage = {
                if (cancel) currentCoroutineContext().cancel()
                AppResult.Failed(f.denied)
            }
            val cleanupStopped = CompletableDeferred<Unit>()
            f.afterRestrict = {
                try {
                    awaitCancellation()
                } finally {
                    cleanupStopped.complete(Unit)
                }
            }
            val request = async { f.refresh()(f.owner) }
            if (cancel) assertFailsWith<CancellationException> { request.await() }
            else assertEquals(f.denied, assertIs<AppResult.Failed>(request.await()).failure)
            cleanupStopped.await()
            assertEquals(1, f.restrictions)
            assertEquals(
                f.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.gate.value)
                    .value
                    .denial,
            )
            assertEquals(0, f.begins + f.appends + f.admits)
        }
    }

    @Test
    fun ordinaryProviderOutageAndExpiredLocalParentDoNotCreateReadDenials() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.native.onPage = { AppResult.Failed(f.unavailable) }
        assertEquals(f.unavailable, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
        f.native.setup.now += 10_000.seconds
        assertIs<AppResult.Failed>(f.refresh()(f.owner))
        assertEquals(1, f.requests.size)
        assertEquals(0, f.restrictions + f.begins + f.admits)
        assertEquals(AppResult.Success(f.open), f.gate.value)
    }

    @Test
    fun invalidOrOverCapacityPagesNeverPublishOrReopen() = runTest {
        for (mode in 0..3) {
            val f = SpaceDirectoryUseCaseFixture()
            f.deny()
            when (mode) {
                0 -> f.native.onPage = {
                        AppResult.Success(
                            f.native.page.copy(
                                owner = f.owner.copy(spaceId = f.native.contact.accountId)
                            )
                        )
                    }
                1 -> f.native.onPage = {
                        AppResult.Success(
                            f.native.page.copy(nextCursor = f.native.contact.accountId)
                        )
                    }
                2 -> f.native.onPage = {
                        AppResult.Success(
                            f.native.page.copy(items = listOf(f.native.contact, f.native.contact))
                        )
                    }
                else -> {
                    f.checkpoint =
                        f.stored.copy(
                            count = 9_999,
                            complete = false,
                            nextCursor = f.native.contact.accountId,
                        )
                    f.native.onPage = { AppResult.Success(f.page(2, 2, true)) }
                }
            }
            assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
            assertEquals(0, f.begins + f.appends + f.admits)
        }
    }

    @Test
    fun falseBeginAndAppendAreConflictsAndNeverAdmit() = runTest {
        for (begin in listOf(false, true)) {
            val f = SpaceDirectoryUseCaseFixture()
            if (begin) f.beforeBegin = { AppResult.Success(false) }
            else f.beforeAppend = { AppResult.Success(false) }
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure.kind,
            )
            assertEquals(0, f.admits)
        }
    }
}
