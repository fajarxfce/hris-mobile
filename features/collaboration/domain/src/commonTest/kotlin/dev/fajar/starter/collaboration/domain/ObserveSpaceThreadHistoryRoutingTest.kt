@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceThreadHistoryRoutingTest {
    @Test
    fun spaceHistoryKeepsCanonicalCoordinatesAndOriginalHistoryKeepsItsActualCompany() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(legacy)
            val read =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observe()(f.access, f.id).first()
                    )
                    .value
                    .history
            assertEquals(f.access, read.access)
            assertEquals(f.id, read.conversationId)
            assertEquals(f.page(), read.page)
            if (legacy) {
                val location = assertIs<CollaborationSpaceHistoryLocation.Original>(read.location)
                assertEquals(f.room.position.companyId, location.access.companyId)
                assertEquals(f.room.position.conversationId, location.conversationId)
                assertNotEquals(f.id, location.conversationId)
                assertEquals(0, f.spaceReads)
            } else {
                assertIs<CollaborationSpaceHistoryLocation.Space>(read.location)
                assertEquals(0, f.originalReads)
            }
            assertEquals(1, f.room.bindingReads)
            assertEquals(1, f.stops)
            assertTrue(f.room.bindingWrites.isEmpty() && f.room.originalReads.isEmpty())
        }
    }

    @Test
    fun oldCredentialsAndMissingHrCompanyDoNotHideOwnedHistoryOrRestartReads() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        f.native.setup.now += 1000.days
        f.native.setup.workspace = null
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.single())
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        f.facts.value = AppResult.Success(f.page())
        runCurrent()
        assertEquals(1, values.size)
        assertEquals(1, f.originalReads)
        assertEquals(1, f.room.bindingReads)
        job.cancelAndJoin()
    }

    @Test
    fun missingMalformedOrFailedBindingNeverBecomesEmptyHistoryOrAnotherNamespace() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(legacy)
            for (binding in
                listOf(
                    AppResult.Success(null),
                    AppResult.Failed(f.base.storageFailure),
                    AppResult.Success(
                        f.room.binding.copy(
                            conversation =
                                f.room.binding.conversation.copy(
                                    id = f.room.position.conversationId
                                )
                        )
                    ),
                )) {
                f.room.retained = binding
                assertIs<AppResult.Failed>(f.observe()(f.access, f.id).first())
                assertEquals(0, f.spaceReads + f.originalReads)
            }
        }
    }

    @Test
    fun validEmptyHistoryNeedsAnExistingExactBinding() = runTest {
        val f = SpaceThreadHistoryFixture()
        val empty = CollaborationHistoryPage(emptyList(), null)
        f.facts.value = AppResult.Success(empty)
        assertEquals(
            empty,
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observe()(f.access, f.id).first()
                )
                .value
                .history
                .page,
        )
        assertEquals(1, f.room.bindingReads)
    }

    @Test
    fun invalidRequestsNeverReadBindingsAndValidWindowPassesUnchangedToBothStores() = runTest {
        val anchor = "0000000000000001/m-" + "a".repeat(64)
        for (legacy in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(legacy)
            for (window in
                listOf(
                    CollaborationHistoryWindow(limit = 151),
                    CollaborationHistoryWindow(after = "bad"),
                    CollaborationHistoryWindow(limit = 10, overlap = 10),
                    CollaborationHistoryWindow(after = anchor, before = anchor),
                )) {
                assertEquals(
                    FailureKind.Validation,
                    assertIs<AppResult.Failed>(f.observe()(f.access, f.id, window).first())
                        .failure
                        .kind,
                )
            }
            assertIs<AppResult.Failed>(f.observe()(f.access, "bad").first())
            assertIs<AppResult.Failed>(f.observe()(f.access.copy(spaceId = "bad"), f.id).first())
            assertEquals(0, f.room.bindingReads)
            for (window in
                listOf(
                    CollaborationHistoryWindow(after = anchor, limit = 150, overlap = 50),
                    CollaborationHistoryWindow(before = anchor, limit = 10, overlap = 3),
                )) {
                assertIs<AppResult.Success<*>>(f.observe()(f.access, f.id, window).first())
                assertEquals(window, f.queries.last())
            }
        }
    }

    @Test
    fun failedCompletedSourceDoesNotRetryOrRefetchBindingsOnTokenRefresh() = runTest {
        val f = SpaceThreadHistoryFixture()
        f.readFacts = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        advanceTimeBy(60_000)
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(tokens = f.native.session.tokens.copy(accessToken = "new"))
            )
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadSnapshot>>(
                AppResult.Failed(f.base.storageFailure)
            ),
            values,
        )
        assertEquals(1, f.spaceReads)
        assertEquals(1, f.room.bindingReads)
        job.cancelAndJoin()
    }
}
