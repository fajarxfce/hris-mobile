package dev.fajar.starter.workspace.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class WorkspaceRefreshTargetTest {
    @Test
    fun replacementCoordinatesCannotMakeOldWorkRenewAnotherOwner() = runTest {
        val f = AdmissionFixture()
        val target = target(f)
        var calls = 0
        f.accountCall = {
            calls++
            AppResult.Success(f.account)
        }
        for (changed in
            listOf(
                target.copy(sessionId = "other"),
                target.copy(workspaceId = "other"),
                target.copy(audience = "https://other.test/"),
                target.copy(accountId = "other"),
                target.copy(companyId = "other"),
            )) {
            assertEquals(
                "client.workspace_changed",
                assertIs<SyncResult.Blocked>(f.refresh(changed)).failure.code,
            )
        }
        assertEquals(0, calls)
        assertEquals(AppResult.Success(f.workspace), f.selected.value)
    }

    @Test
    fun missingOriginalSelectionCannotBeReportedAsSuccessfulRenewal() = runTest {
        for (missingSession in listOf(false, true)) {
            val f = AdmissionFixture()
            val target = target(f)
            f.accountCall = { error("No remote request without the captured owner") }
            if (missingSession) f.identity.value = AppResult.Success(null)
            else f.selected.value = AppResult.Success(null)
            assertIs<SyncResult.Blocked>(f.refresh(target))
        }
    }

    @Test
    fun anOfflineAttemptPreservesSelectionAndTheSameTargetCanRenewWhenOnline() = runTest {
        val f = AdmissionFixture()
        val target = target(f)
        val network = Failure(FailureKind.Network, "fixture offline")
        f.accountCall = { AppResult.Failed(network) }
        assertEquals(network, assertIs<SyncResult.Retry>(f.refresh(target)).failure)
        assertEquals(AppResult.Success(f.workspace), f.selected.value)
        f.accountCall = { AppResult.Success(f.account) }
        assertEquals(SyncResult.Complete, f.refresh(target))
        val renewed = assertNotNull(assertIs<AppResult.Success<Workspace?>>(f.selected.value).value)
        assertEquals(f.workspace.id, renewed.id)
        assertNotNull(renewed.policy)
    }

    @Test
    fun aReplacementDuringRenewalCannotReceiveTheOldResult() = runTest {
        for (sessionChanged in listOf(false, true)) {
            val f = AdmissionFixture()
            val target = target(f)
            val replacement = f.workspace.copy(id = "replacement")
            f.accountCall = {
                if (sessionChanged)
                    f.identity.value = AppResult.Success(f.session.copy(id = "replacement"))
                else f.selected.value = AppResult.Success(replacement)
                AppResult.Success(f.account)
            }
            assertIs<SyncResult.Blocked>(f.refresh(target))
            assertEquals(
                AppResult.Success(if (sessionChanged) f.workspace else replacement),
                f.selected.value,
            )
        }
    }

    @Test
    fun cancelledTargetedRenewalCannotPublishALateResponse() = runTest {
        val f = AdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.accountCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.account)
        }
        val job = async { f.refresh(target(f)) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(AppResult.Success(f.workspace), f.selected.value)
    }

    private fun target(f: AdmissionFixture) =
        WorkspaceRefreshTarget(
            f.session.id,
            f.workspace.id,
            f.session.binding.audience,
            f.session.user.id,
            f.workspace.company.id,
        )
}
