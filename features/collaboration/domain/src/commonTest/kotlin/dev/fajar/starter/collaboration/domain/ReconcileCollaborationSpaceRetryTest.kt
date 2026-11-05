@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReconcileCollaborationSpaceRetryTest {
    @Test
    fun observedRetryArchivesExactCompletionBeforeAnotherOperationCanBePrepared() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared()
        val completed =
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                    f.delivery()(f.owner, original.command.id)
                )
                .value
        assertEquals(AppResult.Success(false), f.reconcile()(f.owner, original.command.id))
        assertEquals(completed, f.saved())
        f.status = f.failedStatus(2)
        assertEquals(AppResult.Success(true), f.reconcile()(f.owner, original.command.id))
        assertNull(f.saved())
        assertEquals(completed, f.archives[original.command.id])
        assertEquals(AppResult.Success(true), f.reconcile()(f.owner, original.command.id))
        assertEquals(1, f.archiveWrites)
        val next = f.prepared()
        assertNotEquals(original.command.id, next.command.id)
        assertEquals(
            2,
            (next.command.request as CollaborationSpaceProvisioningRetry).provisioningRevision,
        )
        assertEquals(AppResult.Success(completed), f.delivery()(f.owner, original.command.id))
        assertEquals(1, f.sent.size)
        assertEquals(0, f.registration.sdkReads)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun incompleteGatewayAndForeignParentOrDeviceCannotRetireOriginalWork() = runTest {
        for (mode in 0..5) {
            val f = SpaceSetupFixture()
            val original =
                f.prepared(
                    if (mode == 2) CollaborationSpaceSetupKind.BOOTSTRAP
                    else CollaborationSpaceSetupKind.RETRY
                )
            when (mode) {
                1 -> f.beforeRetry = { AppResult.Failed(f.base.unavailable) }
                3,
                4,
                5 -> f.status = f.failedStatus()
            }
            if (mode != 0) f.delivery()(f.owner, original.command.id)
            val retained = f.saved()
            f.status = f.failedStatus(2)
            when (mode) {
                3 -> f.status = f.status.copy(id = f.owner.spaceId)
                4 -> f.status = f.status.copy(platform = CollaborationNativePlatform.IOS)
                5 -> {
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
                    assertIs<AppResult.Failed>(f.reconcile()(fresh, original.command.id))
                }
            }
            if (mode < 3)
                assertEquals(AppResult.Success(false), f.reconcile()(f.owner, original.command.id))
            else if (mode != 5)
                assertIs<AppResult.Failed>(f.reconcile()(f.owner, original.command.id))
            assertEquals(retained, f.saved())
            assertEquals(0, f.archiveWrites)
            assertTrue(f.archives.isEmpty())
        }
    }

    @Test
    fun failedOrLostArchiveResponseNeverLosesTheOriginalReceipt() = runTest {
        for (mode in 0..2) {
            val f = SpaceSetupFixture()
            val original = f.prepared()
            val completed =
                assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                        f.delivery()(f.owner, original.command.id)
                    )
                    .value
            f.status = f.failedStatus(2)
            f.beforeArchive = {
                when (mode) {
                    0 -> AppResult.Success(false)
                    1 -> AppResult.Failed(f.base.storageFailure)
                    else -> {
                        currentCoroutineContext().cancel()
                        AppResult.Success(true)
                    }
                }
            }
            when (mode) {
                0 ->
                    assertEquals(
                        AppResult.Success(false),
                        f.reconcile()(f.owner, original.command.id),
                    )
                1 ->
                    assertEquals(
                        AppResult.Failed(f.base.storageFailure),
                        f.reconcile()(f.owner, original.command.id),
                    )
                else ->
                    supervisorScope {
                        val task = async { f.reconcile()(f.owner, original.command.id) }
                        assertFailsWith<CancellationException> { task.await() }
                    }
            }
            if (mode < 2) assertEquals(completed, f.saved())
            else assertEquals(completed, f.archives[original.command.id])
            f.beforeArchive = { AppResult.Success(true) }
            assertEquals(AppResult.Success(true), f.reconcile()(f.owner, original.command.id))
            assertEquals(completed, f.archives[original.command.id])
            assertEquals(1, f.archiveWrites)
        }
    }

    @Test
    fun cancelledStatusDenialAndConcurrentArchiveDenialStayClosed() = runTest {
        for (duringArchive in listOf(false, true)) {
            val f = SpaceSetupFixture()
            val original = f.prepared()
            val completed =
                assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                        f.delivery()(f.owner, original.command.id)
                    )
                    .value
            f.status = f.failedStatus(2)
            if (duringArchive) {
                f.beforeArchive = {
                    f.base.deny()
                    AppResult.Success(true)
                }
                assertEquals(
                    AppResult.Failed(f.base.denied),
                    f.reconcile()(f.owner, original.command.id),
                )
                assertEquals(completed, f.archives[original.command.id])
            } else {
                f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
                f.registration.beforeStatus = {
                    currentCoroutineContext().cancel()
                    AppResult.Failed(f.base.denied)
                }
                supervisorScope {
                    val task = async { f.reconcile()(f.owner, original.command.id) }
                    assertFailsWith<CancellationException> { task.await() }
                }
                f.registration.beforeStatus = { AppResult.Failed(f.base.unavailable) }
                assertEquals(
                    AppResult.Failed(f.base.denied),
                    f.reconcile()(f.owner, original.command.id),
                )
                assertEquals(completed, f.saved())
                assertTrue(f.archives.isEmpty())
            }
            assertEquals(0, f.base.admits)
            assertEquals(1, f.sent.size)
        }
    }
}
