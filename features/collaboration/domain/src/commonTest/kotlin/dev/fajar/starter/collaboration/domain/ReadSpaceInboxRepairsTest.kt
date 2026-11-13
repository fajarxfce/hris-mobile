package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceInboxRepairsTest {
    @Test
    fun ownedRepairPageIgnoresCredentialAgeAndSelectedCompanyWithoutAcquisition() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        f.base.native.onPage = { error("No network acquisition") }
        assertEquals(AppResult.Success(f.page), f.read()(f.access))
        assertEquals<List<Pair<String?, Int>>>(listOf(null to 16), f.requests)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun confirmedDenialAndMalformedRequestsNeverReadTheRepairPage() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.read()(f.access))
        f.base.gate.value = AppResult.Success(f.base.open)
        for (limit in listOf(0, 33)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read()(f.access, limit = limit)).failure.kind,
        )
        for (cursor in listOf("", "x".repeat(4097))) assertIs<AppResult.Failed>(
            f.read()(f.access, cursor)
        )
        assertIs<AppResult.Failed>(f.read()(f.access.copy(spaceId = "invalid")))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun accountSessionAndAudienceReplacementAtReadReturnDiscardCapturedData() = runTest {
        for (kind in 0..3) {
            val f = SpaceInboxRepairUseCaseFixture()
            f.onPage = {
                f.base.identity.value =
                    AppResult.Success(
                        when (kind) {
                            0 -> null
                            1 -> f.base.native.session.copy(id = "replacement")
                            2 ->
                                f.base.native.session.copy(
                                    user =
                                        f.base.native.session.user.copy(
                                            id = f.conversation.secondAccountId
                                        )
                                )
                            else ->
                                f.base.native.session.copy(
                                    binding =
                                        f.base.native.session.binding.copy(
                                            audience = "https://foreign.example.test/"
                                        )
                                )
                        }
                    )
                AppResult.Success(f.page)
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
            )
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun denialReceivedDuringReadWinsOverSuccessfulOrFailedRepairPageResults() = runTest {
        for (failed in listOf(false, true)) {
            val f = SpaceInboxRepairUseCaseFixture()
            f.onPage = {
                f.base.deny()
                if (failed) AppResult.Failed(f.base.storageFailure) else AppResult.Success(f.page)
            }
            assertEquals(AppResult.Failed(f.base.denied), f.read()(f.access))
        }
    }

    @Test
    fun malformedOwnershipInventoryAndCursorResponsesCannotBePublished() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val foreign =
            f.reference.copy(
                intent =
                    f.reference.intent.copy(
                        owner =
                            f.reference.intent.owner.copy(spaceId = f.base.native.contact.accountId)
                    )
            )
        val malformed =
            listOf(
                f.page.copy(references = listOf(foreign)),
                f.page.copy(references = listOf(f.reference, f.reference)),
                f.page.copy(references = listOf(f.reference.copy(insertedAtRevision = 2))),
                f.page.copy(version = null),
                f.page.copy(version = f.version.copy(revision = -1)),
                f.page.copy(version = f.version.copy(lastOrdinal = -1)),
                f.page.copy(references = emptyList(), next = "next-cursor"),
                f.page.copy(
                    references =
                        listOf(
                            f.reference,
                            f.reference.copy(
                                intent = f.reference.intent.copy(id = f.id(30)),
                                insertedAtRevision = 0,
                            ),
                        )
                ),
                f.page.copy(version = f.version.copy(generation = "invalid")),
                f.page.copy(next = ""),
                f.page.copy(next = "x".repeat(4097)),
                f.page.copy(next = "same-cursor"),
            )
        for (value in malformed) {
            f.onPage = { AppResult.Success(value) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.read()(f.access, "same-cursor")).failure.kind,
            )
        }
    }

    @Test
    fun emptyPageAndGenerationRestartRemainExplicitWithoutLooping() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val empty = f.page.copy(references = emptyList(), next = null)
        f.onPage = { AppResult.Success(empty) }
        assertEquals(AppResult.Success(empty), f.read()(f.access, "cursor", 3))
        assertEquals<List<Pair<String?, Int>>>(listOf("cursor" to 3), f.requests)
        f.onPage = { AppResult.Success(CollaborationSpaceInboxRepairPage.Restart) }
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairPage.Restart),
            f.read()(f.access, "next-cursor", 3),
        )
        assertEquals(2, f.requests.size)
    }

    @Test
    fun publishedItemsDoNotAliasAMutableRepositoryList() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val references = mutableListOf(f.reference)
        f.onPage = { AppResult.Success(f.page.copy(references = references)) }
        val read =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairPage>>(f.read()(f.access))
        references.clear()
        assertEquals(
            listOf(f.reference),
            assertIs<CollaborationSpaceInboxRepairPage.Items>(read.value).references,
        )
    }

    @Test
    fun cancellationDoesNotBecomeAbsenceOrRetryTheRepairPage() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.onPage = { throw CancellationException("read cancelled") }
        assertFailsWith<CancellationException> { f.read()(f.access) }
        assertEquals(1, f.requests.size)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onPage = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.page)
            }
        }
        val job = async { f.read()(f.access) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(2, f.requests.size)
    }

    @Test
    fun identityReplacementWhileAdmissionIsSuspendedStopsBeforeInventory() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.beforeAdmission = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
        )
        assertTrue(f.requests.isEmpty())
    }
}
