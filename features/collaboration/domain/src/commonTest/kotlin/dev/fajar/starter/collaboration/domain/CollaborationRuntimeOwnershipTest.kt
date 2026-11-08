@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationList
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationRuntimeOwnershipTest {
    @Test
    fun aDelayedTimerCannotRetainPastMaintenanceOrTheNativeParentDeadline() = runTest {
        repeat(2) { scenario ->
            val monotonic = TestTimeSource()
            val f = OwnershipFixture(testScheduler, monotonic)
            val start = f.now()
            val policy = checkNotNull(f.selected.policy)
            if (scenario == 1)
                f.session.value =
                    AppResult.Success(
                        f.initial.copy(
                            binding =
                                f.initial.binding.copy(
                                    expiresAtEpochMillis = (start + 3.seconds).toEpochMilliseconds()
                                )
                        )
                    )
            f.workspace.value =
                AppResult.Success(
                    f.selected.copy(
                        admission = f.selected.admission.copy(expiresAt = start + 2.seconds),
                        policy =
                            policy.copy(
                                policy =
                                    policy.policy.copy(
                                        validUntil = start + 1.seconds,
                                        maintenance =
                                            CompanyMaintenance(
                                                start + 3.seconds + 500.microseconds,
                                                start + 1.hours,
                                            ),
                                    )
                            ),
                    )
                )
            val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            try {
                runCurrent()
                assertEquals(f.owner, f.retentions.last().foreground)
                val before = f.retentions.size
                // The dispatcher resumes the one-second timer after five real monotonic seconds.
                monotonic += 5.seconds
                advanceTimeBy(1_000)
                runCurrent()
                val resumed = f.retentions.drop(before)
                assertTrue(resumed.isNotEmpty())
                assertTrue(resumed.all { it.foreground == null && it.retainedForeground == null })
                if (scenario == 1) assertTrue(resumed.all { it.parent == null })
            } finally {
                task.cancelAndJoin()
            }
        }
    }

    @Test
    fun expiredAdmissionRetainsAnExistingOwnerButNotItsGrantAcrossRenewals() = runTest {
        val f = OwnershipFixture(testScheduler)
        val policy = checkNotNull(f.selected.policy)
        fun freshWorkspace() =
            f.selected.copy(
                admission =
                    f.selected.admission.copy(checkedAt = f.now(), expiresAt = f.now() + 1.seconds),
                policy =
                    policy.copy(
                        requestedAt = f.now(),
                        policy =
                            policy.policy.copy(
                                serverTime = f.now(),
                                validUntil = f.now() + 1.seconds,
                            ),
                    ),
            )
        f.workspace.value = AppResult.Success(freshWorkspace())
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        try {
            repeat(3) {
                runCurrent()
                assertEquals(AppResult.Success(f.owner), f.observed.last())
                assertEquals(f.owner, f.retentions.last().foreground)
                assertNull(f.retentions.last().retainedForeground)
                advanceTimeBy(1_000)
                runCurrent()
                assertIs<AppResult.Failed>(f.observed.last())
                assertNull(f.retentions.last().foreground)
                assertEquals(f.owner, f.retentions.last().retainedForeground)
                f.session.value =
                    AppResult.Success(
                        f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "rotated-$it"))
                    )
                runCurrent()
                assertIs<AppResult.Failed>(f.observed.last())
                assertEquals(f.owner, f.retentions.last().retainedForeground)
                f.workspace.value = AppResult.Success(freshWorkspace())
            }
        } finally {
            task.cancelAndJoin()
        }
        assertNull(f.retentions.last().retainedForeground)
    }

    @Test
    fun maintenanceAfterFreshnessExpiryStillClosesRetentionWithoutAnyRepositoryEmission() =
        runTest {
            val f = OwnershipFixture(testScheduler)
            val start = f.now()
            val policy = checkNotNull(f.selected.policy)
            f.workspace.value =
                AppResult.Success(
                    f.selected.copy(
                        policy =
                            policy.copy(
                                policy =
                                    policy.policy.copy(
                                        validUntil = start + 1.seconds,
                                        maintenance =
                                            CompanyMaintenance(
                                                start + 3.seconds + 500.microseconds,
                                                start + 1.hours,
                                            ),
                                    )
                            )
                    )
                )
            val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            try {
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(f.owner, f.retentions.last().retainedForeground)
                assertNull(f.retentions.last().foreground)
                f.clockOffset = -1.hours.inWholeMilliseconds
                advanceTimeBy(1_999)
                runCurrent()
                assertEquals(f.owner, f.retentions.last().retainedForeground)
                advanceTimeBy(1)
                runCurrent()
                assertNull(f.retentions.last().retainedForeground)
                assertNull(f.retentions.last().foreground)
                assertEquals(f.parent, f.retentions.last().parent)
                f.session.value =
                    AppResult.Success(
                        f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "rotated"))
                    )
                runCurrent()
                assertNull(f.retentions.last().retainedForeground)
            } finally {
                task.cancelAndJoin()
            }
        }

    @Test
    fun staleWorkspaceCannotRetainAClientAgainstKnownRestrictions() = runTest {
        repeat(4) { scenario ->
            val f = OwnershipFixture(testScheduler)
            val policy = checkNotNull(f.selected.policy)
            val stale = f.selected.copy(admission = f.selected.admission.copy(expiresAt = f.now()))
            f.workspace.value =
                AppResult.Success(
                    when (scenario) {
                        0 -> stale.copy(permissions = stale.permissions - "collaboration.chat")
                        1 ->
                            stale.copy(
                                policy =
                                    policy.copy(
                                        policy = policy.policy.copy(enabledModules = emptySet())
                                    )
                            )
                        2 ->
                            stale.copy(
                                policy =
                                    policy.copy(
                                        policy =
                                            policy.policy.copy(
                                                minimumBuilds =
                                                    policy.policy.minimumBuilds.copy(
                                                        android = Long.MAX_VALUE
                                                    )
                                            )
                                    )
                            )
                        else ->
                            stale.copy(
                                policy =
                                    policy.copy(
                                        policy =
                                            policy.policy.copy(
                                                maintenance =
                                                    CompanyMaintenance(
                                                        f.now() + 1.seconds,
                                                        f.now() + 1.hours,
                                                    ),
                                                maintenanceActive = true,
                                            )
                                    )
                            )
                    }
                )
            val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            try {
                runCurrent()
                assertIs<AppResult.Failed>(f.observed.last())
                assertNull(f.retentions.last().foreground)
                assertNull(f.retentions.last().retainedForeground)
            } finally {
                task.cancelAndJoin()
            }
        }
    }

    @Test
    fun expiredWorkspaceKeepsLocalRowsOnColdEntryAndSameFamilyTokenRotation() = runTest {
        for (coldEntry in listOf(false, true)) {
            val f = OwnershipFixture(testScheduler)
            f.workspace.value =
                AppResult.Success(
                    f.selected.copy(
                        admission = f.selected.admission.copy(expiresAt = f.now() + 1.seconds)
                    )
                )
            if (coldEntry) advanceTimeBy(1_000)
            val values = mutableListOf<AppResult<CollaborationLocalList>>()
            val reading =
                backgroundScope.launch {
                    f.observeList(f.selected.id, flowOf(CollaborationListSelection()))
                        .collect(values::add)
                }
            val runtime = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            try {
                runCurrent()
                if (!coldEntry) {
                    assertEquals(f.owner, f.retentions.last().foreground)
                    advanceTimeBy(1_000)
                    runCurrent()
                    assertEquals(0, f.local.restricts)
                }
                f.session.value =
                    AppResult.Success(
                        f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "rotated"))
                    )
                runCurrent()
                assertEquals(
                    listOf(f.local.summary),
                    assertIs<AppResult.Success<CollaborationLocalList>>(values.last()).value.items,
                )
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(f.observed.last()).failure.kind,
                )
                assertEquals(0, f.local.restricts)
                assertEquals(0, f.local.admits)
                assertNull(f.local.gate.value.denial)
                assertEquals(f.parent, f.retentions.last().parent)
                assertNull(f.retentions.last().foreground)
                assertNull(f.retentions.last().deniedCompanyId)
            } finally {
                reading.cancelAndJoin()
                runtime.cancelAndJoin()
            }
            assertEquals(0, f.local.localCollectors)
            assertEquals(0, f.workspaceObservers)
        }
    }

    @Test
    fun staleAuthorityCannotReplaceOrClearRetainedDenialsEvenAfterFailedCleanup() = runTest {
        for (code in listOf("permission_denied", "mfa_required", "client.workspace_expired")) {
            val f = OwnershipFixture(testScheduler)
            val denial = Failure(FailureKind.AccessDenied, "Denied", code = code)
            val retained = CollaborationListAdmission("retained", denial)
            f.local.gate.value = retained
            f.local.restrictResult = f.setup.storageFailure
            f.onClose = { f.setup.storageFailure }
            f.workspace.value =
                AppResult.Success(
                    f.selected.copy(admission = f.selected.admission.copy(expiresAt = f.now()))
                )
            val values = mutableListOf<AppResult<CollaborationLocalList>>()
            val reading =
                backgroundScope.launch {
                    f.observeList(f.selected.id, flowOf(CollaborationListSelection()))
                        .collect(values::add)
                }
            val runtime = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            try {
                runCurrent()
                assertEquals(AppResult.Failed(denial), values.last())
                assertEquals(retained, f.local.gate.value)
                assertEquals(0, f.local.restricts)
                assertEquals(0, f.local.admits)
                assertEquals(0, f.local.localReads)
                assertEquals(f.parent, f.retentions.last().parent)
                assertNull(f.retentions.last().foreground)
            } finally {
                reading.cancelAndJoin()
                runtime.cancelAndJoin()
            }
            assertEquals(0, f.workspaceObservers)
        }
    }

    @Test
    fun typedPublicationWaitsForRetentionAndDoesNotWaitForAWorkspace() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeWorkspaceObservation = { awaitCancellation() }
        f.onClose = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(Unit)
        }
        val publications = mutableListOf<AppResult<CollaborationRuntimePublication>>()
        val job = backgroundScope.launch { f.maintain.publications().collect(publications::add) }
        try {
            entered.await()
            assertTrue(publications.isEmpty())
            release.complete(Unit)
            runCurrent()
            val published =
                assertIs<AppResult.Success<CollaborationRuntimePublication>>(publications.single())
                    .value
            assertEquals(CollaborationRuntimeRetention(f.parent, null), published.retention)
            assertEquals(AppResult.Success(null), published.ownership)
            assertEquals(AppResult.Success(Unit), published.cleanup)
            job.cancelAndJoin()
            assertNull(f.retentions.last().parent)
            assertEquals(0, f.workspaceObservers)
        } finally {
            release.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun typedPublicationSeparatesParentAndCompanyDenialFromFailedNativeCleanup() = runTest {
        val f = OwnershipFixture(testScheduler)
        f.onClose = { f.setup.storageFailure }
        val publications = mutableListOf<AppResult<CollaborationRuntimePublication>>()
        val job = backgroundScope.launch { f.maintain.publications().collect(publications::add) }
        runCurrent()
        val foreground =
            assertIs<AppResult.Success<CollaborationRuntimePublication>>(publications.last()).value
        assertEquals(f.parent, foreground.retention.parent)
        assertEquals(f.owner, foreground.retention.foreground)
        assertEquals(AppResult.Success(f.owner), foreground.ownership)
        assertEquals(f.setup.storageFailure, foreground.cleanup)
        assertEquals(f.setup.storageFailure, foreground.result)
        f.workspace.value = AppResult.Success(f.selected.copy(permissions = emptySet()))
        runCurrent()
        val denied =
            assertIs<AppResult.Success<CollaborationRuntimePublication>>(publications.last()).value
        assertEquals(f.parent, denied.retention.parent)
        assertNull(denied.retention.foreground)
        assertEquals(f.owner.companyId, denied.retention.deniedCompanyId)
        assertEquals("permission_denied", assertIs<AppResult.Failed>(denied.ownership).failure.code)
        assertEquals(f.setup.storageFailure, denied.cleanup)
        assertEquals(denied.ownership, denied.result)
        assertEquals("permission_denied", f.local.gate.value.denial?.code)
        job.cancelAndJoin()
    }

    @Test
    fun typedAndLegacyCollectorsShareOneExclusiveRuntimeObserver() = runTest {
        for (typedFirst in listOf(false, true)) {
            val f = OwnershipFixture(testScheduler)
            val job =
                backgroundScope.launch {
                    if (typedFirst) f.maintain.publications().collect() else f.maintain().collect()
                }
            runCurrent()
            val before = f.retentions.toList()
            val busy = if (typedFirst) f.maintain().first() else f.maintain.publications().first()
            assertEquals(
                "client.collaboration_owner_busy",
                assertIs<AppResult.Failed>(busy).failure.code,
            )
            assertEquals(before, f.retentions)
            assertEquals(1, f.workspaceObservers)
            job.cancelAndJoin()
            assertNull(f.retentions.last().parent)
            assertEquals(0, f.workspaceObservers)
        }
    }

    @Test
    fun typedPublicationCannotEscapeAfterCancelledLateRetention() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeWorkspaceObservation = { awaitCancellation() }
        var first = true
        f.onClose = {
            if (first) {
                first = false
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            AppResult.Success(Unit)
        }
        val publications = mutableListOf<AppResult<CollaborationRuntimePublication>>()
        val job = backgroundScope.launch { f.maintain.publications().collect(publications::add) }
        try {
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertTrue(publications.isEmpty())
            assertNull(f.retentions.last().parent)
            assertEquals(0, f.workspaceObservers)
        } finally {
            release.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun pendingInitialWorkspacePublishesItsParentBeforeAddingTheObservedForeground() = runTest {
        val f = OwnershipFixture(testScheduler)
        val release = CompletableDeferred<Unit>()
        f.beforeWorkspaceObservation = { release.await() }
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(listOf(CollaborationRuntimeRetention(f.parent, null)), f.retentions)
        assertEquals(listOf<AppResult<CollaborationOwner?>>(AppResult.Success(null)), f.observed)
        assertEquals(1, f.workspaceObservers)
        release.complete(Unit)
        runCurrent()
        assertEquals(f.owner, f.retentions.last().foreground)
        assertEquals(AppResult.Success(f.owner), f.observed.last())
        task.cancelAndJoin()
        assertEquals(0, f.workspaceObservers)
        assertNull(f.retentions.last().parent)
    }

    @Test
    fun sameParentTokenRotationKeepsForegroundWhileItsNextWorkspaceObservationIsPending() =
        runTest {
            val f = OwnershipFixture(testScheduler)
            val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            runCurrent()
            val retained = f.retentions.toList()
            f.beforeWorkspaceObservation = { awaitCancellation() }
            f.session.value =
                AppResult.Success(
                    f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "rotated"))
                )
            runCurrent()
            assertEquals(retained, f.retentions)
            assertEquals(f.owner, f.retentions.last().foreground)
            assertEquals(1, f.workspaceObservers)
            task.cancelAndJoin()
            assertEquals(0, f.workspaceObservers)
        }

    @Test
    fun logoutOrReplacementInvalidatesAParentEvenWithTheInitialWorkspaceStillPending() = runTest {
        for (logout in listOf(false, true)) {
            val f = OwnershipFixture(testScheduler)
            f.beforeWorkspaceObservation = { awaitCancellation() }
            val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            runCurrent()
            val next =
                f.initial.copy(
                    binding =
                        f.initial.binding.copy(serverId = "a1000000-0000-4000-8000-000000000099")
                )
            f.session.value = AppResult.Success(if (logout) null else next)
            runCurrent()
            if (logout) {
                assertNull(f.retentions.last().parent)
                assertEquals(0, f.workspaceObservers)
            } else {
                assertEquals(next.binding.serverId, f.retentions.last().parent?.nativeSessionId)
                assertEquals(1, f.workspaceObservers)
            }
            assertNull(f.retentions.last().foreground)
            task.cancelAndJoin()
            assertEquals(0, f.workspaceObservers)
        }
    }

    @Test
    fun hardParentExpiryStillFiresBeforeAnyInitialWorkspaceEmission() = runTest {
        val f = OwnershipFixture(testScheduler)
        f.session.value =
            AppResult.Success(
                f.initial.copy(
                    binding =
                        f.initial.binding.copy(
                            expiresAtEpochMillis = (f.now() + 1.seconds).toEpochMilliseconds()
                        )
                )
            )
        f.beforeWorkspaceObservation = { awaitCancellation() }
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(f.parent, f.retentions.last().parent)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.retentions.last().parent)
        assertIs<AppResult.Failed>(f.observed.last())
        task.cancelAndJoin()
        assertEquals(0, f.workspaceObservers)
    }

    @Test
    fun repeatedTokenAndWorkspaceObservationsCannotExtendExpiryWhenTheClockRollsBack() = runTest {
        val f = OwnershipFixture(testScheduler)
        val start = f.now()
        val session =
            f.initial.copy(
                binding =
                    f.initial.binding.copy(
                        expiresAtEpochMillis = (start + 2.seconds).toEpochMilliseconds()
                    )
            )
        f.session.value = AppResult.Success(session)
        val policy = checkNotNull(f.selected.policy)
        val workspace =
            f.selected.copy(
                admission = f.selected.admission.copy(expiresAt = start + 2.seconds),
                policy = policy.copy(policy = policy.policy.copy(validUntil = start + 1.seconds)),
            )
        f.workspace.value = AppResult.Success(workspace)
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        advanceTimeBy(500)
        f.clockOffset = -1.hours.inWholeMilliseconds
        f.session.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        f.workspace.value =
            AppResult.Success(workspace.copy(permissions = workspace.permissions + "unrelated"))
        runCurrent()
        assertEquals(f.owner, f.retentions.last().foreground)
        advanceTimeBy(500)
        runCurrent()
        assertNull(f.retentions.last().foreground)
        assertEquals(f.parent, f.retentions.last().parent)
        f.session.value =
            AppResult.Success(
                session.copy(tokens = session.tokens.copy(accessToken = "rotated-again"))
            )
        runCurrent()
        assertNull(f.retentions.last().foreground)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.retentions.last().parent)
        task.cancelAndJoin()
    }

    @Test
    fun expiredParentCannotResurrectAfterReadFailureRollbackAndALaterSameFamilyExpiry() = runTest {
        val f = OwnershipFixture(testScheduler)
        val start = f.now()
        f.session.value =
            AppResult.Success(
                f.initial.copy(
                    binding =
                        f.initial.binding.copy(
                            expiresAtEpochMillis = (start + 1.seconds).toEpochMilliseconds()
                        )
                )
            )
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.retentions.last().parent)
        f.session.value = f.setup.storageFailure
        runCurrent()
        val count = f.retentions.size
        f.clockOffset = -1.hours.inWholeMilliseconds
        f.session.value =
            AppResult.Success(
                f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "renewed"))
            )
        runCurrent()
        assertTrue(f.retentions.drop(count).all { it.parent == null && it.foreground == null })
        assertNull(f.retentions.last().parent)
        assertIs<AppResult.Failed>(f.observed.last())
        task.cancelAndJoin()
    }

    @Test
    fun aForwardClockJumpFollowedByRollbackCannotReopenTheSameParentsElapsedGrant() = runTest {
        val f = OwnershipFixture(testScheduler)
        val start = f.now()
        val session =
            f.initial.copy(
                binding =
                    f.initial.binding.copy(
                        expiresAtEpochMillis = (start + 2.seconds).toEpochMilliseconds()
                    )
            )
        f.session.value = AppResult.Success(session)
        val task = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.clockOffset = 3_000
        f.session.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "forward")))
        runCurrent()
        assertNull(f.retentions.last().parent)
        f.clockOffset = -1.hours.inWholeMilliseconds
        f.session.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rollback")))
        runCurrent()
        assertNull(f.retentions.last().parent)
        assertIs<AppResult.Failed>(f.observed.last())
        task.cancelAndJoin()
    }

    @Test
    fun leavingACompanyDropsForegroundRetentionWhileKeepingTheNativeParent() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(null, f.owner), f.closedExcept)
        f.workspace.value = AppResult.Success(null)
        runCurrent()
        assertEquals(AppResult.Success(null), f.observed.last())
        assertNull(f.closedExcept.last())
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        f.workspace.value = AppResult.Success(f.selected)
        runCurrent()
        assertEquals(AppResult.Success(f.owner), f.observed.last())
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertNull(f.retentions.last().parent)
    }

    @Test
    fun parentExpiryStillFiresAfterForegroundExpiryDespiteClockRollback() = runTest {
        val f = OwnershipFixture(testScheduler)
        val start = f.now()
        f.session.value =
            AppResult.Success(
                f.initial.copy(
                    binding =
                        f.initial.binding.copy(
                            expiresAtEpochMillis = (start + 2.seconds).toEpochMilliseconds()
                        )
                )
            )
        val policy = checkNotNull(f.selected.policy)
        f.workspace.value =
            AppResult.Success(
                f.selected.copy(
                    admission = f.selected.admission.copy(expiresAt = start + 2.seconds),
                    policy =
                        policy.copy(policy = policy.policy.copy(validUntil = start + 1.seconds)),
                )
            )
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(f.owner, f.retentions.last().foreground)
        f.clockOffset = -1.hours.inWholeMilliseconds
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.retentions.last().parent)
        job.cancelAndJoin()
    }

    @Test
    fun unavailableWorkspaceCannotTurnAValidParentIntoLogout() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.workspace.value = f.setup.storageFailure
        runCurrent()
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        assertEquals(0, f.local.restricts)
        f.session.value = f.setup.storageFailure
        runCurrent()
        assertNull(f.retentions.last().parent)
        job.cancelAndJoin()
    }

    @Test
    fun companyDenialReachesTheRetainedGateDespiteStorageAndNativeCleanupFailure() = runTest {
        val f = OwnershipFixture(testScheduler)
        f.local.restrictResult = f.setup.storageFailure
        f.onClose = { f.setup.storageFailure }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.workspace.value = AppResult.Success(f.selected.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("permission_denied", f.local.gate.value.denial?.code)
        assertEquals(1, f.local.restricts)
        assertEquals(f.parent, f.retentions.last().parent)
        assertEquals(f.owner.companyId, f.retentions.last().deniedCompanyId)
        assertEquals(
            "permission_denied",
            assertIs<AppResult.Failed>(f.observed.last()).failure.code,
        )
        job.cancelAndJoin()
    }

    @Test
    fun everyCapturedAdmissionDeadlineClosesWithoutRepositoryActivityOrClockRenewal() = runTest {
        repeat(4) { scenario ->
            val f = OwnershipFixture(testScheduler)
            val start = f.now()
            val selected = f.selected
            val policy = checkNotNull(selected.policy)
            val ending = start + 1.seconds
            f.workspace.value =
                AppResult.Success(
                    when (scenario) {
                        0 ->
                            selected.copy(
                                policy =
                                    policy.copy(policy = policy.policy.copy(validUntil = ending))
                            )
                        1 -> selected.copy(admission = selected.admission.copy(expiresAt = ending))
                        2 -> {
                            f.session.value =
                                AppResult.Success(
                                    f.initial.copy(
                                        binding =
                                            f.initial.binding.copy(
                                                expiresAtEpochMillis = ending.toEpochMilliseconds()
                                            )
                                    )
                                )
                            selected.copy(admission = selected.admission.copy(expiresAt = ending))
                        }
                        else ->
                            selected.copy(
                                policy =
                                    policy.copy(
                                        policy =
                                            policy.policy.copy(
                                                maintenance =
                                                    CompanyMaintenance(ending, ending + 1.hours)
                                            )
                                    )
                            )
                    }
                )
            val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            runCurrent()
            assertEquals(
                listOf<AppResult<CollaborationOwner?>>(
                    AppResult.Success(null),
                    AppResult.Success(f.owner),
                ),
                f.observed,
            )
            f.clockOffset = -1.hours.inWholeMilliseconds
            advanceTimeBy(999)
            runCurrent()
            assertEquals(2, f.observed.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.observed.last()).failure.code,
            )
            assertNull(f.closedExcept.last())
            val calls = f.closedExcept.size
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(calls, f.closedExcept.size)
            job.cancelAndJoin()
        }
    }

    @Test
    fun aFreshPolicyReplacesItsOldTimerWithoutCreatingASecondOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val selected = f.selected
        val policy = checkNotNull(selected.policy)
        f.workspace.value =
            AppResult.Success(
                selected.copy(
                    policy =
                        policy.copy(policy = policy.policy.copy(validUntil = f.now() + 1.seconds))
                )
            )
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        advanceTimeBy(500)
        f.workspace.value =
            AppResult.Success(
                selected.copy(
                    policy =
                        policy.copy(
                            requestedAt = f.now(),
                            policy =
                                policy.policy.copy(
                                    serverTime = f.now(),
                                    validUntil = f.now() + 2.seconds,
                                ),
                        )
                )
            )
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationOwner?>>(
                AppResult.Success(null),
                AppResult.Success(f.owner),
            ),
            f.observed,
        )
        assertEquals(listOf<CollaborationOwner?>(null, f.owner), f.closedExcept)
        advanceTimeBy(1_499)
        runCurrent()
        assertIs<AppResult.Failed>(f.observed.last())
        assertNull(f.closedExcept.last())
        job.cancelAndJoin()
    }

    @Test
    fun nativeTokenRotationDoesNotCloseTheCurrentOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.session.value =
            AppResult.Success(
                f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "renewed"))
            )
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(null, f.owner), f.closedExcept)
        assertEquals(2, f.observed.size)
        job.cancelAndJoin()
    }

    @Test
    fun permissionDenialRemainsVisibleWhenCleanupFailsAndObservationErrorsCloseAll() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.onClose = { f.setup.storageFailure }
        f.workspace.value = AppResult.Success(f.selected.copy(permissions = emptySet()))
        runCurrent()
        assertEquals(
            "permission_denied",
            assertIs<AppResult.Failed>(f.observed.last()).failure.code,
        )
        assertNull(f.closedExcept.last())
        f.session.value = f.setup.storageFailure
        runCurrent()
        assertEquals(f.setup.storageFailure, f.observed.last())
        assertNull(f.closedExcept.last())
        job.cancelAndJoin()
    }

    @Test
    fun anObsoleteCleanupResponseCannotRepublishItsEarlierOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onClose = { retained ->
            if (retained != null) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            AppResult.Success(Unit)
        }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        entered.await()
        f.workspace.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationOwner?>>(
                AppResult.Success(null),
                AppResult.Success(null),
            ),
            f.observed,
        )
        assertEquals(listOf(null, f.owner, null), f.closedExcept)
        job.cancelAndJoin()
    }

    @Test
    fun aSecondCollectorCannotAcquireOrCloseAnotherCollectorsRuntime() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        val calls = f.closedExcept.size
        assertEquals(
            "client.collaboration_owner_busy",
            assertIs<AppResult.Failed>(f.maintain().first()).failure.code,
        )
        assertEquals(calls, f.closedExcept.size)
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertEquals(
            AppResult.Success(f.owner),
            f.maintain().first { it == AppResult.Success(f.owner) },
        )
        assertNull(f.closedExcept.last())
    }

    @Test
    fun cancellationStillClosesAllOwnersInAnActiveCleanupContext() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        f.onClose = { owner ->
            if (owner != null) {
                entered.complete(Unit)
                awaitCancellation()
            }
            assertTrue(currentCoroutineContext().isActive)
            AppResult.Success(Unit)
        }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        entered.await()
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertEquals(listOf<AppResult<CollaborationOwner?>>(AppResult.Success(null)), f.observed)
    }
}

