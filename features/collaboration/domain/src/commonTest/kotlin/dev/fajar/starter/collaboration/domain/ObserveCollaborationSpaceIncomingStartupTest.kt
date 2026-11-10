@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceIncomingStartupTest {
    @Test
    fun rootThenBootstrapPublicationProducesOneReadySignalWithoutTransport() = runTest {
        val f = SpaceIncomingStartupFixture()
        f.root.value = AppResult.Success(null)
        f.setup.records.value = AppResult.Success(null)
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect { received += it }
            }
        runCurrent()
        assertNull(assertIs<AppResult.Success<*>>(received.single()).value)
        f.root.value = AppResult.Success(CollaborationSpaceRoot(f.owner.spaceId))
        runCurrent()
        assertEquals(1, received.size)
        f.setup.records.value = AppResult.Success(f.adoption.record)
        runCurrent()
        assertEquals(listOf(AppResult.Success(null), f.candidate()), received)
        job.cancelAndJoin()
        assertEquals(f.rootStarts, f.rootStops)
        assertEquals(f.setup.observations, f.setup.stops)
        assertEquals(f.setup.registration.observations, f.setup.registration.stops)
        assertEquals(0, f.adoption.installs)
        assertEquals(0, f.setup.registration.sdkReads)
        assertTrue(f.setup.sent.isEmpty())
        assertTrue(f.setup.base.requests.isEmpty())
    }

    @Test
    fun tokenRenewalAndLocalJournalWritesDoNotRepublishStartup() = runTest {
        val f = SpaceIncomingStartupFixture()
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect { received += it }
            }
        runCurrent()
        f.setup.registration.session(
            f.setup.native.session.copy(
                tokens = f.setup.native.session.tokens.copy(accessToken = "rotated")
            )
        )
        f.setup.records.value =
            AppResult.Success(f.adoption.record.copy(revision = 9, attempts = 8))
        f.setup.registration.records.value =
            AppResult.Success(f.setup.registered.copy(revision = 8, attempts = 7))
        runCurrent()
        assertEquals(listOf(f.candidate()), received)
        assertEquals(1, f.rootStarts)
        job.cancelAndJoin()
    }

    @Test
    fun expiredTokensAndGatewayDoNotHideLocalBootstrap() = runTest {
        val f = SpaceIncomingStartupFixture()
        f.setup.native.setup.now += 1_000_000.seconds
        f.setup.native.setup.workspace = null
        assertEquals(f.candidate(), f.observe()(f.owner.parent).first())
    }

    @Test
    fun nativeParentReplacementCancelsAllPreviousObservations() = runTest {
        val f = SpaceIncomingStartupFixture()
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect { received += it }
            }
        runCurrent()
        f.setup.registration.session(
            f.setup.native.session.copy(
                binding = f.setup.native.session.binding.copy(serverId = f.owner.spaceId)
            )
        )
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(received.last()).failure.kind,
        )
        assertEquals(1, f.rootStops)
        assertEquals(1, f.setup.stops)
        assertEquals(1, f.setup.registration.stops)
        f.setup.records.value = AppResult.Success(f.adoption.record.copy(revision = 4))
        runCurrent()
        assertEquals(2, received.size)
        job.cancelAndJoin()
    }

    @Test
    fun blockedConsumerCannotPublishAQueuedOldOwnerAfterReplacement() = runTest {
        val f = SpaceIncomingStartupFixture()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect {
                    received += it
                    if (received.size == 1) release.await()
                }
            }
        runCurrent()
        f.setup.records.value = AppResult.Success(f.nextBootstrap)
        runCurrent()
        f.setup.registration.session(
            f.setup.native.session.copy(
                user = f.setup.native.session.user.copy(id = f.owner.spaceId)
            )
        )
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, received.size)
        assertIs<AppResult.Failed>(received.last())
        job.cancelAndJoin()
    }

    @Test
    fun rootRemovalCancelsQueuedBootstrapWhileTheConsumerIsBlocked() = runTest {
        val f = SpaceIncomingStartupFixture()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect {
                    received += it
                    if (received.size == 1) release.await()
                }
            }
        runCurrent()
        f.setup.records.value = AppResult.Success(f.nextBootstrap)
        runCurrent()
        f.root.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(f.candidate(), AppResult.Success(null)), received)
        job.cancelAndJoin()
        assertEquals(f.setup.observations, f.setup.stops)
    }

    @Test
    fun denialOverridesStorageFailureAndReadmissionRechecksCurrentBootstrap() = runTest {
        val f = SpaceIncomingStartupFixture()
        val received = mutableListOf<AppResult<CollaborationSpaceIncomingStartup?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.owner.parent).collect { received += it }
            }
        runCurrent()
        val denied = CollaborationSpaceDirectoryAdmission(f.owner.spaceId, f.setup.base.denied)
        f.setup.registration.records.value = AppResult.Failed(f.setup.base.storageFailure)
        f.setup.base.gate.value = AppResult.Success(denied)
        runCurrent()
        assertEquals(AppResult.Failed(f.setup.base.denied), received.last())
        f.setup.base.gate.value = AppResult.Success(f.setup.base.open)
        runCurrent()
        assertEquals(AppResult.Failed(f.setup.base.storageFailure), received.last())
        f.setup.registration.records.value = AppResult.Success(f.setup.registered)
        runCurrent()
        assertEquals(f.candidate(admission = f.setup.base.open), received.last())
        job.cancelAndJoin()
    }

    @Test
    fun rootFailureAndMalformedRootNeverSubscribeToSetup() = runTest {
        val f = SpaceIncomingStartupFixture()
        f.root.value = AppResult.Failed(f.setup.base.storageFailure)
        assertEquals(
            AppResult.Failed(f.setup.base.storageFailure),
            f.observe()(f.owner.parent).first(),
        )
        f.root.value = AppResult.Success(CollaborationSpaceRoot("bad"))
        assertIs<AppResult.Failed>(f.observe()(f.owner.parent).first())
        assertEquals(0, f.setup.observations)
        assertEquals(0, f.setup.registration.observations)
    }

    @Test
    fun malformedOversizedOrFailedSetupCannotBecomeReadiness() = runTest {
        val f = SpaceIncomingStartupFixture()
        for (rows in
            listOf(
                listOf(f.adoption.record.copy(attempts = -1)),
                List(129) { f.adoption.record },
            )) {
            f.setup.observeRecords = { flowOf(AppResult.Success(rows)) }
            assertIs<AppResult.Failed>(f.observe()(f.owner.parent).first())
        }
        f.setup.observeRecords = { flowOf(AppResult.Failed(f.setup.base.storageFailure)) }
        assertEquals(
            AppResult.Failed(f.setup.base.storageFailure),
            f.observe()(f.owner.parent).first(),
        )
    }

    @Test
    fun unsupportedPlatformAndForeignParentDoNotObserveRoots() = runTest {
        val f = SpaceIncomingStartupFixture()
        assertIs<AppResult.Failed>(
            f.observe(f.setup.build.copy(platform = AppPlatform.Web))(f.owner.parent).first()
        )
        assertIs<AppResult.Failed>(
            f.observe()(f.owner.parent.copy(accountId = f.owner.spaceId)).first()
        )
        assertEquals(0, f.rootStarts)
    }
}
