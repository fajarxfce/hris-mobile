@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceThreadHistorySnapshotTest {
    @Test
    fun wrongRoomOversizedRowsInvalidMessagesAndUnboundContentAreRejected() = runTest {
        val f = SpaceThreadHistoryFixture()
        val page = f.page()
        val message = page.messages.single()
        for (invalid in
            listOf(
                page.copy(roomId = "!foreign:matrix.example.test"),
                page.copy(roomId = null),
                page.copy(messages = List(51) { message }),
                page.copy(messages = listOf(message.copy(eventIds = List(129) { "\$event$it" }))),
                page.copy(messages = listOf(message.copy(sender = "invalid"))),
                page.copy(next = "invalid"),
                page.copy(previous = "invalid"),
                CollaborationHistoryPage(emptyList(), null, revision = "unbound"),
            )) {
            f.facts.value = AppResult.Success(invalid)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.id).first()).failure.kind,
            )
        }
    }

    @Test
    fun messageAndAliasListsAreFrozenBeforeSuspendingPublicationGuard() = runTest {
        val f = SpaceThreadHistoryFixture()
        val aliases = mutableListOf("\$original")
        val messages = mutableListOf(f.page().messages.single().copy(eventIds = aliases))
        f.facts.value = AppResult.Success(f.page().copy(messages = messages))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var checks = 0
        f.currentAdmission = {
            checks++
            if (checks == 3) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.base.open)
        }
        val result = async { f.observe()(f.access, f.id).first() }
        entered.await()
        aliases += "\$mutated"
        messages.clear()
        release.complete(Unit)
        val read =
            assertIs<AppResult.Success<CollaborationSpaceThreadHistory>>(result.await()).value
        assertEquals(f.page(), read.page)
        assertEquals(listOf("\$original"), read.page.messages.single().eventIds)
    }

    @Test
    fun changedCurrentAccountWhilePublicationAdmissionIsSuspendedRejectsTheSnapshot() = runTest {
        val f = SpaceThreadHistoryFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var checks = 0
        f.currentAdmission = {
            checks++
            if (checks == 3) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.base.open)
        }
        val result = async { f.observe()(f.access, f.id).first() }
        entered.await()
        f.currentIdentity = { AppResult.Success(null) }
        release.complete(Unit)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(result.await()).failure.kind,
        )
    }

    @Test
    fun cancellationWhilePublishingJoinsTheHistorySubscription() = runTest {
        val f = SpaceThreadHistoryFixture()
        val entered = CompletableDeferred<Unit>()
        var checks = 0
        f.currentAdmission = {
            checks++
            if (checks == 3) {
                entered.complete(Unit)
                awaitCancellation()
            }
            AppResult.Success(f.base.open)
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        entered.await()
        job.cancelAndJoin()
        assertTrue(values.isEmpty())
        assertEquals(1, f.stops)
    }
}
