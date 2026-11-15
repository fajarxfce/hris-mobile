@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.time.ExperimentalTime::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceDirectory
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceDirectoryContactObservationTest {
    @Test
    fun oneLocalContactSurvivesCredentialAgeAndUnchangedRenewalWithoutInventory() = runTest {
        val f = ContactObservationFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.onPage = { error("No network acquisition") }
        val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceContact?>>>(
            listOf(AppResult.Success(f.contact)),
            values,
        )
        repeat(5) {
            f.base.identity.value =
                AppResult.Success(
                    f.base.native.session.copy(
                        tokens = f.base.native.session.tokens.copy(accessToken = "rotated-$it")
                    )
                )
            runCurrent()
        }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.starts)
        assertEquals(1, f.readers)
        assertEquals(0, f.base.observations)
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(0, f.readers)
    }

    @Test
    fun invalidOrSelfPeerDoesNotAcquireAnyContact() = runTest {
        val f = ContactObservationFixture()
        for (peer in listOf("", "foreign", f.base.access.accountId)) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe(peer).first()).failure.kind,
            )
        }
        assertEquals(0, f.starts)
    }

    @Test
    fun foreignOrMalformedContactIsRejectedAndRemovalIsExplicit() = runTest {
        val f = ContactObservationFixture()
        for (contact in
            listOf(
                f.contact.copy(accountId = f.base.access.accountId),
                f.contact.copy(displayName = ""),
            )) {
            f.facts.value = AppResult.Success(contact)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe().first()).failure.kind,
            )
        }
        f.facts.value = AppResult.Success(null)
        assertEquals(AppResult.Success(null), f.observe().first())
    }

    @Test
    fun publicCompanyLabelsAreCapturedBeforeSuspendingOwnershipChecks() = runTest {
        val f = ContactObservationFixture()
        val companies = f.contact.companies.toMutableList()
        val expected = companies.toList()
        f.facts.value = AppResult.Success(f.contact.copy(companies = companies))
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeCurrent = {
            if (++calls == 3) {
                entered.complete(Unit)
                release.await()
            }
        }
        val value = async { f.observe().first() }
        entered.await()
        companies.clear()
        release.complete(Unit)
        assertEquals(
            expected,
            assertIs<AppResult.Success<CollaborationSpaceContact?>>(value.await()).value?.companies,
        )
    }

    @Test
    fun currentAccountReplacementDuringAContactReadRejectsTheCapturedName() = runTest {
        val f = ContactObservationFixture()
        f.source = {
            flow {
                f.currentIdentity = {
                    AppResult.Success(f.base.native.session.copy(id = "replacement"))
                }
                emit(AppResult.Success(f.contact))
            }
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.observe().first()).failure.kind,
        )
        assertEquals(0, f.readers)
    }

    @Test
    fun denialJoinsLateContactAndFailedCleanupCannotRestoreItsName() = runTest {
        val f = ContactObservationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = {
            flow {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                emit(AppResult.Success(f.contact))
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        entered.await()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        assertIs<AppResult.Failed>(f.base.directory.restrict(f.base.access, f.base.denied))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceContact?>>>(
            listOf(AppResult.Failed(f.base.denied)),
            values,
        )
        assertEquals(0, f.readers)
        job.cancelAndJoin()
    }

    @Test
    fun closingContactFailureStaysLatchedUntilObservedReadmission() = runTest {
        val f = ContactObservationFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        f.facts.value = AppResult.Failed(f.base.denied)
        runCurrent()
        f.facts.value = AppResult.Success(f.contact.copy(displayName = "Later"))
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.starts)
        f.base.deny()
        runCurrent()
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(f.facts.value, values.last())
        assertEquals(2, f.starts)
        assertEquals(1, f.maximumReaders)
        job.cancelAndJoin()
    }

    @Test
    fun transientContactFailureRecoversFromLocalPublicationWithoutPolling() = runTest {
        val f = ContactObservationFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        f.facts.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.starts)
        f.facts.value = AppResult.Success(f.contact)
        runCurrent()
        assertEquals(f.facts.value, values.last())
        assertEquals(3, values.size)
        job.cancelAndJoin()
    }

    @Test
    fun completedOwnershipStreamsStopTheExactContactReader() = runTest {
        for (sessionCompletes in listOf(false, true)) {
            val f = ContactObservationFixture()
            val finish = CompletableDeferred<Unit>()
            if (sessionCompletes)
                f.sessionSource = {
                    flow {
                        emitAll(f.base.identity.take(1))
                        finish.await()
                    }
                }
            else
                f.admissionSource = {
                    flow {
                        emitAll(f.base.gate.take(1))
                        finish.await()
                    }
                }
            val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe().toList(values)
                }
            runCurrent()
            assertEquals(1, f.readers)
            finish.complete(Unit)
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            assertEquals(0, f.readers)
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancellationDuringThePostReadGuardCannotPublishCapturedLabels() = runTest {
        val f = ContactObservationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        f.beforeCurrent = {
            if (++calls == 3)
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(values.isEmpty())
        assertEquals(0, f.readers)
    }
}

private class ContactObservationFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val contact = base.native.contact
    val facts = MutableStateFlow<AppResult<CollaborationSpaceContact?>>(AppResult.Success(contact))
    var source: () -> Flow<AppResult<CollaborationSpaceContact?>> = { facts }
    var sessionSource: () -> Flow<AppResult<Session?>> = { base.identity }
    var admissionSource: () -> Flow<AppResult<CollaborationSpaceDirectoryAdmission>> = { base.gate }
    var currentIdentity: suspend () -> AppResult<Session?> = { base.identity.value }
    var beforeCurrent: suspend () -> Unit = {}
    var starts = 0
    var readers = 0
    var maximumReaders = 0
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = admissionSource()

            override fun observeContact(access: CollaborationSpaceLocalAccess, accountId: String) =
                flow {
                    assertEquals(base.access, access)
                    assertEquals(contact.accountId, accountId)
                    starts++
                    readers++
                    maximumReaders = maxOf(maximumReaders, readers)
                    try {
                        emitAll(source())
                    } finally {
                        readers--
                    }
                }
        }
    val sessions =
        object : SessionRepository by base.sessions {
            override fun observe() = sessionSource()

            override suspend fun current(): AppResult<Session?> {
                beforeCurrent()
                return currentIdentity()
            }
        }

    fun observe(peer: String = contact.accountId) =
        ObserveCollaborationSpaceDirectory(directory, sessions, base.native.server)
            .contact(base.access, peer)
}
