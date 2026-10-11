package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.usecases.PreviewLeaveRequest
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class LeavePreparationTest {
    @Test
    fun preparationNeedsOnlyLeaveAndKeepsTheServerPolicyDeadline() = runTest {
        val f = LeavePreparationFixture()
        f.base.workspaceState.value =
            AppResult.Success(
                f.base.workspace.copy(
                    policy =
                        CompanyPolicySnapshot(
                            f.base.policy.copy(enabledModules = setOf(CompanyModule.LEAVE)),
                            f.base.clock.now(),
                        )
                )
            )
        val value =
            assertIs<AppResult.Success<LeavePreparation>>(f.preview("workspace", f.selected)).value
        assertEquals(f.value, value.preview)
        assertEquals(f.selected, value.selection)
        assertEquals(f.base.clock.now() + 60.seconds, value.expiresAt)
        assertEquals(0, f.base.writes)
        assertEquals(0, f.base.observers)
        assertEquals(1, f.previewReads)
        val maintenance =
            CompanyMaintenance(f.base.clock.now() + 20.seconds, f.base.clock.now() + 40.seconds)
        f.base.policyCall = { AppResult.Success(f.base.policy.copy(maintenance = maintenance)) }
        val bounded =
            assertIs<AppResult.Success<LeavePreparation>>(f.preview("workspace", f.selected)).value
        assertEquals(maintenance.startsAt, bounded.expiresAt)
    }

    @Test
    fun unavailableTypesAreRemovedWithoutInventingACursorOrAnotherPage() = runTest {
        val f = LeavePreparationFixture()
        f.typesCall = {
            AppResult.Success(LeavePage(listOf(f.type, f.type.copy(active = false)), "NEXT"))
        }
        val page =
            assertIs<AppResult.Success<LeavePage<LeaveTypeOption>>>(
                    f.types("workspace", LeaveTypeSelection(LocalDate(2026, 10, 15)))
                )
                .value
        assertEquals(listOf(f.type), page.items)
        assertEquals("NEXT", page.nextCursor)
        assertEquals(1, f.typeReads)
    }

    @Test
    fun invalidSelectionsAndReadOnlyPermissionsNeverAcquirePreparation() = runTest {
        val f = LeavePreparationFixture()
        for (selection in
            listOf(
                f.selected.copy(employeeId = "../other"),
                f.selected.copy(typeId = "../other"),
                f.selected.copy(days = emptyList()),
                f.selected.copy(days = f.selected.days + f.selected.days),
                f.selected.copy(
                    days = listOf(f.selected.days.single().copy(date = LocalDate(1899, 1, 1)))
                ),
                f.selected.copy(
                    days =
                        listOf(
                            f.selected.days.single(),
                            f.selected.days.single().copy(date = LocalDate(2027, 10, 16)),
                        )
                ),
            )) {
            assertIs<AppResult.Failed>(f.preview("workspace", selection))
        }
        assertIs<AppResult.Failed>(f.types("workspace", LeaveTypeSelection(LocalDate(2201, 1, 1))))
        assertIs<AppResult.Failed>(
            f.types("workspace", LeaveTypeSelection(LocalDate(2026, 1, 1), "../next"))
        )
        assertEquals(0, f.base.policyReads)
        f.base.workspaceState.value =
            AppResult.Success(
                f.base.workspace.copy(permissions = setOf("leave.read", "leave.manage"))
            )
        assertEquals(
            "access_denied",
            assertIs<AppResult.Failed>(f.preview("workspace", f.selected)).failure.code,
        )
        assertEquals(
            "access_denied",
            assertIs<AppResult.Failed>(
                    f.types("workspace", LeaveTypeSelection(LocalDate(2026, 1, 1)))
                )
                .failure
                .code,
        )
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.previewReads)
    }

    @Test
    fun delayedPolicyAndTransportCannotReturnResultsOrDenialsFromAnotherCompany() = runTest {
        for (operation in listOf("types", "preview")) for (stage in
            listOf("policy", "transport")) for (denied in listOf(false, true)) {
            val f = LeavePreparationFixture()
            val release = CompletableDeferred<Unit>()
            val failure =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Denied", code = "access_denied")
                )
            if (stage == "policy")
                f.base.policyCall = {
                    release.await()
                    if (denied) failure else AppResult.Success(f.base.policy)
                }
            else {
                f.typesCall = {
                    release.await()
                    if (denied) failure else AppResult.Success(LeavePage(listOf(f.type)))
                }
                f.previewCall = {
                    release.await()
                    if (denied) failure else AppResult.Success(f.value)
                }
            }
            val pending = async {
                if (operation == "types")
                    f.types("workspace", LeaveTypeSelection(LocalDate(2026, 10, 15)))
                else f.preview("workspace", f.selected)
            }
            runCurrent()
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            release.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(0, f.base.writes)
        }
    }

    @Test
    fun cancellationRejectsLateSuccessAndEachAttemptHasAFiniteDeadline() = runTest {
        val f = LeavePreparationFixture()
        val release = CompletableDeferred<Unit>()
        f.previewCall = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.value)
        }
        val pending = async { f.preview("workspace", f.selected) }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        f.previewCall = { AppResult.Success(f.value) }
        assertIs<AppResult.Success<LeavePreparation>>(f.preview("workspace", f.selected))
        var cancelled = false
        f.typesCall = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals(
            "client.leave_timeout",
            assertIs<AppResult.Failed>(
                    f.types("workspace", LeaveTypeSelection(LocalDate(2026, 10, 15)))
                )
                .failure
                .code,
        )
        assertTrue(cancelled)
        assertEquals(1, f.typeReads)
    }

    @Test
    fun confirmationFactsKeepTheirOriginalDateSelectionDuringAPendingRead() = runTest {
        val f = LeavePreparationFixture()
        val mutable = f.selected.days.toMutableList()
        val release = CompletableDeferred<Unit>()
        f.base.policyCall = {
            release.await()
            AppResult.Success(f.base.policy)
        }
        val pending = async { f.preview("workspace", f.selected.copy(days = mutable)) }
        runCurrent()
        mutable.clear()
        release.complete(Unit)
        assertEquals(
            f.selected,
            assertIs<AppResult.Success<LeavePreparation>>(pending.await()).value.selection,
        )
        f.previewCall = { AppResult.Success(f.value.copy(employeeId = f.type.id)) }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(f.preview("workspace", f.selected)).failure.code,
        )
    }

    @Test
    fun replacingTheSessionDuringWorkspaceAcquisitionCannotStartTransport() = runTest {
        val f = LeavePreparationFixture()
        val release = CompletableDeferred<Unit>()
        val workspaces =
            object : WorkspaceRepository by f.base.workspaces {
                override suspend fun current(sessionId: String): AppResult<Workspace?> {
                    release.await()
                    return AppResult.Success(f.base.workspace)
                }
            }
        val load =
            PreviewLeaveRequest(f.remote, f.base.sessions, workspaces, f.base.clock, f.base.build)
        val pending = async { load("workspace", f.selected) }
        runCurrent()
        f.base.sessionState.value = AppResult.Success(f.base.session.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.previewReads)
    }
}
