@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.people.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.usecases.LoadCompanyDirectory
import dev.fajar.starter.people.domain.usecases.LoadDirectoryDepartments
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CompanyDirectoryAccessTest {
    @Test
    fun departmentLookupRequiresDirectoryAdmissionAndBoundsSearchBeforeIo() = runTest {
        val f = PeopleFixture()
        val load = LoadDirectoryDepartments(f.people, f.sessions, f.workspaces)
        assertIs<AppResult.Failed>(load(f.workspace.id))
        assertEquals(0, f.departmentReads)
        f.workspaces.state.value =
            AppResult.Success(f.workspace.copy(permissions = setOf("people.directory.read")))
        assertIs<AppResult.Failed>(load(f.workspace.id, "x".repeat(121)))
        assertEquals(0, f.departmentReads)
        f.departmentsCall = { query ->
            assertEquals("Engine", query)
            AppResult.Success(
                dev.fajar.starter.people.domain.entities.DirectoryDepartments(
                    listOf(f.department),
                    false,
                )
            )
        }
        assertIs<AppResult.Success<*>>(load(f.workspace.id, " Engine "))
        val employees = LoadCompanyDirectory(f.people, f.sessions, f.workspaces)
        assertIs<AppResult.Failed>(employees(f.workspace.id, departmentId = "invalid"))
        assertEquals(0, f.companyDirectoryReads)
        assertIs<AppResult.Success<*>>(employees(f.workspace.id, departmentId = f.department.id))
        assertEquals(f.department.id, f.selectedDepartment)
    }

    @Test
    fun departmentLookupDiscardsLateResultsAndFailuresAfterScopeChangesOrDisposal() = runTest {
        for (failed in listOf(false, true)) for (change in
            listOf("permission", "company", "logout", "cancel")) {
            val f = PeopleFixture()
            val allowed = f.workspace.copy(permissions = setOf("people.directory.read"))
            f.workspaces.state.value = AppResult.Success(allowed)
            val release = CompletableDeferred<Unit>()
            f.departmentsCall = {
                withContext(NonCancellable) { release.await() }
                if (failed) AppResult.Failed(Failure(FailureKind.Network, "Offline"))
                else
                    AppResult.Success(
                        dev.fajar.starter.people.domain.entities.DirectoryDepartments(
                            listOf(f.department),
                            false,
                        )
                    )
            }
            val pending = async {
                LoadDirectoryDepartments(f.people, f.sessions, f.workspaces)(f.workspace.id)
            }
            runCurrent()
            when (change) {
                "permission" -> f.workspaces.state.value = AppResult.Success(f.workspace)
                "company" ->
                    f.workspaces.state.value = AppResult.Success(allowed.copy(id = "other"))
                "logout" -> f.sessions.state.value = AppResult.Success(null)
                else -> pending.cancel()
            }
            release.complete(Unit)
            if (change == "cancel") assertFailsWith<CancellationException> { pending.await() }
            else
                assertTrue(
                    assertIs<AppResult.Failed>(pending.await()).failure.kind != FailureKind.Network
                )
        }
    }

    @Test
    fun directoryNeedsItsOwnGrantAndPeopleAvailabilityBeforeAcquiringData() = runTest {
        val f = PeopleFixture()
        val load = LoadCompanyDirectory(f.people, f.sessions, f.workspaces)
        assertEquals("access_denied", assertIs<AppResult.Failed>(load(f.workspace.id)).failure.code)
        assertEquals(0, f.companyDirectoryReads)
        for (permission in listOf("people.directory.read", "people.read")) {
            f.workspaces.state.value =
                AppResult.Success(f.workspace.copy(permissions = setOf(permission)))
            assertIs<AppResult.Success<*>>(load(f.workspace.id))
        }
        val snapshot = requireNotNull(f.workspace.policy)
        f.workspaces.state.value =
            AppResult.Success(
                f.workspace.copy(
                    permissions = setOf("people.directory.read"),
                    policy =
                        snapshot.copy(
                            policy =
                                snapshot.policy.copy(
                                    enabledModules =
                                        CompanyModule.entries.toSet() - CompanyModule.PEOPLE
                                )
                        ),
                )
            )
        assertEquals(
            "company_module_disabled",
            assertIs<AppResult.Failed>(load(f.workspace.id)).failure.code,
        )
        assertEquals(2, f.companyDirectoryReads)
        assertEquals(0, f.profileReads)
    }

    @Test
    fun pendingResultsAndFailuresCannotOutliveAccessScopeOrCancellation() = runTest {
        for (failure in listOf(false, true)) for (change in
            listOf("permission", "company", "logout", "cancel")) {
            val f = PeopleFixture()
            val allowed = f.workspace.copy(permissions = setOf("people.directory.read"))
            f.workspaces.state.value = AppResult.Success(allowed)
            val release = CompletableDeferred<Unit>()
            f.companyDirectoryCall = { _, _ ->
                withContext(NonCancellable) { release.await() }
                if (failure) AppResult.Failed(Failure(FailureKind.Network, "Offline."))
                else AppResult.Success(f.companyDirectory)
            }
            val pending = async {
                LoadCompanyDirectory(f.people, f.sessions, f.workspaces)(f.workspace.id)
            }
            runCurrent()
            when (change) {
                "permission" -> f.workspaces.state.value = AppResult.Success(f.workspace)
                "company" -> f.workspaces.state.value = AppResult.Success(allowed.copy(id = "next"))
                "logout" -> f.sessions.state.value = AppResult.Success(null)
                else -> pending.cancel()
            }
            release.complete(Unit)
            if (change == "cancel") assertFailsWith<CancellationException> { pending.await() }
            else
                assertTrue(
                    assertIs<AppResult.Failed>(pending.await()).failure.kind != FailureKind.Network
                )
        }
    }

    @Test
    fun searchAndPagingAreBoundedAndClassifiedFailuresArePreserved() = runTest {
        val f = PeopleFixture()
        f.workspaces.state.value =
            AppResult.Success(f.workspace.copy(permissions = setOf("people.directory.read")))
        val load = LoadCompanyDirectory(f.people, f.sessions, f.workspaces)
        assertIs<AppResult.Failed>(load(f.workspace.id, query = "x".repeat(121)))
        for (after in listOf("", " ", "x".repeat(33))) assertIs<AppResult.Failed>(
            load(f.workspace.id, after = after)
        )
        assertEquals(0, f.companyDirectoryReads)
        val failure =
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Verification required.", code = "mfa_required")
            )
        f.companyDirectoryCall = { query, after ->
            assertEquals("Sam & Co", query)
            assertEquals("E01", after)
            failure
        }
        assertSame(failure, load(f.workspace.id, query = " Sam & Co ", after = "E01"))
    }
}