private class OwnershipFixture(
    private val scheduler: TestCoroutineScheduler,
    private val monotonic: TimeSource = scheduler.timeSource,
) {
    val setup = CollaborationCommandFixture()
    val initial =
        setup.access.initial.copy(
            user = setup.access.initial.user.copy(id = "a1000000-0000-4000-8000-000000000001"),
            binding =
                setup.access.initial.binding.copy(serverId = "a1000000-0000-4000-8000-000000000002"),
        )
    val selected =
        setup.access.selected.copy(
            admission =
                setup.access.selected.admission.copy(
                    accountId = initial.user.id,
                    familyId = initial.binding.serverId,
                )
        )
    val owner =
        CollaborationOwner(
            initial.id,
            initial.binding.audience,
            initial.user.id,
            selected.company.id,
            initial.binding.serverId,
        )
    val parent =
        CollaborationNativeParent(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            owner.nativeSessionId,
        )
    val local = LocalListFixture()
    val retentions = mutableListOf<CollaborationRuntimeRetention>()
    private val started = scheduler.currentTime
    var clockOffset = 0L

    fun now() = setup.access.now + (scheduler.currentTime - started + clockOffset).milliseconds

    val session = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))
    val workspace = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(selected))
    val observed = mutableListOf<AppResult<CollaborationOwner?>>()
    val closedExcept = mutableListOf<CollaborationOwner?>()
    var onClose: suspend (CollaborationOwner?) -> AppResult<Unit> = { AppResult.Success(Unit) }
    private val sessions =
        object : SessionRepository by setup.access.sessions {
            override fun observe() = session
        }
    var beforeWorkspaceObservation: suspend () -> Unit = {}
    var workspaceObservers = 0
    private val workspaces =
        object : WorkspaceRepository by setup.access.workspaces {
            override fun observe(sessionId: String) = flow {
                workspaceObservers++
                try {
                    beforeWorkspaceObservation()
                    emitAll(workspace)
                } finally {
                    workspaceObservers--
                }
            }
        }
    private val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun handoverIntent(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun stageHandover(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
                intent:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun updateRetention(
                retention:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
            ): AppResult<Unit> {
                retentions += retention
                return closeOtherOwners(retention.foreground)
            }

            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
            > = error("No implicit background runtime")

            override suspend fun readOwnReadReceipts(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
            > = error("No implicit read receipt lookup")

            override suspend fun sendReadReceipt(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                eventId: String,
                visibility:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
            ): AppResult<Unit> = error("No implicit read receipt delivery")

            override suspend fun synchronizeDevice(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit encryption activation")

            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit pusher activation")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No implicit history head lookup")

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No implicit history acquisition")

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
            > = error("No implicit queue acquisition")

            override suspend fun isOpen(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Boolean> = error("No implicit runtime probe")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit conversation entry")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No implicit installation")

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> {
                closedExcept.add(retainedOwner)
                return onClose(retainedOwner)
            }
        }
    private val clock =
        object : Clock {
            override fun now() = this@OwnershipFixture.now()
        }
    val observeList =
        ObserveCollaborationList(
            local.replica,
            sessions,
            workspaces,
            setup.server,
            setup.build,
            clock,
        )
    val maintain =
        MaintainCollaborationRuntimeOwnership(
            runtimes,
            local.replica,
            sessions,
            workspaces,
            setup.server,
            setup.build,
            clock,
            monotonic,
        )
}
