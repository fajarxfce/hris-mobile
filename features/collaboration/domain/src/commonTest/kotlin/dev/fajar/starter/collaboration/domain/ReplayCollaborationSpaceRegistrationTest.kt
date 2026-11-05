@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReplayCollaborationSpaceRegistrationTest {
    @Test
    fun firstSendRechecksSdkAndUncertainRetryKeepsOperationWithoutReopeningFrozenRuntime() =
        runTest {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            f.beforeSdk = { AppResult.Failed(f.base.unavailable) }
            assertIs<AppResult.Failed>(
                f.delivery()(f.owner, f.owner.parent.nativeSessionId, original.command.id)
            )
            assertEquals(original, f.saved())
            assertTrue(f.sent.isEmpty())
            f.beforeSdk = { AppResult.Success(f.sdkIdentity) }
            f.beforeRegister = { AppResult.Failed(f.base.unavailable) }
            assertIs<AppResult.Failed>(
                f.delivery()(f.owner, f.owner.parent.nativeSessionId, original.command.id)
            )
            val reads = f.sdkReads
            f.beforeSdk = { error("Uncertain original must not reopen a frozen SDK") }
            f.beforeRegister = { AppResult.Success(f.receipt.copy(version = 2)) }
            val renewed = f.owner.copy(parent = f.owner.parent.copy(sessionId = "new-local-login"))
            f.session(f.native.session.copy(id = renewed.parent.sessionId))
            val result =
                assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord>>(
                        f.delivery(f.build.copy(number = 6))(
                            renewed,
                            original.command.binding.nativeSessionId,
                            original.command.id,
                        )
                    )
                    .value
            assertEquals(original.command, result.command)
            assertEquals(2, result.attempts)
            assertIs<CollaborationSpaceRegistrationOutcome.Registered>(result.outcome)
            assertEquals(listOf(original.command, original.command), f.sent)
            assertEquals(reads, f.sdkReads)
            assertEquals(0, f.base.admits)
        }

    @Test
    fun changedActualKeysAndUnsupportedSpaceEnrollmentCannotSend() = runTest {
        for (space in listOf(false, true)) {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            if (space)
                f.records.value =
                    AppResult.Success(
                        original.copy(
                            command =
                                original.command.copy(
                                    origin = CollaborationSpaceDeviceOrigin.SPACE,
                                    legacy = null,
                                )
                        )
                    )
            else
                f.beforeSdk = {
                    AppResult.Success(f.sdkIdentity.copy(fingerprint = "b".repeat(64)))
                }
            assertIs<AppResult.Failed>(
                f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
            )
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun failedUncertaintyWriteNeverReachesHttp() = runTest {
        for (fails in listOf(false, true)) {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            f.beforeUpdate = {
                if (fails) AppResult.Failed(f.base.storageFailure) else AppResult.Success(false)
            }
            assertIs<AppResult.Failed>(
                f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
            )
            assertTrue(f.sent.isEmpty())
            assertEquals(original, f.saved())
        }
    }

    @Test
    fun differentNativeParentCannotReplayButConfirmedFactsRemainReadableAfterLoginAndExpiry() =
        runTest {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            val fresh =
                f.owner.copy(
                    parent =
                        f.owner.parent.copy(sessionId = "fresh", nativeSessionId = f.owner.spaceId)
                )
            f.session(
                f.native.session.copy(
                    id = fresh.parent.sessionId,
                    binding = f.native.session.binding.copy(serverId = fresh.parent.nativeSessionId),
                )
            )
            assertIs<AppResult.Failed>(
                f.delivery()(fresh, original.command.binding.nativeSessionId, original.command.id)
            )
            assertTrue(f.sent.isEmpty())
            val confirmed =
                original.copy(
                    revision = 2,
                    attempts = 1,
                    outcome = CollaborationSpaceRegistrationOutcome.Registered(f.receipt),
                )
            f.records.value = AppResult.Success(confirmed)
            f.native.setup.now += 1_000_000.seconds
            assertEquals(
                AppResult.Success(confirmed),
                f.delivery(f.build.copy(platform = AppPlatform.Web))(
                    fresh,
                    original.command.binding.nativeSessionId,
                    original.command.id,
                ),
            )
            assertTrue(f.sent.isEmpty())
        }

    @Test
    fun cancellationDuringHttpAndNonCooperativeLateSuccessKeepUnconfirmedRecord() = runTest {
        for (late in listOf(false, true)) {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            val entered = CompletableDeferred<Unit>()
            f.beforeRegister = {
                entered.complete(Unit)
                if (late) {
                    currentCoroutineContext().cancel()
                    AppResult.Success(f.receipt)
                } else awaitCancellation()
            }
            val task = async {
                f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
            }
            entered.await()
            if (late) {
                task.join()
                assertTrue(task.isCancelled)
            } else task.cancelAndJoin()
            assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
                assertNotNull(f.saved()).outcome
            )
            assertEquals(original.command, f.saved()?.command)
        }
    }

    @Test
    fun mutexWaitAndNonCooperativeHttpShareOriginalTimeoutWithoutSecondSend() = runTest {
        val f = SpaceRegistrationFixture()
        val original = f.prepared()
        f.beforeRegister = {
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(f.receipt)
        }
        val replay = f.delivery()
        val first = async {
            replay(f.owner, original.command.binding.nativeSessionId, original.command.id)
        }
        runCurrent()
        val second = async {
            replay(f.owner, original.command.binding.nativeSessionId, original.command.id)
        }
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(second.await()).failure.kind)
        advanceUntilIdle()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        assertEquals(1, f.sent.size)
        assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
            assertNotNull(f.saved()).outcome
        )
    }

    @Test
    fun originalHardDeadlineWallRollbackMonotonicExpiryAndLogoutRejectHttpSuccess() = runTest {
        for (variant in 0..3) {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.beforeRegister = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    2 -> f.native.time += 2.seconds
                    else -> f.native.setup.session = null
                }
                AppResult.Success(f.receipt)
            }
            assertIs<AppResult.Failed>(
                f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
            )
            assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
                assertNotNull(f.saved()).outcome
            )
        }
    }

    @Test
    fun deniedCancelledResponseAndFailedPersistenceStayDeniedThroughFailedAndMalformedRetries() =
        runTest {
            val f = SpaceRegistrationFixture()
            val original = f.prepared()
            f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
            f.beforeRegister = {
                currentCoroutineContext().cancel()
                AppResult.Failed(f.base.denied)
            }
            val task = async {
                f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
            }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, f.base.restrictions)
            f.beforeUpdate = {
                if (it is CollaborationSpaceRegistrationOutcome.Unconfirmed && it.failure != null)
                    AppResult.Failed(f.base.storageFailure)
                else AppResult.Success(true)
            }
            for (response in
                listOf(
                    AppResult.Failed(f.base.unavailable),
                    AppResult.Success(CollaborationSetupReceipt("invalid", 0)),
                )) {
                f.beforeRegister = { response }
                assertEquals(
                    f.base.denied,
                    assertIs<AppResult.Failed>(
                            f.delivery()(
                                f.owner,
                                original.command.binding.nativeSessionId,
                                original.command.id,
                            )
                        )
                        .failure,
                )
            }
            assertEquals(0, f.base.admits)
        }

    @Test
    fun concurrentDenialBeforeMarkStopsSendAndAfterResponseRetainsReceiptWithoutGrant() = runTest {
        val before = SpaceRegistrationFixture()
        val pending = before.prepared()
        before.beforeSdk = {
            before.base.deny()
            AppResult.Success(before.sdkIdentity)
        }
        assertEquals(
            before.base.denied,
            assertIs<AppResult.Failed>(
                    before.delivery()(
                        before.owner,
                        pending.command.binding.nativeSessionId,
                        pending.command.id,
                    )
                )
                .failure,
        )
        assertTrue(before.sent.isEmpty())
        val f = SpaceRegistrationFixture()
        val original = f.prepared()
        f.beforeRegister = {
            f.base.deny()
            AppResult.Success(f.receipt)
        }
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Failed>(
                    f.delivery()(
                        f.owner,
                        original.command.binding.nativeSessionId,
                        original.command.id,
                    )
                )
                .failure,
        )
        assertIs<CollaborationSpaceRegistrationOutcome.Registered>(assertNotNull(f.saved()).outcome)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun failedReceiptWriteCanRecoverOnlyThroughSameOriginalOperation() = runTest {
        val f = SpaceRegistrationFixture()
        val original = f.prepared()
        f.beforeUpdate = {
            if (it is CollaborationSpaceRegistrationOutcome.Registered)
                AppResult.Failed(f.base.storageFailure)
            else AppResult.Success(true)
        }
        assertIs<AppResult.Failed>(
            f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
        )
        assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
            assertNotNull(f.saved()).outcome
        )
        f.beforeUpdate = { AppResult.Success(true) }
        assertIs<AppResult.Success<*>>(
            f.delivery()(f.owner, original.command.binding.nativeSessionId, original.command.id)
        )
        assertEquals(listOf(original.command, original.command), f.sent)
    }
}
