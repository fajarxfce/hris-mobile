@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReplayCollaborationSpaceSetupTest {
    @Test
    fun uncertainRetryKeepsOriginalOperationAfterSameParentLoginAndBuildUpgrade() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared()
        f.beforeRetry = { AppResult.Failed(f.base.unavailable) }
        assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
        assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
        val identityReads = f.registration.identityReads
        val statusReads = f.registration.statusReads
        f.registration.beforeIdentity = { error("Uncertain retry cannot re-prepare the operation") }
        f.registration.beforeStatus = { error("Uncertain retry cannot replace observed versions") }
        f.beforeRetry = {
            AppResult.Success(CollaborationSetupReceipt(it.deviceId, it.provisioningRevision + 1))
        }
        val renewed = f.owner.copy(parent = f.owner.parent.copy(sessionId = "new-local"))
        f.registration.session(f.native.session.copy(id = renewed.parent.sessionId))
        val result =
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                    f.delivery(f.build.copy(number = 6))(renewed, original.command.id)
                )
                .value
        assertEquals(original.command, result.command)
        assertEquals(2, result.attempts)
        assertIs<CollaborationSpaceSetupOutcome.Retried>(result.outcome)
        assertEquals(listOf(original.command.request, original.command.request), f.sent)
        assertEquals(identityReads, f.registration.identityReads)
        assertEquals(statusReads, f.registration.statusReads)
        assertEquals(0, f.registration.sdkReads)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun bootstrapBuildCannotBeRewrittenAndCompletedGatewayRemainsOwnedAfterExpiry() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        f.beforeBootstrap = { AppResult.Failed(f.base.unavailable) }
        assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
        val uncertain = f.saved()
        val changed =
            assertIs<AppResult.Failed>(
                f.delivery(f.build.copy(number = 6))(f.owner, original.command.id)
            )
        assertEquals("client.collaboration_build_changed", changed.failure.code)
        assertEquals(uncertain, f.saved())
        assertEquals(1, f.sent.size)
        f.beforeBootstrap = { AppResult.Success(f.gateway(it)) }
        val completed =
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                    f.delivery()(f.owner, original.command.id)
                )
                .value
        assertIs<CollaborationSpaceSetupOutcome.Bootstrapped>(completed.outcome)
        assertEquals(original.command, completed.command)
        val fresh =
            f.owner.copy(
                parent = f.owner.parent.copy(sessionId = "fresh", nativeSessionId = f.owner.spaceId)
            )
        f.registration.session(
            f.native.session.copy(
                id = fresh.parent.sessionId,
                binding = f.native.session.binding.copy(serverId = fresh.parent.nativeSessionId),
            )
        )
        f.native.setup.now += 1_000_000.seconds
        f.native.setup.workspace = null
        assertEquals(
            AppResult.Success(completed),
            f.delivery(f.build.copy(platform = AppPlatform.Web))(fresh, original.command.id),
        )
        assertEquals(2, f.sent.size)
        assertEquals(0, f.registration.sdkReads)
    }

    @Test
    fun failedOrRejectedUncertaintyWriteCannotReachNativeHttp() = runTest {
        for (kind in CollaborationSpaceSetupKind.entries) for (fails in listOf(false, true)) {
            val f = SpaceSetupFixture()
            val original = f.prepared(kind)
            f.beforeUpdate = {
                if (fails) AppResult.Failed(f.base.storageFailure) else AppResult.Success(false)
            }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertEquals(original, f.saved())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun pendingChangedVersionsForeignProviderAndUnsupportedSpaceCannotSend() = runTest {
        for (mode in 0..3) {
            val f = SpaceSetupFixture()
            val original =
                f.prepared(
                    if (mode == 2) CollaborationSpaceSetupKind.BOOTSTRAP
                    else CollaborationSpaceSetupKind.RETRY
                )
            when (mode) {
                0 -> f.status = f.failedStatus(2)
                1 ->
                    f.registration.records.value =
                        AppResult.Success(
                            f.registered.copy(
                                command =
                                    f.registered.command.copy(
                                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                                        legacy = null,
                                    )
                            )
                        )
                2 -> {
                    val request = original.command.request as CollaborationSpaceBootstrap
                    val provider =
                        request.provider.copy(
                            legacyCompanyId = f.owner.spaceId,
                            matrixUserId =
                                "@hris_${f.owner.spaceId.replace("-", "")}_${f.owner.parent.accountId.replace("-", "")}:matrix.hris.internal",
                        )
                    f.records.value =
                        AppResult.Success(
                            original.copy(
                                command =
                                    original.command.copy(
                                        request = request.copy(provider = provider)
                                    )
                            )
                        )
                }
                else -> {
                    val fresh =
                        f.owner.copy(
                            parent = f.owner.parent.copy(nativeSessionId = f.owner.spaceId)
                        )
                    f.registration.session(
                        f.native.session.copy(
                            binding =
                                f.native.session.binding.copy(
                                    serverId = fresh.parent.nativeSessionId
                                )
                        )
                    )
                    assertIs<AppResult.Failed>(f.delivery()(fresh, original.command.id))
                }
            }
            if (mode != 3) assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun cancellationDisposesBothPendingRequestsAndRetainsUnconfirmedIntent() = runTest {
        for (kind in CollaborationSpaceSetupKind.entries) {
            val f = SpaceSetupFixture()
            val original = f.prepared(kind)
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.beforeRetry = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            f.beforeBootstrap = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val task = async { f.delivery()(f.owner, original.command.id) }
            entered.await()
            task.cancelAndJoin()
            assertTrue(stopped)
            assertEquals(original.command, f.saved()?.command)
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
            assertEquals(1, f.sent.size)
        }
    }

    @Test
    fun originalHardExpiryRollbackMonotonicExpiryAndLogoutRejectLateNativeResults() = runTest {
        for (kind in CollaborationSpaceSetupKind.entries) for (mode in 0..3) {
            val f = SpaceSetupFixture()
            val original = f.prepared(kind)
            val initial =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 5.seconds).toEpochMilliseconds()
                        )
                )
            f.registration.session(initial)
            fun change() {
                when (mode) {
                    0 -> {
                        f.native.setup.now += 6.seconds
                        f.registration.session(
                            initial.copy(
                                binding =
                                    initial.binding.copy(
                                        expiresAtEpochMillis =
                                            (f.native.setup.now + 100.seconds).toEpochMilliseconds()
                                    )
                            )
                        )
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    2 -> f.native.time += 16.seconds
                    else -> f.native.setup.session = null
                }
            }
            f.beforeRetry = {
                change()
                AppResult.Success(
                    CollaborationSetupReceipt(it.deviceId, it.provisioningRevision + 1)
                )
            }
            f.beforeBootstrap = {
                val reply = f.gateway(it)
                change()
                AppResult.Success(reply)
            }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
            assertEquals(original.command, f.saved()?.command)
        }
    }

    @Test
    fun cancelledDenialAndFailedWritesRemainDeniedOnUnavailableAndMalformedRetries() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        f.beforeUpdate = {
            if (it is CollaborationSpaceSetupOutcome.Unconfirmed && it.failure != null)
                AppResult.Failed(f.base.storageFailure)
            else AppResult.Success(true)
        }
        f.beforeRetry = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.base.denied)
        }
        supervisorScope {
            val task = async { f.delivery()(f.owner, original.command.id) }
            assertFailsWith<CancellationException> { task.await() }
        }
        f.beforeRetry = { AppResult.Failed(f.base.unavailable) }
        assertEquals(AppResult.Failed(f.base.denied), f.delivery()(f.owner, original.command.id))
        f.beforeRetry = { AppResult.Success(CollaborationSetupReceipt(f.owner.spaceId, 999)) }
        assertEquals(AppResult.Failed(f.base.denied), f.delivery()(f.owner, original.command.id))
        assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun denialDuringUncertaintyWriteStopsHttpAndAfterSuccessKeepsReceiptButNotCurrentGrant() =
        runTest {
            for (duringMark in listOf(false, true)) {
                val f = SpaceSetupFixture()
                val original = f.prepared()
                if (duringMark)
                    f.beforeUpdate = {
                        if (it is CollaborationSpaceSetupOutcome.Unconfirmed) f.base.deny()
                        AppResult.Success(true)
                    }
                else
                    f.beforeRetry = {
                        f.base.deny()
                        AppResult.Success(
                            CollaborationSetupReceipt(it.deviceId, it.provisioningRevision + 1)
                        )
                    }
                assertEquals(
                    AppResult.Failed(f.base.denied),
                    f.delivery()(f.owner, original.command.id),
                )
                if (duringMark) {
                    assertTrue(f.sent.isEmpty())
                    assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
                } else {
                    assertEquals(1, f.sent.size)
                    assertIs<CollaborationSpaceSetupOutcome.Retried>(f.saved()?.outcome)
                }
                assertEquals(0, f.base.admits)
            }
        }

    @Test
    fun lostReceiptOrGatewayWriteRecoversExactOriginalOperation() = runTest {
        for (kind in CollaborationSpaceSetupKind.entries) {
            val f = SpaceSetupFixture()
            val original = f.prepared(kind)
            f.beforeUpdate = {
                if (
                    it is CollaborationSpaceSetupOutcome.Retried ||
                        it is CollaborationSpaceSetupOutcome.Bootstrapped
                )
                    AppResult.Failed(f.base.storageFailure)
                else AppResult.Success(true)
            }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
            f.beforeUpdate = { AppResult.Success(true) }
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                f.delivery()(f.owner, original.command.id)
            )
            assertEquals(listOf(original.command.request, original.command.request), f.sent)
            assertEquals(original.command, f.saved()?.command)
        }
    }

    @Test
    fun mutexWaitAndNonCooperativeLateHttpShareFiniteInvocationBudgets() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        val entered = CompletableDeferred<Unit>()
        f.beforeBootstrap = {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(f.gateway(it))
        }
        val delivery = f.delivery()
        val first = async { delivery(f.owner, original.command.id) }
        entered.await()
        val second = async { delivery(f.owner, original.command.id) }
        advanceUntilIdle()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(second.await()).failure.kind)
        assertEquals(1, f.sent.size)
        assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
    }

    @Test
    fun wrongGatewayIdentityExpiredAccessAndExtendedNativeHardExpiryCannotConfirm() = runTest {
        for (mode in 0..3) {
            val f = SpaceSetupFixture()
            val original = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
            f.beforeBootstrap = {
                val gateway = f.gateway(it)
                AppResult.Success(
                    when (mode) {
                        0 -> gateway.copy(matrixUserId = "@other:matrix.hris.internal")
                        1 -> gateway.copy(accessToken = "hris_mx_a_" + "a".repeat(43))
                        2 ->
                            gateway.copy(
                                accessExpiresAtEpochMillis =
                                    f.native.setup.now.toEpochMilliseconds()
                            )
                        else ->
                            gateway.copy(
                                expiresAtEpochMillis =
                                    f.native.session.binding.expiresAtEpochMillis + 1
                            )
                    }
                )
            }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
            assertEquals(original.command, f.saved()?.command)
        }
    }

    @Test
    fun gatewayExpiringDuringStorageRemainsAnOwnedFactWithoutCurrentPublication() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        f.beforeBootstrap = {
            AppResult.Success(
                f.gateway(it)
                    .copy(
                        accessExpiresAtEpochMillis =
                            (f.native.setup.now + 1.seconds).toEpochMilliseconds()
                    )
            )
        }
        f.beforeUpdate = {
            if (it is CollaborationSpaceSetupOutcome.Bootstrapped) f.native.setup.now += 2.seconds
            AppResult.Success(true)
        }
        assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
        val owned = assertNotNull(f.saved())
        assertIs<CollaborationSpaceSetupOutcome.Bootstrapped>(owned.outcome)
        assertEquals(AppResult.Success(owned), f.delivery()(f.owner, original.command.id))
        assertEquals(1, f.sent.size)
        assertEquals(0, f.registration.sdkReads)
    }

    @Test
    fun pendingLocalCommandReadIsBoundedWithoutReachingHttp() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared()
        var stopped = false
        f.beforeRead = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val result = f.delivery()(f.owner, original.command.id)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result).failure.kind)
        assertTrue(stopped)
        assertTrue(f.sent.isEmpty())
        assertEquals(original, f.saved())
    }
}
