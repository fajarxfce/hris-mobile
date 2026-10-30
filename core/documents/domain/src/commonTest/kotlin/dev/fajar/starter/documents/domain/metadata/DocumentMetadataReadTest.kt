@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentEmploymentPage
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataReadTest {
    @Test
    fun localFactsReopenAfterCredentialAgeWithoutSendingAnyRequest() = runTest {
        val f = MetadataReadFixture()
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        assertEquals(1, f.headReads)
        val acquired = f.cache.stored.value
        f.base.now += 365.days
        val value =
            assertIs<AppResult.Success<DocumentMetadataView>>(
                    f.observe(f.base.workspace.id, f.id, flowOf(null)).first()
                )
                .value
        assertEquals(acquired, value.snapshot.checkpoint)
        assertEquals(3, value.snapshot.history.items.size)
        assertEquals(1, f.headReads)
        assertEquals(0, f.saves)
    }

    @Test
    fun firstAcquisitionRequiresCurrentOwnedEmploymentAndConsistentHistory() = runTest {
        val foreign = MetadataReadFixture()
        foreign.headCall = {
            AppResult.Success(foreign.head.copy(employmentId = foreign.base.session.user.id))
        }
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(foreign.refresh(foreign.base.workspace.id, foreign.id))
                .failure
                .code,
        )
        assertNull(foreign.cache.stored.value)
        assertEquals(0, foreign.historyReads)
        val inconsistent = MetadataReadFixture()
        inconsistent.historyCall = {
            AppResult.Success(inconsistent.page.copy(items = inconsistent.page.items.dropLast(1)))
        }
        assertIs<AppResult.Failed>(
            inconsistent.refresh(inconsistent.base.workspace.id, inconsistent.id)
        )
        assertNull(inconsistent.cache.stored.value)
    }

    @Test
    fun lateAccountChangeDiscardsResponseAndDoesNotFenceTheReplacement() = runTest {
        val f = MetadataReadFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.headCall = {
            entered.complete(Unit)
            release.await()
            documentFailure("document_access_denied", FailureKind.AccessDenied)
        }
        val task = async { f.refresh(f.base.workspace.id, f.id) }
        entered.await()
        f.base.sessions.value = AppResult.Success(f.base.session.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(task.await())
        assertNull(f.cache.stored.value)
        assertNull(f.admission.state.value.denial)
        assertEquals(0, f.historyReads)
    }

    @Test
    fun cancellationAndTimeoutReleaseAcquisitionWithoutPublishing() = runTest {
        val f = MetadataReadFixture()
        val entered = CompletableDeferred<Unit>()
        f.headCall = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.refresh(f.base.workspace.id, f.id) }
        entered.await()
        task.cancelAndJoin()
        assertNull(f.cache.stored.value)
        val timed = assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id))
        assertEquals(FailureKind.Timeout, timed.failure.kind)
        assertFalse(f.mutex.isLocked)
        f.headCall = { AppResult.Success(f.head) }
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
    }

    @Test
    fun latePolicyExpiryAndHistoryMfaCannotPublishFreshFacts() = runTest {
        val f = MetadataReadFixture()
        f.catalog.policyCall = {
            AppResult.Success(
                f.base.policy.copy(serverTime = f.base.now, validUntil = f.base.now + 1.seconds)
            )
        }
        f.historyCall = {
            f.base.now += 2.seconds
            AppResult.Success(f.page)
        }
        assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id))
        assertNull(f.cache.stored.value)
        val denied = MetadataReadFixture()
        denied.historyCall = { documentFailure("mfa_required", FailureKind.AccessDenied) }
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(denied.refresh(denied.base.workspace.id, denied.id))
                .failure
                .code,
        )
        assertNull(denied.cache.stored.value)
        assertEquals("mfa_required", denied.admission.state.value.denial?.code)
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(
                    denied.observe(denied.base.workspace.id, denied.id, flowOf(null)).first()
                )
                .failure
                .code,
        )
    }

    @Test
    fun aLaterDenialWinsAfterCacheCommitAndAnOlderAdmission() = runTest {
        val f = MetadataReadFixture()
        f.admission.beforeAdmit = {
            f.admission.restrict(
                f.catalog.access,
                f.id,
                Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
            )
        }
        assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id))
        assertNotNull(f.cache.stored.value)
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(f.observe(f.base.workspace.id, f.id, flowOf(null)).first())
                .failure
                .code,
        )
        assertEquals(0, f.cache.observations)
    }

    @Test
    fun failedAdmissionStorageKeepsFactsClosedThroughPendingAndFailedRetry() = runTest {
        val f = MetadataReadFixture()
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        val values = mutableListOf<AppResult<DocumentMetadataView>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id, f.id, flowOf(null)).toList(values)
            }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        f.admission.failStorage = true
        f.headCall = { documentFailure("document_access_denied", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id))
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        assertEquals(0, f.cache.activeObservers)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.headCall = {
            entered.complete(Unit)
            release.await()
            documentFailure("network", FailureKind.Network)
        }
        val retry = async { f.refresh(f.base.workspace.id, f.id) }
        entered.await()
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        release.complete(Unit)
        assertIs<AppResult.Failed>(retry.await())
        runCurrent()
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
        f.admission.failStorage = false
        f.headCall = { AppResult.Success(f.head) }
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        observer.cancelAndJoin()
        assertEquals(0, f.cache.activeObservers)
        assertEquals(0, f.saves)
    }

    @Test
    fun completedStorageSubscriptionRequiresExplicitReopening() = runTest {
        val f = MetadataReadFixture()
        f.cache.completedFailure = Failure(FailureKind.Storage, "Fixture source completed")
        val values = mutableListOf<AppResult<DocumentMetadataView>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id, f.id, flowOf(null)).toList(values)
            }
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        f.cache.completedFailure = null
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        runCurrent()
        assertEquals(1, f.cache.observations)
        assertIs<AppResult.Failed>(values.last())
        observer.cancelAndJoin()
        assertIs<AppResult.Success<*>>(f.observe(f.base.workspace.id, f.id, flowOf(null)).first())
        assertEquals(2, f.cache.observations)
    }

    @Test
    fun policyExpiryDuringCacheWriteCannotClearAnEarlierDenial() = runTest {
        expiryDuringPublication(false)
    }

    @Test
    fun policyExpiryDuringAdmissionWriteRestoresAnEarlierDenial() = runTest {
        expiryDuringPublication(true)
    }

    private suspend fun expiryDuringPublication(duringAdmission: Boolean) {
        val f = MetadataReadFixture()
        f.admission.restrict(
            f.catalog.access,
            f.id,
            Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
        )
        f.catalog.policyCall = {
            AppResult.Success(
                f.base.policy.copy(serverTime = f.base.now, validUntil = f.base.now + 1.seconds)
            )
        }
        if (duringAdmission) f.admission.beforeAdmit = { f.base.now += 2.seconds }
        else f.cache.beforeCommit = { f.base.now += 2.seconds }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id)).failure.code,
        )
        assertNotNull(f.cache.stored.value)
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(f.observe(f.base.workspace.id, f.id, flowOf(null)).first())
                .failure
                .code,
        )
    }

    @Test
    fun legacyServerDoesNotRequestUnsupportedHistory() = runTest {
        val f = MetadataReadFixture()
        f.headCall = {
            AppResult.Success(
                f.head.copy(metadata = null, metadataVersion = null, metadataSupported = false)
            )
        }
        f.catalog.employmentCall = {
            AppResult.Success(
                DocumentEmploymentPage(
                    listOf(f.catalog.employment),
                    "2026-10-30",
                    "Asia/Jakarta",
                    null,
                )
            )
        }
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        assertEquals(0, f.historyReads)
        assertFalse(assertNotNull(f.cache.stored.value).head.metadataSupported)
    }

    @Test
    fun employmentDirectoryCannotLoopOrExceedItsFiniteBudget() = runTest {
        for (loop in listOf(true, false)) {
            val f = MetadataReadFixture()
            f.catalog.employmentCall = {
                AppResult.Success(
                    DocumentEmploymentPage(
                        emptyList(),
                        "2026-10-30",
                        "Asia/Jakarta",
                        if (loop) "loop" else "page-${f.catalog.employmentReads}",
                        true,
                    )
                )
            }
            assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, f.id))
            assertEquals(if (loop) 2 else 10, f.catalog.employmentReads)
            assertNull(f.cache.stored.value)
            assertEquals(0, f.historyReads)
        }
    }

    @Test
    fun successfulResourceReadCannotClearAnUnrelatedCatalogDenial() = runTest {
        val f = MetadataReadFixture()
        f.catalog.store.restrict(
            f.catalog.access,
            Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
        )
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, f.id))
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observe(f.base.workspace.id, f.id, flowOf(null)).first())
                .failure
                .code,
        )
        assertEquals(0, f.cache.observations)
    }
}
