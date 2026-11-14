package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceHistoryChangesTest {
    @Test
    fun offlineCanonicalReadUsesTheActualGroupOrCompanySource() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceHistoryChangesFixture(legacy)
            f.base.native.setup.now += 365.days
            f.base.native.setup.workspace = null
            f.base.native.setup.beforeWorkspace = { error("No selected company") }
            f.base.native.onPage = { error("No network acquisition") }
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryChangesRead(f.owner, f.page)),
                f.result(),
            )
            assertEquals<
                List<
                    Triple<
                        CollaborationSpaceHistoryLocation,
                        CollaborationHistoryChangeCursor?,
                        Int,
                    >
                >
            >(
                listOf(Triple(f.source, null, 16)),
                f.calls,
            )
            assertEquals(1, f.boundReads)
            assertEquals(0, f.base.native.pageCalls)
            assertEquals(
                if (legacy) CollaborationHistoryChangeOwnerKind.COMPANY
                else CollaborationHistoryChangeOwnerKind.SPACE,
                f.owner.kind,
            )
            if (legacy) {
                assertEquals(f.base.room.position.companyId, f.owner.organizationId)
                assertEquals(f.base.room.position.conversationId, f.owner.conversationId)
            }
        }
    }

    @Test
    fun invalidRequestsNeverReadBindingOrHistory() = runTest {
        val f = SpaceHistoryChangesFixture()
        for (limit in listOf(0, 17)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.result(limit = limit)).failure.kind,
        )
        assertIs<AppResult.Failed>(f.read()(f.access, "invalid"))
        assertIs<AppResult.Failed>(f.read()(f.access.copy(spaceId = "invalid"), f.id))
        val after = CollaborationHistoryChangeCursor.After(f.owner, f.generation, -1)
        val within = CollaborationHistoryChangeCursor.Within(f.window, 2, "")
        for (cursor in
            listOf(
                after,
                after.copy(generation = "invalid", revision = 1),
                within,
                within.copy(recordId = "x".repeat(513)),
                within.copy(recordId = "\n"),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.result(cursor)).failure.kind,
            )
        }
        assertEquals(0, f.boundReads)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun deniedAccessDoesNotStartALocalRead() = runTest {
        val f = SpaceHistoryChangesFixture()
        f.base.base.deny()
        assertEquals(AppResult.Failed(f.base.base.denied), f.result())
        assertEquals(0, f.boundReads)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun aCursorCannotMoveAcrossAccountOrganizationNamespaceConversationOrRoom() = runTest {
        val f = SpaceHistoryChangesFixture(true)
        val owner = f.owner
        for (foreign in
            listOf(
                owner.copy(audience = "https://foreign.example.test"),
                owner.copy(accountId = f.generation),
                owner.copy(kind = CollaborationHistoryChangeOwnerKind.SPACE),
                owner.copy(organizationId = f.access.spaceId),
                owner.copy(conversationId = f.generation),
                owner.copy(roomId = "!other:example.test"),
            )) {
            if (foreign == owner) continue
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        f.result(CollaborationHistoryChangeCursor.After(foreign, f.generation, 1))
                    )
                    .failure
                    .kind,
            )
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun missingBindingCannotBeTreatedAsEmptyHistory() = runTest {
        val f = SpaceHistoryChangesFixture()
        f.base.room.retained = AppResult.Success(null)
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.result()).failure.kind)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun denialDuringBindingOrHistoryWinsOverSuccessAndStorageFailure() = runTest {
        for (boundary in listOf("binding", "history")) for (failed in listOf(false, true)) {
            val f = SpaceHistoryChangesFixture()
            if (failed) f.response = AppResult.Failed(f.failure)
            f.before = { if (it == boundary) f.base.base.deny() }
            assertEquals(AppResult.Failed(f.base.base.denied), f.result())
            assertEquals(if (boundary == "binding") 0 else 1, f.calls.size)
        }
    }

    @Test
    fun changedSessionAccountAndAudienceDiscardASuspendedRead() = runTest {
        for (boundary in listOf("binding", "history")) for (kind in 0..3) {
            val f = SpaceHistoryChangesFixture()
            f.before = {
                if (it == boundary)
                    f.base.identity.value =
                        AppResult.Success(
                            when (kind) {
                                0 -> null
                                1 -> f.base.native.session.copy(id = "replacement")
                                2 ->
                                    f.base.native.session.copy(
                                        user = f.base.native.session.user.copy(id = f.generation)
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
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.result()).failure.kind,
            )
            assertEquals(if (boundary == "binding") 0 else 1, f.calls.size)
        }
    }

    @Test
    fun accountReplacementDuringAdmissionStopsBeforeBinding() = runTest {
        val f = SpaceHistoryChangesFixture()
        f.base.currentAdmission = {
            f.base.identity.value = AppResult.Success(null)
            f.base.base.gate.value
        }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.result()).failure.kind)
        assertEquals(0, f.boundReads)
    }

    @Test
    fun malformedAndForeignPagesCannotBecomeRepairProgress() = runTest {
        val f = SpaceHistoryChangesFixture()
        for (page in
            listOf(
                f.page.copy(window = f.window.copy(owner = f.owner.copy(accountId = f.generation))),
                f.page.copy(window = f.window.copy(generation = "invalid")),
                f.page.copy(window = f.window.copy(throughRevision = -1)),
                f.page.copy(window = f.window.copy(afterRevision = 1)),
                f.page.copy(eventIds = listOf(f.event, f.event)),
                f.page.copy(eventIds = listOf("invalid")),
                f.page.copy(eventIds = (0..16).map { "\$event-$it" }),
                f.page.copy(sourceRevision = ""),
                f.page.copy(sourceRevision = "x".repeat(129)),
                f.page.copy(
                    next =
                        CollaborationHistoryChangeCursor.Within(
                            f.window.copy(throughRevision = 5),
                            3,
                            "opaque",
                        )
                ),
            )) {
            f.response = AppResult.Success(page)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.result()).failure.kind,
            )
        }
    }

    @Test
    fun aContinuationMustAdvanceWithinItsOriginalFrozenWindow() = runTest {
        val f = SpaceHistoryChangesFixture()
        val cursor = CollaborationHistoryChangeCursor.Within(f.window, 2, "opaque-b")
        for (next in
            listOf(
                cursor,
                cursor.copy(recordId = "opaque-a"),
                cursor.copy(revision = 1),
                cursor.copy(revision = 5),
                cursor.copy(window = f.window.copy(throughRevision = 5)),
            )) {
            f.response = AppResult.Success(f.page.copy(next = next))
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.result(cursor)).failure.kind,
            )
        }
        f.response =
            AppResult.Success(
                f.page.copy(
                    window = f.window.copy(throughRevision = 5),
                    sourceRevision = "${f.generation}:5",
                )
            )
        assertIs<AppResult.Failed>(f.result(cursor))
    }

    @Test
    fun emptyReferencePagesStillContinueWithoutAnyReadLoop() = runTest {
        val f = SpaceHistoryChangesFixture()
        val cursor = CollaborationHistoryChangeCursor.Within(f.window, 2, "opaque-a")
        val next = cursor.copy(revision = 3, recordId = "opaque-b")
        val page =
            f.page.copy(eventIds = emptyList(), next = next, sourceRevision = "opaque-later-source")
        f.response = AppResult.Success(page)
        assertEquals(
            AppResult.Success(CollaborationSpaceHistoryChangesRead(f.owner, page)),
            f.result(cursor, 1),
        )
        assertEquals<
            List<Triple<CollaborationSpaceHistoryLocation, CollaborationHistoryChangeCursor?, Int>>
        >(
            listOf(Triple(f.source, cursor, 1)),
            f.calls,
        )
        assertEquals(4L, page.window.throughRevision)
    }

    @Test
    fun aCompletedIntervalStartsAfterItsSavedRevisionWithoutReplayingEarlierRows() = runTest {
        val f = SpaceHistoryChangesFixture()
        val cursor = CollaborationHistoryChangeCursor.After(f.owner, f.generation, 4)
        val page =
            f.page.copy(
                window = f.window.copy(afterRevision = 4, throughRevision = 8),
                sourceRevision = "${f.generation}:9",
            )
        f.response = AppResult.Success(page)
        assertEquals(
            AppResult.Success(CollaborationSpaceHistoryChangesRead(f.owner, page)),
            f.result(cursor),
        )
        for (window in
            listOf(
                page.window.copy(afterRevision = null),
                page.window.copy(afterRevision = 3),
                page.window.copy(generation = "b3000000-0000-4000-8000-000000000002"),
            )) {
            f.response = AppResult.Success(page.copy(window = window))
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.result(cursor)).failure.kind,
            )
        }
        assertEquals(4, f.calls.size)
    }

    @Test
    fun absenceAndGenerationRestartRemainExplicitAndFailuresAreNotRetried() = runTest {
        val f = SpaceHistoryChangesFixture()
        val cursor = CollaborationHistoryChangeCursor.After(f.owner, f.generation, 4)
        for (value in
            listOf(CollaborationHistoryChanges.Absent, CollaborationHistoryChanges.Restart)) {
            f.response = AppResult.Success(value)
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryChangesRead(f.owner, value)),
                f.result(cursor),
            )
        }
        f.response = AppResult.Failed(f.failure)
        assertEquals(AppResult.Failed(f.failure), f.result(cursor))
        assertEquals(3, f.calls.size)
    }

    @Test
    fun responseCollectionsAreFrozenBeforeAdmissionCanSuspendAgain() = runTest {
        val f = SpaceHistoryChangesFixture()
        val events = mutableListOf(f.event)
        f.response = AppResult.Success(f.page.copy(eventIds = events))
        var calls = 0
        f.base.currentAdmission = {
            if (++calls == 3) events.clear()
            f.base.base.gate.value
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceHistoryChangesRead(f.owner, f.page)),
            f.result(),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationAndLateNonCancellableResultsCannotCompleteARead() = runTest {
        val f = SpaceHistoryChangesFixture()
        f.before = { if (it == "history") throw CancellationException("Cancelled") }
        assertFailsWith<CancellationException> { f.result() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "history")
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
        }
        val job = async { f.result() }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        job.join()
        assertEquals(2, f.calls.size)
    }
}
