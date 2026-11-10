package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpacePusherRepository
import dev.fajar.starter.collaboration.domain.usecases.ActivateCollaborationSpacePusher
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ActivateCollaborationSpacePusherTest {
    @Test
    fun currentOriginalRegistersOnceWithoutOpeningAClientOrChangingSetup() = runTest {
        val f = Fixture()
        f.setup.status = f.setup.status.copy(gateway = f.setup.status.gateway!!.copy(version = 3))
        assertEquals(AppResult.Success(Unit), f.run())
        assertEquals(1, f.calls)
        assertEquals(1, f.setup.registration.statusReads)
        assertEquals(0, f.original.installs)
        assertEquals(0, f.original.publications)
        assertEquals(f.original.record, f.setup.saved())
        assertTrue(f.setup.sent.isEmpty())
        assertTrue(f.setup.registration.sent.isEmpty())
    }

    @Test
    fun wrongRuntimeDeviceOperationOrOwnerCannotRegister() = runTest {
        val f = Fixture()
        for (runtime in
            listOf(
                f.runtime.copy(deviceId = ID),
                f.runtime.copy(operationId = ID),
                f.runtime.copy(owner = f.runtime.owner.copy(spaceId = ID)),
                f.runtime.copy(
                    owner =
                        f.runtime.owner.copy(
                            parent = f.runtime.owner.parent.copy(sessionId = "replacement")
                        )
                ),
            )) assertIs<AppResult.Failed>(f.execute(runtime))
        assertEquals(0, f.calls)
        assertEquals(0, f.original.installs)
    }

    @Test
    fun unsupportedPlatformAndExpiredNativeParentStopBeforeProviderStatus() = runTest {
        for (platform in listOf(AppPlatform.Web, AppPlatform.Desktop)) {
            val f = Fixture()
            assertIs<AppResult.Failed>(f.create(platform)(f.runtime))
            assertEquals(0, f.calls)
            assertEquals(0, f.setup.registration.statusReads)
        }
        val f = Fixture()
        f.setup.native.setup.now += 1_000_000.seconds
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.calls)
        assertEquals(0, f.setup.registration.statusReads)
    }

    @Test
    fun revokedDeviceAndChangedGatewayCannotReachRegistration() = runTest {
        for (revoked in listOf(false, true)) {
            val f = Fixture()
            f.setup.status =
                if (revoked) f.setup.status.copy(state = CollaborationDeviceState.REVOKED)
                else f.setup.status.copy(gateway = f.setup.status.gateway!!.copy(id = ID))
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.calls)
            assertEquals(1, f.setup.registration.statusReads)
        }
    }

    @Test
    fun uncertainProviderFailureDoesNotRetryOrRewriteTheOriginal() = runTest {
        val f = Fixture()
        f.before = { AppResult.Failed(f.setup.base.unavailable) }
        assertEquals(AppResult.Failed(f.setup.base.unavailable), f.run())
        assertEquals(1, f.calls)
        assertEquals(1, f.setup.registration.statusReads)
        assertEquals(0, f.original.spaceCloses)
        assertEquals(f.original.record, f.setup.saved())
    }

    @Test
    fun retainedDenialStillWinsWhenRuntimeCleanupFails() = runTest {
        val f = Fixture()
        f.setup.base.deny()
        f.original.beforeSpaceClose = { error("Fictional cleanup failure") }
        assertEquals(AppResult.Failed(f.setup.base.denied), f.run())
        assertEquals(1, f.original.spaceCloses)
        assertEquals(0, f.calls)
        assertEquals(0, f.setup.registration.statusReads)
    }

    @Test
    fun remoteAndProviderDenialSurviveFailedPersistenceAndCleanup() = runTest {
        for (provider in listOf(false, true)) {
            val f = Fixture()
            f.setup.base.afterRestrict = { AppResult.Failed(f.setup.base.storageFailure) }
            f.original.beforeSpaceClose = { AppResult.Failed(f.setup.base.storageFailure) }
            if (provider) f.before = { AppResult.Failed(f.setup.base.denied) }
            else f.setup.registration.beforeStatus = { AppResult.Failed(f.setup.base.denied) }
            assertEquals(AppResult.Failed(f.setup.base.denied), f.run())
            assertEquals(if (provider) 1 else 0, f.calls)
            assertEquals(1, f.original.spaceCloses)
        }
    }

    @Test
    fun replacementDuringStatusCannotStartAndReplacementDuringIoCannotSucceed() = runTest {
        for (duringIo in listOf(false, true)) {
            val f = Fixture()
            if (duringIo)
                f.before = {
                    f.original.transfer.replaceSession()
                    AppResult.Success(Unit)
                }
            else
                f.setup.registration.beforeStatus = {
                    f.original.transfer.replaceSession()
                    AppResult.Success(
                        CollaborationSpaceDeviceObservation(f.setup.owner, f.setup.status)
                    )
                }
            assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(if (duringIo) 1 else 0, f.calls)
        }
    }

    @Test
    fun aNewRestrictionCannotBeOverwrittenByLateProviderSuccess() = runTest {
        val f = Fixture()
        f.before = {
            f.setup.base.deny()
            AppResult.Success(Unit)
        }
        assertEquals(AppResult.Failed(f.setup.base.denied), f.run())
        assertEquals(1, f.original.spaceCloses)
        assertEquals(1, f.calls)
    }

    @Test
    fun changedBootstrapOrRetiredRuntimeCannotPublishALateRegistration() = runTest {
        for (retired in listOf(false, true)) {
            val f = Fixture()
            f.before = {
                if (retired) f.original.open = false
                else
                    f.setup.records.value =
                        AppResult.Success(f.original.record.copy(successorId = ID))
                AppResult.Success(Unit)
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.calls)
            assertEquals(0, f.original.installs)
        }
    }

    @Test
    fun cancellationBeforeEntryCannotReadOrCallProvider() = runTest {
        val f = Fixture()
        val job = launch {
            currentCoroutineContext().cancel()
            f.run()
            error("Cancelled result")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, f.setup.registration.statusReads)
        assertEquals(0, f.calls)
    }

    @Test
    fun cancellationJoinsThePendingProviderWithoutClosingThePublishedParent() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.before = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val job = launch {
            f.run()
            error("Cancelled result")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped)
        assertTrue(f.original.open)
        assertEquals(0, f.original.spaceCloses)
    }

    @Test
    fun aNonCooperativeLateReplyCannotTurnCancellationIntoSuccess() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.before = {
            entered.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            AppResult.Success(Unit)
        }
        val pending = async { f.run() }
        entered.await()
        pending.cancel()
        finish.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, f.calls)
        assertEquals(0, f.original.spaceCloses)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finiteDeadlineAndBusyAdmissionReleaseTheOwnerForAnExplicitLaterAttempt() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.before = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val first = async { f.run() }
        entered.await()
        assertEquals(
            "client.collaboration_space_pusher_busy",
            assertIs<AppResult.Failed>(f.run()).failure.code,
        )
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        assertEquals(15_000L, testScheduler.currentTime)
        assertTrue(stopped)
        f.before = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(Unit), f.run())
        assertEquals(2, f.calls)
    }

    @Test
    fun elapsedBudgetAndWallRollbackCannotExtendRegistrationAuthority() = runTest {
        for (mode in 0..2) {
            val f = Fixture()
            f.before = {
                when (mode) {
                    0 -> f.setup.native.setup.now += 15.seconds
                    1 -> f.setup.native.time += 15.seconds
                    else -> f.setup.native.setup.now -= 1.seconds
                }
                AppResult.Success(Unit)
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.calls)
        }
    }

    private class Fixture {
        val original = SpaceRuntimeAdoptionFixture().also { it.open = true }
        val setup = original.setup
        val runtime =
            CollaborationSpaceRuntimeReady(
                setup.owner,
                original.request.deviceId,
                original.operation,
            )
        var calls = 0
        var before: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
        private val pushers =
            object : CollaborationSpacePusherRepository {
                override suspend fun activate(
                    runtime: CollaborationSpaceRuntimeReady
                ): AppResult<Unit> {
                    assertEquals(this@Fixture.runtime, runtime)
                    calls++
                    return before()
                }
            }

        fun create(platform: AppPlatform = setup.build.platform) =
            ActivateCollaborationSpacePusher(
                setup.commands,
                setup.registration.devices,
                original.runtimes,
                pushers,
                setup.base.directory,
                setup.base.sessions,
                setup.native.server,
                setup.build.copy(platform = platform),
                setup.native.clock,
                setup.native.time,
            )

        val execute = create()

        suspend fun run() = execute(runtime)
    }

    private companion object {
        const val ID = "90000000-0000-4000-8000-000000000099"
    }
}
