package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceConversationCatalogTest {
    @Test
    fun ownedCatalogIgnoresCredentialAgeAndSelectedCompanyWithoutAcquisition() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        f.base.native.onPage = { error("No network acquisition") }
        assertEquals(AppResult.Success(f.page), f.read()(f.access))
        assertEquals<List<Pair<String?, Int>>>(listOf(null to 50), f.requests)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun confirmedDenialAndMalformedRequestsNeverReadTheCatalog() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.read()(f.access))
        f.base.gate.value = AppResult.Success(f.base.open)
        for (limit in listOf(0, 51)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read()(f.access, rawLimit = limit)).failure.kind,
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
            val f = SpaceCatalogUseCaseFixture()
            f.onRead = {
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
    fun denialReceivedDuringReadWinsOverSuccessfulOrFailedCatalogResults() = runTest {
        for (failed in listOf(false, true)) {
            val f = SpaceCatalogUseCaseFixture()
            f.onRead = {
                f.base.deny()
                if (failed) AppResult.Failed(f.base.storageFailure) else AppResult.Success(f.page)
            }
            assertEquals(AppResult.Failed(f.base.denied), f.read()(f.access))
        }
    }

    @Test
    fun malformedOwnershipInventoryAndCursorResponsesCannotBePublished() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        val foreign = f.conversation.copy(spaceId = f.base.native.contact.accountId)
        val malformed =
            listOf(
                f.page.copy(items = listOf(foreign)),
                f.page.copy(items = listOf(f.conversation, f.conversation)),
                f.page.copy(
                    items = listOf(f.conversation, f.conversation.copy(id = f.base.revision))
                ),
                f.page.copy(version = null),
                f.page.copy(version = f.version.copy(revision = -1)),
                f.page.copy(version = f.version.copy(generation = "invalid")),
                f.page.copy(next = ""),
                f.page.copy(next = "x".repeat(4097)),
                f.page.copy(next = "same-cursor"),
            )
        for (value in malformed) {
            f.onRead = { AppResult.Success(value) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.read()(f.access, "same-cursor")).failure.kind,
            )
        }
    }

    @Test
    fun emptyContinuationsAndGenerationRestartRemainExplicitWithoutLooping() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        val empty = f.page.copy(items = emptyList(), next = "next-cursor", changed = true)
        f.onRead = { AppResult.Success(empty) }
        assertEquals(AppResult.Success(empty), f.read()(f.access, "cursor", 3))
        assertEquals<List<Pair<String?, Int>>>(listOf("cursor" to 3), f.requests)
        f.onRead = { AppResult.Success(CollaborationSpaceConversationCatalogRead.Restart) }
        assertEquals(
            AppResult.Success(CollaborationSpaceConversationCatalogRead.Restart),
            f.read()(f.access, "next-cursor", 3),
        )
        assertEquals(2, f.requests.size)
    }

    @Test
    fun publishedItemsDoNotAliasAMutableRepositoryList() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        val items = mutableListOf(f.conversation)
        f.onRead = { AppResult.Success(f.page.copy(items = items)) }
        val read =
            assertIs<AppResult.Success<CollaborationSpaceConversationCatalogRead>>(
                f.read()(f.access)
            )
        items.clear()
        assertEquals(
            listOf(f.conversation),
            assertIs<CollaborationSpaceConversationCatalogRead.Page>(read.value).items,
        )
    }

    @Test
    fun cancellationDoesNotBecomeAbsenceOrRetryTheInventory() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.onRead = { throw CancellationException("read cancelled") }
        assertFailsWith<CancellationException> { f.read()(f.access) }
        assertEquals(1, f.requests.size)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onRead = {
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
        val f = SpaceCatalogUseCaseFixture()
        f.base.beforeAdmission = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read()(f.access)).failure.kind,
        )
        assertTrue(f.requests.isEmpty())
    }
}
