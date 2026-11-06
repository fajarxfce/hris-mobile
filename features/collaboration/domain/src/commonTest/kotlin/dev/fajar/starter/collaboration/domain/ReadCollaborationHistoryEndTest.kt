package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationHistoryEndTest {
    @Test
    fun expiredCredentialsRestoreTheEndAcrossStatePagesWithoutChangingStoredFacts() = runTest {
        val f = stateHistory(45)
        f.local.setup.now += 365.days
        val original = f.references.toList()
        repeat(2) { assertEquals(AppResult.Success(true), f.readEnd()) }
        assertEquals(6, f.reads)
        assertEquals(original, f.references)
        assertEquals(0, f.factReads)
        assertEquals(0, f.replacementReads)
        assertEquals(0, f.writes)
        assertEquals(0, f.local.setup.calls)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun noTraversalAndAnUnfinishedProviderCursorCannotClaimTheEnd() = runTest {
        val f = stateHistory(0)
        f.pageOverride = CollaborationHistoryReferences(null, emptyList(), null)
        assertEquals(AppResult.Success(false), f.readEnd())
        f.pageOverride = null
        f.progress = f.progress.copy(next = "provider-next")
        assertEquals(AppResult.Success(false), f.readEnd())
        f.progress = f.progress.copy(next = null)
        assertEquals(AppResult.Success(true), f.readEnd())
    }

    @Test
    fun aPendingMessageOnTheLastPageKeepsHistoryAvailableForEverySupportedType() = runTest {
        for (type in listOf("m.room.encrypted", "m.room.message", "m.sticker")) {
            val f = stateHistory(45)
            f.references =
                f.references.mapIndexed { i, reference ->
                    if (i == 44) reference.copy(event = reference.event.copy(type = type))
                    else reference
                }
            assertEquals(AppResult.Success(false), f.readEnd(), type)
            assertEquals(3, f.reads)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun resolvedMessagesDoNotReopenTheEndOrGetResolvedAgain() = runTest {
        val f = stateHistory(25)
        f.references =
            f.references.map {
                it.copy(event = it.event.copy(type = "m.room.encrypted"), resolved = true)
            }
        assertEquals(AppResult.Success(true), f.readEnd())
        assertEquals(1, f.reads)
        assertEquals(0, f.writes)
        assertTrue(f.references.all { it.resolved })
    }

    @Test
    fun resolutionRevisionCanAdvanceWithoutReplacingTheTraversal() = runTest {
        val f = stateHistory(45)
        f.afterRead = { f.progress = f.progress.copy(revision = f.progress.revision + 1) }
        assertEquals(AppResult.Success(true), f.readEnd())
        assertEquals(3, f.reads)
    }

    @Test
    fun changingTheTraversalExtentStopsTheScanWithoutFollowingAnUnboundedAppend() = runTest {
        for (change in
            listOf<(CollaborationHistoryProgress) -> CollaborationHistoryProgress>(
                { it.copy(generation = "c4000000-0000-4000-8000-000000000002") },
                { it.copy(references = it.references + 1) },
                { it.copy(next = "new-provider-position") },
            )) {
            val f = stateHistory(45)
            f.afterRead = { f.progress = change(f.progress) }
            assertEquals(AppResult.Success(false), f.readEnd())
            assertEquals(2, f.reads)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun anOverlappingPageCannotFinishAnIncompleteScan() = runTest {
        val f = stateHistory(45)
        f.afterRead = {
            // Different opaque cursor, but the sequence moved backwards across the page edge.
            f.pageOverride =
                CollaborationHistoryReferences(f.progress, f.references.take(20), "overlap")
        }
        assertEquals(AppResult.Success(false), f.readEnd())
        assertEquals(2, f.reads)
    }

    @Test
    fun invalidInputAndKnownDenialCannotOpenTheStore() = runTest {
        val f = stateHistory(25)
        assertIs<AppResult.Failed>(f.endReader("selection", f.key.copy(roomId = "invalid")))
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        assertEquals(AppResult.Failed(f.local.denied), f.readEnd())
        f.local.gateFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.readEnd())
        assertEquals(0, f.reads)
    }

    @Test
    fun lateDenialWinsOverConcurrentStorageAndSessionFailures() = runTest {
        val f = stateHistory(45)
        f.afterRead = {
            if (f.reads == 2) {
                f.readFailure = f.local.storage
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
            }
        }
        assertEquals(AppResult.Failed(f.local.denied), f.readEnd())
        assertEquals(2, f.reads)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun accountLossDuringTheSecondPageCannotPublishTheOldEnd() = runTest {
        val f = stateHistory(25)
        f.afterRead = { if (f.reads == 2) f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.readEnd())
        assertEquals(2, f.reads)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun replacementSessionDuringTheSecondPageCannotCombineOwners() = runTest {
        val f = stateHistory(25)
        f.afterRead = {
            if (f.reads == 2)
                f.local.setup.session = f.local.setup.session?.copy(id = "replacement-session")
        }
        assertIs<AppResult.Failed>(f.readEnd())
        assertEquals(2, f.reads)
        assertEquals(1, f.accessed.distinct().size)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun storageFailureStopsWithoutRetryAndAnExplicitLaterReadCanRecover() = runTest {
        val f = stateHistory(25)
        f.readFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.readEnd())
        assertEquals(1, f.reads)
        f.readFailure = null
        assertEquals(AppResult.Success(true), f.readEnd())
        assertEquals(3, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun malformedPagesFailBeforeClaimingTheEnd() = runTest {
        val f = stateHistory(25)
        val valid = CollaborationHistoryReferences(f.progress, f.references.take(20), "next")
        for (page in
            listOf(
                valid.copy(progress = null),
                valid.copy(events = f.references),
                valid.copy(events = valid.events.reversed()),
                valid.copy(events = listOf(valid.events.first().copy(resolved = true))),
                valid.copy(events = emptyList()),
                valid.copy(after = "bad cursor"),
            )) {
            f.pageOverride = page
            assertIs<AppResult.Failed>(f.readEnd())
        }
        assertEquals(0, f.writes)
    }

    @Test
    fun finalAdmissionCannotMutateTheCapturedReferenceList() = runTest {
        val f = stateHistory(1)
        val message =
            f.references.single().let { it.copy(event = it.event.copy(type = "m.sticker")) }
        val source = mutableListOf(message)
        f.pageOverride = CollaborationHistoryReferences(f.progress, source, null)
        f.local.beforeAdmission = { if (f.reads > 0) source.clear() }
        assertEquals(AppResult.Success(false), f.readEnd())
        assertTrue(source.isEmpty())
    }

    @Test
    fun cancellationClosesThePageAndLeavesAnExplicitNewReadPossible() = runTest {
        val f = stateHistory(45)
        val entered = CompletableDeferred<Unit>()
        f.afterRead = {
            if (f.reads == 2) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val pending = launch {
            f.readEnd()
            error("Cancelled end scan cannot publish")
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(2, f.reads)
        assertEquals(0, f.activeReads)
        f.afterRead = {}
        assertEquals(AppResult.Success(true), f.readEnd())
        assertEquals(0, f.writes)
    }

    private fun stateHistory(count: Int) =
        HistoryReferenceReconciliationFixture().apply {
            progress = progress.copy(next = null, references = count.toLong())
            references =
                List(count) { index ->
                    CollaborationHistoryReference(
                        index.toLong(),
                        CollaborationProviderHistoryEvent(
                            "\$state-$index",
                            "@fictional:test",
                            100L + index,
                            "m.room.member",
                        ),
                    )
                }
        }
}
