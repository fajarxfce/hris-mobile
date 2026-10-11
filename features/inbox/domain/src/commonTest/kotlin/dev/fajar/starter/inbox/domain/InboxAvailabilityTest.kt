@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class InboxAvailabilityTest {
    @Test
    fun policyThatExpiredInTransitCannotStartAMutation() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxCommandFixture(InboxFixture { base + testScheduler.currentTime.milliseconds })
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
        f.inbox.policyCall = {
            delay(2.seconds)
            AppResult.Success(f.inbox.policy.copy(validUntil = base + 1.seconds))
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.replay(f.inbox.workspace.id, original.command.id))
                .failure
                .code,
        )
        assertEquals(listOf(original), f.records.value)
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun maintenanceBuildAndModuleGatesRetainTheOriginalCommandWithoutConsumingAnAttempt() =
        runTest {
            for (code in
                listOf(
                    "company_maintenance",
                    "client_update_required",
                    "company_module_disabled",
                )) {
                val f = InboxCommandFixture()
                val original = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
                val policy = f.inbox.policy
                val blocked =
                    when (code) {
                        "company_maintenance" ->
                            policy.copy(
                                maintenance =
                                    CompanyMaintenance(
                                        f.inbox.clock.now(),
                                        f.inbox.clock.now() + 10.minutes,
                                    ),
                                maintenanceActive = true,
                            )
                        "client_update_required" ->
                            policy.copy(minimumBuilds = CompanyMinimumBuilds(2, 0, 0))
                        else -> policy.copy(enabledModules = emptySet())
                    }
                f.inbox.policyCall = { AppResult.Success(blocked) }
                assertEquals(
                    code,
                    assertIs<AppResult.Failed>(f.replay(f.inbox.workspace.id, original.command.id))
                        .failure
                        .code,
                )
                assertEquals(listOf(original), f.records.value)
                assertTrue(f.transmitted.isEmpty())
                assertEquals(0, f.inbox.clearCalls)
                f.inbox.policyCall = { AppResult.Success(policy) }
                assertIs<AppResult.Success<InboxCommandRecord>>(
                    f.replay(f.inbox.workspace.id, original.command.id)
                )
                assertEquals(listOf(original.command), f.transmitted)
            }
        }

    @Test
    fun unavailablePolicyDoesNotMarkAnUnsentIntentAsAnUncertainMutation() = runTest {
        val f = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
        val failure = AppResult.Failed(Failure(FailureKind.Network, "offline"))
        f.inbox.policyCall = { failure }
        assertSame(failure, f.replay(f.inbox.workspace.id, original.command.id))
        assertEquals(listOf(original), f.records.value)
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun cancellationOrReplacementDuringPolicyLookupPreventsCommandStorageAndTransport() = runTest {
        for (cancel in listOf(false, true)) {
            val f = InboxCommandFixture()
            val original = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
            val release = CompletableDeferred<Unit>()
            f.inbox.policyCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.inbox.policy)
            }
            val pending = async { f.replay(f.inbox.workspace.id, original.command.id) }
            runCurrent()
            if (cancel) pending.cancel()
            else
                f.inbox.workspaceState.value =
                    AppResult.Success(f.inbox.workspace.copy(id = "replacement"))
            release.complete(Unit)
            if (cancel) pending.join()
            else
                assertEquals(
                    "client.workspace_changed",
                    assertIs<AppResult.Failed>(pending.await()).failure.code,
                )
            assertEquals(listOf(original), f.records.value)
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun aLateDeniedPolicyCannotClearTheReplacementCompanyButCurrentRevocationRemovesItsCache() =
        runTest {
            val f = InboxCommandFixture()
            val original = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
            val release = CompletableDeferred<Unit>()
            val denied =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "denied", code = "company_access_denied")
                )
            f.inbox.policyCall = {
                release.await()
                denied
            }
            val pending = async { f.replay(f.inbox.workspace.id, original.command.id) }
            runCurrent()
            f.inbox.workspaceState.value =
                AppResult.Success(f.inbox.workspace.copy(id = "replacement"))
            release.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(0, f.inbox.clearCalls)
            f.inbox.workspaceState.value = AppResult.Success(f.inbox.workspace)
            assertSame(denied, f.replay(f.inbox.workspace.id, original.command.id))
            assertEquals(1, f.inbox.clearCalls)
            assertEquals(listOf(original), f.records.value)
        }

    @Test
    fun aDisabledModulePausesSyncWithoutDiscardingItsCursorAndCanResumeFromIt() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val checkpoint = f.checkpoint
        f.policyCall = { AppResult.Success(f.policy.copy(enabledModules = emptySet())) }
        assertEquals(
            "company_module_disabled",
            assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure.code,
        )
        assertEquals(checkpoint, f.checkpoint)
        assertEquals(0, f.clearCalls)
        f.policyCall = { AppResult.Success(f.policy) }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        assertEquals(1, f.bootstrapCalls)
        assertEquals(checkpoint!!.generation, f.checkpoint!!.generation)
    }

    @Test
    fun cachedModuleChangesHideAnOpenInboxAndReenableItsExistingSnapshot() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val states = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Page()).collect { states.add(it) }
            }
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        CompanyPolicySnapshot(
                            f.policy.copy(enabledModules = emptySet()),
                            f.clock.now(),
                        )
                )
            )
        runCurrent()
        assertEquals(
            "company_module_disabled",
            assertIs<AppResult.Failed>(states.last()).failure.code,
        )
        f.workspaceState.value = AppResult.Success(f.workspace)
        runCurrent()
        assertEquals(
            listOf(f.item),
            assertIs<AppResult.Success<InboxCachePage>>(states.last()).value.items,
        )
        assertEquals(0, f.clearCalls)
        reader.cancelAndJoin()
    }
}
