package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimeCommandPreparationTest {
    @Test
    fun competingConfirmationsRecoverOneOriginalPlanAndCannotOverwriteItsInput() = runTest {
        val f = OvertimeCommandFixture()
        val release = CompletableDeferred<Unit>()
        f.beforeInsert = { release.await() }
        val first = async { f.queue(" Inventory count ") }
        val duplicate = async { f.queue() }
        runCurrent()
        release.complete(Unit)
        val stored = assertIs<AppResult.Success<OvertimeCommandRecord>>(first.await()).value
        assertEquals(
            stored,
            assertIs<AppResult.Success<OvertimeCommandRecord>>(duplicate.await()).value,
        )
        assertEquals(1, f.inserts)
        assertEquals("Inventory count", stored.command.reason)
        assertEquals(
            f.prepared.review.revision,
            assertIs<OvertimeCommandPayload.Plan>(stored.command.payload).planningRevision,
        )
        assertEquals(
            "client.overtime_command_pending",
            assertIs<AppResult.Failed>(f.queue("Other reason")).failure.code,
        )
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun actualAndWithdrawalShareTheSameRequestSlotAndPinTheObservedVersion() = runTest {
        val f = OvertimeCommandFixture()
        val proof =
            f.snapshot.copy(
                value =
                    f.snapshot.value.copy(
                        details =
                            f.overtime.details.copy(request = f.overtime.request.copy(version = 7))
                    )
            )
        val result = f.queueAction(OvertimeRequestedAction.Actual(f.interval), proof)
        val stored = assertIs<AppResult.Success<OvertimeCommandRecord>>(result).value
        assertEquals(
            7,
            assertIs<OvertimeCommandPayload.Actual>(stored.command.payload).expectedVersion,
        )
        assertEquals(f.overtime.request.id, stored.command.requestId)
        assertEquals(
            "client.overtime_command_pending",
            assertIs<AppResult.Failed>(f.queueAction(proof = proof)).failure.code,
        )
        assertEquals(listOf(stored), f.records.value)
    }

    @Test
    fun invalidIntervalsActionsReasonsAndReadOnlyAccessCannotCreateCommands() = runTest {
        val f = OvertimeCommandFixture()
        assertIs<AppResult.Failed>(f.queue(""))
        assertIs<AppResult.Failed>(f.queue("x".repeat(1_001)))
        assertIs<AppResult.Failed>(
            f.queue(proof = f.prepared.copy(review = f.prepared.review.copy(revision = "invalid")))
        )
        assertIs<AppResult.Failed>(
            f.queueAction(
                OvertimeRequestedAction.Actual(
                    f.interval.copy(endsAt = f.base.clock.now() + 60.seconds)
                )
            )
        )
        assertIs<AppResult.Failed>(
            f.queueAction(
                proof =
                    f.snapshot.copy(
                        value =
                            f.snapshot.value.copy(
                                details = f.overtime.details.copy(actions = emptySet())
                            )
                    )
            )
        )
        f.base.workspaceState.value =
            AppResult.Success(f.workspace.copy(permissions = setOf("overtime.read")))
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.queue()).failure.code)
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.queueAction()).failure.code)
        assertEquals(0, f.inserts)
    }

    @Test
    fun expiredPlanningAndRequestProofsAreRecheckedAfterPendingStorage() = runTest {
        for (action in listOf(false, true)) {
            var now = Instant.parse("2026-10-12T13:00:00Z")
            val f = OvertimeCommandFixture { now }
            f.beforeObserve = { now += 2.seconds }
            val result =
                if (action) f.queueAction(proof = f.snapshot.copy(refreshAfter = now + 1.seconds))
                else f.queue(proof = f.prepared.copy(expiresAt = now + 1.seconds))
            assertIs<AppResult.Failed>(result)
            assertEquals(0, f.inserts)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun scopeSessionAndPermissionChangesPreventLateAdmissionForBothCommands() = runTest {
        for (action in listOf(false, true)) for (change in
            listOf("scope", "session", "permission")) {
            val f = OvertimeCommandFixture()
            val release = CompletableDeferred<Unit>()
            f.beforeObserve = { release.await() }
            val pending = async { if (action) f.queueAction() else f.queue() }
            runCurrent()
            when (change) {
                "scope" ->
                    f.base.workspaceState.value =
                        AppResult.Success(f.workspace.copy(id = "replacement"))
                "session" ->
                    f.base.sessionState.value =
                        AppResult.Success(f.base.session.copy(id = "replacement"))
                else ->
                    f.base.workspaceState.value =
                        AppResult.Success(f.workspace.copy(permissions = setOf("overtime.read")))
            }
            release.complete(Unit)
            assertIs<AppResult.Failed>(pending.await())
            assertEquals(0, f.inserts)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun cancellationDisposesPendingObservationAndCannotPublishLateInput() = runTest {
        for (action in listOf(false, true)) {
            val f = OvertimeCommandFixture()
            val release = CompletableDeferred<Unit>()
            f.beforeObserve = { withContext(NonCancellable) { release.await() } }
            val pending = async { if (action) f.queueAction() else f.queue() }
            runCurrent()
            pending.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(0, f.inserts)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun lostSaveResponseRestoresTheCanonicalCommandOnTheNextConfirmation() = runTest {
        for (action in listOf(false, true)) {
            val f = OvertimeCommandFixture()
            f.insertionResponseLost = true
            assertIs<AppResult.Failed>(if (action) f.queueAction() else f.queue())
            val stored = f.records.value.single()
            f.insertionResponseLost = false
            assertEquals(
                stored,
                assertIs<AppResult.Success<OvertimeCommandRecord>>(
                        if (action) f.queueAction() else f.queue()
                    )
                    .value,
            )
            assertEquals(1, f.inserts)
        }
    }
}
