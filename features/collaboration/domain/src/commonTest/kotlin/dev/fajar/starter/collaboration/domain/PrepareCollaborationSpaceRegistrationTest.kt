@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PrepareCollaborationSpaceRegistrationTest {
    @Test
    fun originalSdkAndProviderAreRetainedOnceWithoutCompanySelectionOrHttpMutation() = runTest {
        val f = SpaceRegistrationFixture()
        f.native.setup.workspace = null
        val original = f.prepared()
        assertEquals(f.provider, original.command.provider)
        assertEquals(f.sdkIdentity, original.command.legacy?.identity)
        assertEquals(f.sdkOwner.setupBinding(), original.command.legacy?.binding)
        assertEquals(CollaborationSpaceDeviceOrigin.LEGACY, original.command.origin)
        assertEquals(
            AppResult.Success(original),
            f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Another label"),
        )
        assertEquals(1, f.inserts)
        assertEquals(1, f.sdkReads)
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.base.admits)
    }

    @Test
    fun absentSelectionUnavailableSdkAndWrongProviderNeverInventNewDevice() = runTest {
        for (variant in 0..4) {
            val f = SpaceRegistrationFixture()
            when (variant) {
                0 -> f.beforeIdentity = {
                        AppResult.Success(CollaborationSpaceIdentityObservation(f.owner, null))
                    }
                1 -> f.beforeSdk = { AppResult.Failed(f.base.unavailable) }
                2 -> f.beforeSdk = { AppResult.Success(f.sdkIdentity.copy(fingerprint = "wrong")) }
                3 -> f.beforeIdentity = {
                        AppResult.Success(
                            CollaborationSpaceIdentityObservation(
                                f.owner,
                                f.provider.copy(matrixUserId = "@other:matrix.hris.internal"),
                            )
                        )
                    }
                else -> f.beforeIdentity = {
                        AppResult.Success(
                            CollaborationSpaceIdentityObservation(
                                f.owner.copy(spaceId = f.owner.parent.accountId),
                                f.provider,
                            )
                        )
                    }
            }
            assertIs<AppResult.Failed>(f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone"))
            assertEquals(0, f.inserts)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun differentOriginalNativeFamilyOrAccountCannotReadSdkOrPrepare() = runTest {
        for (variant in 0..3) {
            val f = SpaceRegistrationFixture()
            val original =
                when (variant) {
                    0 -> f.sdkOwner.copy(nativeSessionId = f.owner.spaceId)
                    1 -> f.sdkOwner.copy(accountId = f.owner.spaceId)
                    2 -> f.sdkOwner.copy(sessionId = "old-local")
                    else -> f.sdkOwner.copy(companyId = f.owner.spaceId)
                }
            assertIs<AppResult.Failed>(f.prepare()(f.owner, original, f.sdkDevice, "Phone"))
            assertEquals(0, f.sdkReads)
            assertEquals(0, f.inserts)
        }
    }

    @Test
    fun invalidNativePlatformBuildAndLabelCannotReachAcquisition() = runTest {
        for (variant in 0..3) {
            val f = SpaceRegistrationFixture()
            val build =
                when (variant) {
                    0 -> f.build.copy(platform = AppPlatform.Web)
                    1 -> f.build.copy(number = 1_000_000_000L)
                    else -> f.build
                }
            val label =
                when (variant) {
                    2 -> " "
                    3 -> "Phone\nname"
                    else -> "Phone"
                }
            assertIs<AppResult.Failed>(f.prepare(build)(f.owner, f.sdkOwner, f.sdkDevice, label))
            assertEquals(0, f.identityReads)
            assertEquals(0, f.inserts)
        }
    }

    @Test
    fun pendingLocalAndSdkReadsAreCancellableAndTimeoutDoesNotInsert() = runTest {
        val f = SpaceRegistrationFixture()
        f.beforeCurrent = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone"))
                .failure
                .kind,
        )
        f.beforeCurrent = {}
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.beforeSdk = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val task = async { f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone") }
        entered.await()
        task.cancelAndJoin()
        stopped.await()
        assertEquals(0, f.inserts)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun originalHardDeadlineWallRollbackMonotonicExpiryAndAccountReplacementRejectSdkResult() =
        runTest {
            for (variant in 0..3) {
                val f = SpaceRegistrationFixture()
                f.native.setup.session =
                    f.native.session.copy(
                        binding =
                            f.native.session.binding.copy(
                                expiresAtEpochMillis =
                                    (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                            )
                    )
                f.beforeSdk = {
                    when (variant) {
                        0 -> {
                            f.native.setup.now += 3.seconds
                            f.native.setup.session = f.native.session
                        }
                        1 -> f.native.setup.now -= 1.seconds
                        2 -> f.native.time += 2.seconds
                        else -> f.native.setup.session = null
                    }
                    AppResult.Success(f.sdkIdentity)
                }
                assertIs<AppResult.Failed>(f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone"))
                assertEquals(0, f.inserts)
            }
        }

    @Test
    fun denialAtCancellationSurvivesFailedRestrictionAndRetry() = runTest {
        for (atStatus in listOf(false, true)) {
            val f = SpaceRegistrationFixture()
            f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
            if (atStatus)
                f.beforeStatus = {
                    currentCoroutineContext().cancel()
                    AppResult.Failed(f.base.denied)
                }
            else
                f.beforeIdentity = {
                    currentCoroutineContext().cancel()
                    AppResult.Failed(f.base.denied)
                }
            val task = async { f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone") }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, f.base.restrictions)
            f.beforeIdentity = { AppResult.Failed(f.base.unavailable) }
            assertEquals(
                f.base.denied,
                assertIs<AppResult.Failed>(f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone"))
                    .failure,
            )
            assertEquals(0, f.inserts)
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun newDenialBeforeInsertPreventsPreparationAndLostInsertResponseRetainsOneOperation() =
        runTest {
            val denied = SpaceRegistrationFixture()
            denied.beforeSdk = {
                denied.base.deny()
                AppResult.Success(denied.sdkIdentity)
            }
            assertEquals(
                denied.base.denied,
                assertIs<AppResult.Failed>(
                        denied.prepare()(denied.owner, denied.sdkOwner, denied.sdkDevice, "Phone")
                    )
                    .failure,
            )
            assertEquals(0, denied.inserts)
            val f = SpaceRegistrationFixture()
            f.beforeInsert = { currentCoroutineContext().cancel() }
            val task = async { f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone") }
            task.join()
            assertTrue(task.isCancelled)
            val saved = assertNotNull(f.saved())
            f.beforeInsert = {}
            assertEquals(
                AppResult.Success(saved),
                f.prepare()(f.owner, f.sdkOwner, f.sdkDevice, "Phone"),
            )
            assertEquals(1, f.inserts)
            assertTrue(f.sent.isEmpty())
        }
}
