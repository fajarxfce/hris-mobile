@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.people.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ProfileOwnershipTest {
    @Test
    fun aDifferentPersonCannotEnterSelfStateOrTriggerPrivateProfileAcquisition() = runTest {
        val f = PeopleFixture()
        val other = f.employee.copy(accountId = "other")
        f.directoryCall = { AppResult.Success(f.directory.copy(items = listOf(other))) }
        val directory = LoadSelfEmployments(f.people, f.sessions, f.workspaces)(f.workspace.id)
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(directory).failure.code)
        f.employmentCall = { AppResult.Success(f.details.copy(employee = other)) }
        val result =
            LoadEmploymentProfile(f.people, f.sessions, f.workspaces)(
                f.workspace.id,
                other.id,
                f.date,
            )
        assertEquals("employee_not_found", assertIs<AppResult.Failed>(result).failure.code)
        assertEquals(0, f.profileReads)
    }

    @Test
    fun thePersonAndEmploymentMustReferToTheSameAccountAndIdentity() = runTest {
        val f = PeopleFixture()
        val load = LoadEmploymentProfile(f.people, f.sessions, f.workspaces)
        for (person in
            listOf(f.profile.copy(personId = "other"), f.profile.copy(accountId = "other"))) {
            f.profileCall = { AppResult.Success(person) }
            assertEquals(
                "client.invalid_response",
                assertIs<AppResult.Failed>(load(f.workspace.id, f.employee.id, f.date)).failure.code,
            )
        }
        f.profileCall = { AppResult.Success(f.profile.copy(ownerCompanyId = "original-company")) }
        assertIs<AppResult.Success<*>>(load(f.workspace.id, f.employee.id, f.date))
    }

    @Test
    fun anotherCompanySessionOrAudienceIsRejectedBeforeAcquisition() = runTest {
        for (mode in listOf("company", "account", "audience")) {
            val f = PeopleFixture()
            when (mode) {
                "company" ->
                    f.workspaces.state.value = AppResult.Success(f.workspace.copy(id = "next"))
                "account" -> f.sessions.state.value = AppResult.Success(f.session.copy(id = "next"))
                else ->
                    f.sessions.state.value =
                        AppResult.Success(
                            f.session.copy(
                                binding =
                                    f.session.binding.copy(audience = "https://different.invalid/")
                            )
                        )
            }
            assertIs<AppResult.Failed>(
                LoadSelfEmployments(f.people, f.sessions, f.workspaces)(f.workspace.id)
            )
            assertEquals(0, f.directoryReads)
        }
    }

    @Test
    fun lateDirectoryAndProfileResultsCannotCrossOwnershipOrCancellationBoundaries() = runTest {
        for (detail in listOf(false, true)) for (mode in listOf("company", "logout", "cancel")) {
            val f = PeopleFixture()
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.directory)
            }
            f.profileCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.profile)
            }
            val pending = async {
                if (detail)
                    LoadEmploymentProfile(f.people, f.sessions, f.workspaces)(
                        f.workspace.id,
                        f.employee.id,
                        f.date,
                    )
                else LoadSelfEmployments(f.people, f.sessions, f.workspaces)(f.workspace.id)
            }
            runCurrent()
            when (mode) {
                "company" ->
                    f.workspaces.state.value = AppResult.Success(f.workspace.copy(id = "next"))
                "logout" -> f.sessions.state.value = AppResult.Success(null)
                else -> pending.cancel()
            }
            release.complete(Unit)
            if (mode == "cancel") assertFailsWith<CancellationException> { pending.await() }
            else assertIs<AppResult.Failed>(pending.await())
        }
    }

    @Test
    fun classifiedFailureIsPreservedAndMalformedPagingDoesNotReachTheRepository() = runTest {
        val f = PeopleFixture()
        val failure =
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Verification required.", code = "mfa_required")
            )
        f.directoryCall = { failure }
        val load = LoadSelfEmployments(f.people, f.sessions, f.workspaces)
        assertIs<AppResult.Failed>(load(f.workspace.id, "X".repeat(33)))
        assertEquals(0, f.directoryReads)
        assertSame(failure, load(f.workspace.id))
    }
}
