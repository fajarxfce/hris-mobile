@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceBootstrapReplacementTest {
    private suspend fun SpaceSetupFixture.uncertain(): CollaborationSpaceSetupRecord {
        val prepared = prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        beforeBootstrap = { AppResult.Failed(base.unavailable) }
        assertIs<AppResult.Failed>(delivery()(owner, prepared.command.id))
        return assertNotNull(saved())
    }

    private suspend fun SpaceSetupFixture.replacement(
        original: CollaborationSpaceSetupRecord,
        number: Long = 6,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                replace(build.copy(number = number))(owner, original.command.id)
            )
            .value

    @Test
    fun buildUpgradeRetainsOriginalBeforeHttpAndCompletesOnlyTheReplacement() = runTest {
        val f = SpaceSetupFixture()
        val old = f.uncertain()
        val next = f.replacement(old)
        assertEquals(1, f.sent.size)
        assertEquals(CollaborationSpaceSetupOutcome.Pending, next.outcome)
        val request = assertIs<CollaborationSpaceBootstrapReplacement>(next.command.request)
        assertEquals(old.command.request, request.supersedes)
        assertEquals(6L, next.command.clientBuild)
        assertEquals(old.copy(successorId = next.command.id), f.archives[old.command.id])
        assertEquals(
            AppResult.Success(next),
            f.replace(f.build.copy(number = 6))(f.owner, old.command.id),
        )
        assertEquals(
            AppResult.Success(assertNotNull(f.archives[old.command.id])),
            f.delivery()(f.owner, old.command.id),
        )
        assertEquals(1, f.sent.size)
        val done =
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                    f.delivery(f.build.copy(number = 6))(f.owner, next.command.id)
                )
                .value
        val receipt =
            assertIs<CollaborationSpaceSetupOutcome.BootstrapReplaced>(done.outcome).receipt
        assertEquals(request.supersedes, receipt.superseded)
        assertEquals(next.command.id, receipt.replacementOperationId)
        assertEquals(listOf(old.command.request, request), f.sent)
        assertEquals(0, f.registration.sdkReads)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun lostLocalResponseFindsTheSingleDurableSuccessorWithoutSending() = runTest {
        val f = SpaceSetupFixture()
        val old = f.uncertain()
        f.afterHandoff = { AppResult.Failed(f.base.storageFailure) }
        val next = f.replacement(old)
        assertEquals(next, f.saved())
        f.registration.beforeIdentity = { error("Retained handoff must not re-prepare") }
        assertEquals(AppResult.Success(next), f.replace()(f.owner, old.command.id))
        assertEquals(1, f.archives.size)
        assertEquals(1, f.sent.size)
    }

    @Test
    fun inheritedDenialSurvivesTwoBuildUpgradesBeforeAnyReplacementHttp() = runTest {
        val f = SpaceSetupFixture()
        val old = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        f.beforeBootstrap = { AppResult.Failed(f.base.denied) }
        f.base.afterRestrict = {
            f.base.gate.value = AppResult.Success(f.base.open)
            AppResult.Failed(f.base.storageFailure)
        }
        assertIs<AppResult.Failed>(f.delivery()(f.owner, old.command.id))
        val denied = assertNotNull(f.saved())
        val next = f.replacement(denied)
        val third = f.replacement(next, 7)
        val request = assertIs<CollaborationSpaceBootstrapReplacement>(third.command.request)
        assertEquals(next.command.request.bootstrapEffect(), request.supersedes)
        assertEquals(f.base.denied.code, request.retainedRestriction?.code)
        f.beforeReplacement = { AppResult.Failed(f.base.unavailable) }
        val failed =
            assertIs<AppResult.Failed>(
                f.delivery(f.build.copy(number = 7))(f.owner, third.command.id)
            )
        assertEquals(f.base.denied.code, failed.failure.code)
        assertEquals(f.base.denied.kind, failed.failure.kind)
        assertEquals(2, f.archives.size)
        assertEquals(2, f.sent.size)
    }

    @Test
    fun lateOriginalCompletionCannotReplaceThePreparedSuccessor() = runTest {
        val f = SpaceSetupFixture()
        val old = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.beforeBootstrap = {
            entered.complete(Unit)
            finish.await()
            AppResult.Success(f.gateway(it))
        }
        val delivery = async { f.delivery()(f.owner, old.command.id) }
        entered.await()
        val next = f.replacement(assertNotNull(f.saved()))
        finish.complete(Unit)
        assertIs<AppResult.Failed>(delivery.await())
        assertEquals(next, f.saved())
        assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.archives[old.command.id]?.outcome)
    }

    @Test
    fun cancellationBeforeAndAfterAtomicHandoffNeverInventsRemoteCompletion() = runTest {
        for (after in listOf(false, true)) {
            val f = SpaceSetupFixture()
            val old = f.uncertain()
            val entered = CompletableDeferred<Unit>()
            val wait: suspend () -> AppResult<Boolean> = {
                entered.complete(Unit)
                awaitCancellation()
            }
            if (after) f.afterHandoff = wait else f.beforeHandoff = wait
            val task = launch { f.replace(f.build.copy(number = 6))(f.owner, old.command.id) }
            entered.await()
            task.cancelAndJoin()
            if (after) {
                assertNotNull(f.archives[old.command.id]?.successorId)
                assertEquals(CollaborationSpaceSetupOutcome.Pending, f.saved()?.outcome)
            } else {
                assertEquals(old, f.saved())
                assertTrue(f.archives.isEmpty())
            }
            assertEquals(1, f.sent.size)
        }
    }

    @Test
    fun retainedHandoffSurvivesOrdinaryExpiryButNotAnotherLocalAccount() = runTest {
        val f = SpaceSetupFixture()
        val old = f.uncertain()
        val next = f.replacement(old)
        f.native.setup.now += 1_000_000.seconds
        f.registration.beforeIdentity = { error("Owned archive cannot need metadata") }
        assertEquals(AppResult.Success(next), f.replace()(f.owner, old.command.id))
        run {
            f.native.setup.session = null
            f.base.identity.value = AppResult.Success(null)
        }
        assertIs<AppResult.Failed>(f.replace()(f.owner, old.command.id))
        assertEquals(next, f.saved())
    }

    @Test
    fun ownershipOrDeadlineChangeDuringStatusCannotPersistOrSend() = runTest {
        for (mode in 0..2) {
            val f = SpaceSetupFixture()
            val old = f.uncertain()
            f.registration.beforeStatus = {
                when (mode) {
                    0 ->
                        run {
                            f.native.setup.session = null
                            f.base.identity.value = AppResult.Success(null)
                        }
                    1 -> f.native.setup.now += 16.seconds
                    2 -> f.native.setup.now -= 1.seconds
                }
                AppResult.Success(CollaborationSpaceDeviceObservation(f.owner, f.status))
            }
            assertIs<AppResult.Failed>(f.replace(f.build.copy(number = 6))(f.owner, old.command.id))
            assertEquals(old, f.saved())
            assertTrue(f.archives.isEmpty())
            assertEquals(1, f.sent.size)
        }
    }

    @Test
    fun mismatchedProofCannotCompleteAndAnUncertainRetryKeepsTheExactRequest() = runTest {
        val f = SpaceSetupFixture()
        val next = f.replacement(f.uncertain())
        val request = assertIs<CollaborationSpaceBootstrapReplacement>(next.command.request)
        f.beforeReplacement = {
            AppResult.Success(
                CollaborationSpaceBootstrapReplacementReceipt(
                    it.supersedes.copy(clientBuild = 123),
                    it.operationId,
                    f.gateway(it.replacement),
                )
            )
        }
        assertIs<AppResult.Failed>(f.delivery(f.build.copy(number = 6))(f.owner, next.command.id))
        assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.saved()?.outcome)
        f.registration.beforeIdentity = {
            error("Uncertain replacement must keep the original versions")
        }
        f.beforeReplacement = {
            AppResult.Success(
                CollaborationSpaceBootstrapReplacementReceipt(
                    it.supersedes,
                    it.operationId,
                    f.gateway(it.replacement),
                )
            )
        }
        assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
            f.delivery(f.build.copy(number = 6))(f.owner, next.command.id)
        )
        assertEquals(listOf(request, request), f.sent.drop(1))
    }

    @Test
    fun competingPreparationsReturnTheSameArchivedLinkAndNeverSend() = runTest {
        val f = SpaceSetupFixture()
        val old = f.uncertain()
        var entered = 0
        val both = CompletableDeferred<Unit>()
        f.beforeHandoff = {
            entered++
            if (entered == 2) both.complete(Unit)
            both.await()
            AppResult.Success(true)
        }
        val first = async { f.replace(f.build.copy(number = 6))(f.owner, old.command.id) }
        val second = async { f.replace(f.build.copy(number = 6))(f.owner, old.command.id) }
        val a = assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(first.await()).value
        val b = assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(second.await()).value
        assertEquals(a, b)
        assertEquals(a, f.saved())
        assertEquals(a.command.id, f.archives[old.command.id]?.successorId)
        assertEquals(1, f.archives.size)
        assertEquals(1, f.sent.size)
    }

    @Test
    fun removedSuccessorDoesNotRecreateWorkAndChangedParentCannotReplaceUncertainty() = runTest {
        val f = SpaceSetupFixture()
        val old = f.uncertain()
        val foreign = f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = f.owner.spaceId))
        assertIs<AppResult.Failed>(f.replace(f.build.copy(number = 6))(foreign, old.command.id))
        assertEquals(old, f.saved())
        f.replacement(old)
        f.records.value = AppResult.Success(null)
        f.registration.beforeIdentity = { error("Missing retained successor must not re-prepare") }
        assertIs<AppResult.Failed>(f.replace()(f.owner, old.command.id))
        assertNull(f.saved())
        assertEquals(1, f.archives.size)
        assertEquals(1, f.sent.size)
    }
}
