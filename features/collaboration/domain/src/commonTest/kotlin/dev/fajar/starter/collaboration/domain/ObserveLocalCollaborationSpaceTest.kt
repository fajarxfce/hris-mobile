@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveLocalCollaborationSpaceTest {
    @Test
    fun signedOutAndMissingRootNeverAcquireGroupAdmission() = runTest {
        for (signedOut in listOf(false, true)) {
            val f = LocalSpaceOwnerFixture()
            if (signedOut) f.identity.value = AppResult.Success(null)
            else f.facts.value = AppResult.Success(null)
            val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
            val job = backgroundScope.launch { f.observe().toList(values) }
            runCurrent()
            assertEquals(
                listOf<AppResult<CollaborationSpaceLocalAccess?>>(AppResult.Success(null)),
                values,
            )
            assertEquals(if (signedOut) 0 else 1, f.rootStarts.size)
            assertTrue(f.gateStarts.isEmpty())
            job.cancelAndJoin()
            assertEquals(f.rootStarts, f.rootStops)
            assertEquals(1, f.sessionStops)
        }
    }

    @Test
    fun expiredCredentialsAndTokenRotationKeepOneLocalOwnerWithoutNativeAcquisition() = runTest {
        val f = LocalSpaceOwnerFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.access), values.last())
        val session = f.base.native.session
        f.identity.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(listOf(f.account), f.rootStarts)
        assertEquals(listOf(f.access), f.gateStarts)
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(f.gateStarts, f.gateStops)
    }

    @Test
    fun aChangedRootClosesTheOldOwnerBeforeNewAdmissionIsAvailable() = runTest {
        val f = LocalSpaceOwnerFixture()
        val second = MutableSharedFlow<AppResult<CollaborationSpaceDirectoryAdmission>>()
        f.gateSource = { access -> if (access == f.access) f.admission else second }
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.access), values.last())
        f.facts.value = AppResult.Success(CollaborationSpaceRoot(f.other))
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        assertEquals(listOf(f.access), f.gateStops)
        second.emit(AppResult.Success(f.allowed))
        runCurrent()
        assertEquals(AppResult.Success(f.access.copy(spaceId = f.other)), values.last())
        job.cancelAndJoin()
        assertEquals(f.gateStarts, f.gateStops)
    }

    @Test
    fun switchingAccountsClosesTheOldOwnerWhileTheNewRootIsStillUnavailable() = runTest {
        val f = LocalSpaceOwnerFixture()
        f.rootSource = { access ->
            if (access == f.account) f.facts else flow { awaitCancellation() }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        f.identity.value =
            AppResult.Success(
                f.base.native.session.copy(user = f.base.native.session.user.copy(id = f.other))
            )
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        assertEquals(listOf(f.access), f.gateStops)
        assertEquals(listOf(f.account), f.rootStops)
        assertEquals(2, f.rootStarts.size)
        job.cancelAndJoin()
    }

    @Test
    fun confirmedDenialSurvivesStorageAndMalformedAdmissionUntilAnAdmittedUpdate() = runTest {
        val f = LocalSpaceOwnerFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        f.admission.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.other, f.denied))
        runCurrent()
        assertEquals(AppResult.Failed(f.denied), values.last())
        val count = values.size
        f.admission.value = AppResult.Failed(f.unavailable)
        runCurrent()
        f.admission.value = AppResult.Success(CollaborationSpaceDirectoryAdmission(f.other, null))
        runCurrent()
        assertEquals(count, values.size)
        assertEquals(AppResult.Failed(f.denied), values.last())
        f.admission.value = AppResult.Success(f.allowed)
        runCurrent()
        assertEquals(AppResult.Success(f.access), values.last())
        assertEquals(1, f.rootStarts.size)
        assertEquals(1, f.gateStarts.size)
        job.cancelAndJoin()
    }

    @Test
    fun rootRemovalDuringCurrentAdmissionCannotPublishTheOldOwner() = runTest {
        val f = LocalSpaceOwnerFixture()
        f.beforeGateRead = { f.facts.value = AppResult.Success(null) }
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertFalse(values.any { (it as? AppResult.Success)?.value == f.access })
        assertEquals(AppResult.Success(null), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun aSessionChangeDuringTheFinalRootReadCannotPublishTheOldOwner() = runTest {
        val f = LocalSpaceOwnerFixture()
        f.beforeRootRead = { f.identity.value = AppResult.Success(null) }
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        assertFalse(values.any { (it as? AppResult.Success)?.value == f.access })
        assertEquals(AppResult.Success(null), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun lateCancelledAdmissionCannotPublishAnOwnerAndEverySubscriptionCloses() = runTest {
        val f = LocalSpaceOwnerFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeGateRead = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
        val job = backgroundScope.launch { f.observe().toList(values) }
        runCurrent()
        entered.await()
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertFalse(values.any { (it as? AppResult.Success)?.value == f.access })
        assertEquals(f.rootStarts, f.rootStops)
        assertEquals(f.gateStarts, f.gateStops)
        assertEquals(1, f.sessionStops)
    }

    @Test
    fun completedRootAndAdmissionStreamsLeaveNoActiveOwner() = runTest {
        for (rootCompleted in listOf(false, true)) {
            val f = LocalSpaceOwnerFixture()
            if (rootCompleted) f.rootSource = { flowOf(f.facts.value) }
            else f.gateSource = { flowOf(f.admission.value) }
            val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
            val job = backgroundScope.launch { f.observe().toList(values) }
            runCurrent()
            assertEquals(AppResult.Success(null), values.last())
            assertEquals(f.gateStarts, f.gateStops)
            job.cancelAndJoin()
        }
    }

    @Test
    fun foreignAudienceAndMalformedRootNeverReachGroupAdmission() = runTest {
        for (foreignAudience in listOf(false, true)) {
            val f = LocalSpaceOwnerFixture()
            if (foreignAudience)
                f.identity.value =
                    AppResult.Success(
                        f.base.native.session.copy(
                            binding =
                                f.base.native.session.binding.copy(audience = "https://other.test")
                        )
                    )
            else f.facts.value = AppResult.Success(CollaborationSpaceRoot("malformed"))
            val values = mutableListOf<AppResult<CollaborationSpaceLocalAccess?>>()
            val job = backgroundScope.launch { f.observe().toList(values) }
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            assertTrue(f.gateStarts.isEmpty())
            assertEquals(if (foreignAudience) 0 else 1, f.rootStarts.size)
            job.cancelAndJoin()
        }
    }
}
