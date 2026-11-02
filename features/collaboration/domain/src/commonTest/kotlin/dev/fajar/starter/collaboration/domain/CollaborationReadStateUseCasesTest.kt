package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollaborationReadStateUseCasesTest {
    @Test
    fun acquiredOriginalAndTombstoneCanBeMarkedOfflineWithoutChangingUnseenCoverage() = runTest {
        val f = CollaborationReadStateFixture()
        f.local.setup.now += 365.days
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selected company or network needed") }
        val scope = f.scope()
        assertEquals(AppResult.Success(f.state), f.read(scope))
        val marked = assertIs<AppResult.Success<CollaborationReadState>>(f.mark(scope)).value
        assertEquals(f.ids.toSet(), marked.readEventIds)
        assertEquals(listOf(f.intent), marked.pending)
        assertNull(marked.acknowledged)
        assertEquals(
            emptySet(),
            assertIs<AppResult.Success<CollaborationReadState>>(f.read(scope, listOf("\$unseen")))
                .value
                .readEventIds,
        )
        assertEquals(2, f.base.factReads)
        assertEquals(1, f.writes)
        assertTrue(f.accessLog.all { it == scope.access })
        assertEquals(0, f.local.setup.calls)
        assertTrue(f.local.scheduledKeys.isEmpty())
    }

    @Test
    fun sameAccountReauthenticationRetainsFactsButRejectsOldActionBinding() = runTest {
        val f = CollaborationReadStateFixture()
        val old = f.scope()
        assertIs<AppResult.Success<*>>(f.mark())
        f.local.setup.session = f.local.setup.session!!.copy(id = "new-local-family")
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read(old)).failure.kind)
        val current = f.scope()
        assertEquals(
            f.state,
            assertIs<AppResult.Success<CollaborationReadState>>(f.read(current)).value,
        )
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(
                    f.marker(current, f.conversationId, f.roomId, f.ids, f.intent, old.access)
                )
                .failure
                .kind,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun invalidSelectionsAndIntentNeverReadHistoryOrReadState() = runTest {
        val f = CollaborationReadStateFixture()
        val invalid =
            listOf(
                emptyList(),
                listOf("bad"),
                listOf(f.ids[0], f.ids[0]),
                List(129) { "\$event-$it" },
            )
        for (selected in invalid) assertIs<AppResult.Failed>(f.mark(selected = selected))
        assertIs<AppResult.Failed>(f.mark(nextIntent = f.intent.copy(id = "bad")))
        assertIs<AppResult.Failed>(f.mark(nextIntent = f.intent.copy(eventId = "\$unselected")))
        for ((conversation, room) in
            listOf(
                "bad" to f.roomId,
                f.conversationId to "room",
                f.conversationId to "!bad room",
            )) {
            assertIs<AppResult.Failed>(f.reader(f.scope(), conversation, room, f.ids))
            assertIs<AppResult.Failed>(f.marker(f.scope(), conversation, room, f.ids, f.intent))
        }
        for (selected in invalid.drop(1)) assertIs<AppResult.Failed>(f.read(selected = selected))
        assertEquals(0, f.reads)
        assertEquals(0, f.base.factReads)
        assertEquals(0, f.writes)
        assertIs<AppResult.Success<*>>(f.read(selected = emptyList()))
    }

    @Test
    fun foreignAccountAndMalformedLocalPairCannotMark() = runTest {
        val f = CollaborationReadStateFixture()
        val scope = f.scope()
        val summary = f.local.summary!!
        val other = CollaborationReadStateFixture.id(99)
        val alternatives =
            listOf(
                summary.copy(conversation = summary.conversation.copy(id = other)),
                summary.copy(conversation = summary.conversation.copy(companyId = other)),
                summary.copy(conversation = summary.conversation.copy(firstAccountId = other)),
                summary.copy(
                    conversation =
                        summary.conversation.copy(secondAccountId = scope.access.accountId)
                ),
                summary.copy(peer = summary.peer.copy(accountId = other)),
                null,
            )
        for (value in alternatives) {
            f.local.summary = value
            assertIs<AppResult.Failed>(f.mark(scope))
        }
        assertIs<AppResult.Failed>(
            f.read(scope.copy(access = scope.access.copy(accountId = other)))
        )
        assertEquals(0, f.base.factReads)
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun missingOrSubstitutedHistoryCannotMarkEvenWithMatchingReceiptTarget() = runTest {
        val f = CollaborationReadStateFixture()
        val event = f.ids[0]
        val original = f.base.facts.getValue(event)
        val malformed =
            listOf(
                original.copy(eventId = "\$other"),
                original.copy(sender = "invalid"),
                original.copy(timestamp = -1),
                original.copy(timestamp = null, redacted = false),
                original.copy(timestamp = 9_007_199_254_740_992L),
            )
        for (fact in malformed) {
            f.base.facts[event] = fact
            assertIs<AppResult.Failed>(f.mark())
        }
        f.base.facts.remove(event)
        assertIs<AppResult.Failed>(f.mark())
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun sameIntentRecoversLostLocalResponseAndCannotBeRebound() = runTest {
        val f = CollaborationReadStateFixture()
        f.commitOnFailure = true
        f.markResult = AppResult.Failed(f.local.storage)
        assertEquals(AppResult.Failed(f.local.storage), f.mark())
        assertEquals(f.ids.toSet(), f.state.readEventIds)
        assertEquals(listOf(f.intent), f.state.pending)
        f.markResult = AppResult.Success(true)
        f.commitOnFailure = false
        assertEquals(AppResult.Success(f.state), f.mark())
        assertEquals(1, f.writes)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.mark(
                        nextIntent =
                            f.intent.copy(visibility = CollaborationReadReceiptVisibility.Public)
                    )
                )
                .failure
                .kind,
        )
        f.state = f.state.copy(pending = emptyList(), acknowledged = f.intent)
        assertEquals(AppResult.Success(f.state), f.mark())
        assertEquals(1, f.writes)
    }

    @Test
    fun capacityAndCasConflictNeverRetryOrDropPendingPoints() = runTest {
        val f = CollaborationReadStateFixture()
        f.state =
            CollaborationReadState(
                CollaborationReadStateRevision(CollaborationReadStateFixture.id(2), 8),
                emptySet(),
                List(128) { f.intent.copy(id = CollaborationReadStateFixture.id(it + 10)) },
                null,
            )
        val before = f.state
        assertEquals("client.command_queue_full", assertIs<AppResult.Failed>(f.mark()).failure.code)
        assertEquals(before, f.state)
        assertEquals(0, f.writes)
        f.state = f.state.copy(pending = f.state.pending.drop(1))
        f.markResult = AppResult.Success(false)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.mark()).failure.kind)
        assertEquals(1, f.writes)
        assertEquals(127, f.state.pending.size)
        assertTrue(f.state.readEventIds.isEmpty())
    }

    @Test
    fun knownDenialWinsBeforeAndAfterEachPendingStepEvenIfOtherReadsFail() = runTest {
        for (phase in listOf("initial", "conversation", "history", "read", "mark")) {
            val f = CollaborationReadStateFixture()
            val scope = f.scope()
            val deny: suspend () -> Unit = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
                f.readFailure = f.local.storage
                f.base.factFailure = f.local.storage
            }
            when (phase) {
                "initial" ->
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                "conversation" -> f.local.afterConversation = deny
                "history" -> f.base.afterFact = deny
                "read" -> f.afterRead = deny
                "mark" -> f.afterMark = deny
            }
            assertEquals(AppResult.Failed(f.local.denied), f.mark(scope), phase)
            assertEquals(if (phase == "mark") 1 else 0, f.writes, phase)
            if (phase == "mark") assertEquals(listOf(f.intent), f.state.pending)
        }
    }

    @Test
    fun lateReadDenialAndAccountReplacementWithholdSavedProjection() = runTest {
        for (deny in listOf(true, false)) {
            val f = CollaborationReadStateFixture()
            assertIs<AppResult.Success<*>>(f.mark())
            val scope = f.scope()
            f.afterRead = {
                if (deny) {
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    f.readFailure = f.local.storage
                    f.local.setup.sessionFailure = f.local.storage
                } else f.local.setup.session = f.local.setup.session!!.copy(id = "replacement")
            }
            val failed = assertIs<AppResult.Failed>(f.read(scope))
            if (deny) assertEquals(f.local.denied, failed.failure)
            else assertEquals(FailureKind.Unauthorized, failed.failure.kind)
            assertEquals(listOf(f.intent), f.state.pending)
        }
    }

    @Test
    fun accountReplacementDuringMarkCannotPublishOrContinueFurtherWrites() = runTest {
        for (phase in listOf("conversation", "history", "read", "mark")) {
            val f = CollaborationReadStateFixture()
            val scope = f.scope()
            val replace: suspend () -> Unit = {
                f.local.setup.session = f.local.setup.session!!.copy(id = "replacement")
            }
            when (phase) {
                "conversation" -> f.local.afterConversation = replace
                "history" -> f.base.afterFact = replace
                "read" -> f.afterRead = replace
                "mark" -> f.afterMark = replace
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.mark(scope)).failure.kind,
            )
            assertEquals(if (phase == "mark") 1 else 0, f.writes)
        }
    }

    @Test
    fun pendingCancellationAndTimeoutJoinWorkAndReleaseMarkExclusion() = runTest {
        for (phase in listOf("conversation", "history", "read", "mark")) {
            val f = CollaborationReadStateFixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val wait: suspend () -> Unit = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            when (phase) {
                "conversation" -> f.local.afterConversation = wait
                "history" -> f.base.afterFact = wait
                "read" -> f.afterRead = wait
                "mark" -> f.afterMark = wait
            }
            var published = false
            val job = launch {
                f.mark()
                published = true
            }
            entered.await()
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.mark()).failure.kind)
            job.cancelAndJoin()
            assertTrue(stopped)
            assertFalse(published)
            f.local.afterConversation = {}
            f.base.afterFact = {}
            f.afterRead = {}
            f.afterMark = {}
            assertIs<AppResult.Success<*>>(f.mark())
        }
        val f = CollaborationReadStateFixture()
        var stopped = false
        f.afterRead = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.mark()).failure.kind)
        assertTrue(stopped)
        assertEquals(10_000L, testScheduler.currentTime)
        f.afterRead = {}
        assertIs<AppResult.Success<*>>(f.mark())
    }

    @Test
    fun localReadCancellationAndTimeoutCannotPublish() = runTest {
        val f = CollaborationReadStateFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.afterRead = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val job = launch {
            f.read()
            error("Cancelled read published")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped)
        stopped = false
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertTrue(stopped)
        assertEquals(0, f.writes)
    }

    @Test
    fun callerSelectionsAndReturnedCollectionsAreDetachedBeforeSuspension() = runTest {
        val f = CollaborationReadStateFixture()
        val selection = f.ids.toMutableList()
        f.local.afterConversation = {
            selection.clear()
            selection += "\$unseen"
        }
        assertIs<AppResult.Success<*>>(f.mark(selected = selection))
        assertEquals(listOf(f.ids), f.storedSelections)
        f.local.afterConversation = {}
        val readSelection = f.ids.toMutableList()
        f.local.beforeAdmission = { readSelection.clear() }
        assertEquals(
            f.ids.toSet(),
            assertIs<AppResult.Success<CollaborationReadState>>(f.read(selected = readSelection))
                .value
                .readEventIds,
        )
        assertEquals(f.ids, f.readSelections.last())
        val pending = f.state.pending.toMutableList()
        val covered = f.ids.toMutableSet()
        f.rawRead = f.state.copy(pending = pending, readEventIds = covered)
        var gateCalls = 0
        f.local.beforeAdmission = {
            if (++gateCalls == 2) {
                pending.clear()
                covered.clear()
            }
        }
        val snapshot = assertIs<AppResult.Success<CollaborationReadState>>(f.read()).value
        assertEquals(listOf(f.intent), snapshot.pending)
        assertEquals(f.ids.toSet(), snapshot.readEventIds)
    }

    @Test
    fun malformedCheckpointNeverPublishesOrEnablesAMark() = runTest {
        val f = CollaborationReadStateFixture()
        val version = CollaborationReadStateRevision(CollaborationReadStateFixture.id(2), 0)
        val clean = CollaborationReadState(version, f.ids.toSet(), listOf(f.intent), null)
        val invalid =
            listOf(
                clean.copy(version = null),
                clean.copy(version = version.copy(generation = "bad")),
                clean.copy(version = version.copy(revision = -1)),
                clean.copy(version = version.copy(revision = 9_007_199_254_740_991L)),
                clean.copy(readEventIds = setOf("\$unselected")),
                clean.copy(pending = listOf(f.intent, f.intent)),
                clean.copy(
                    pending =
                        List(129) { f.intent.copy(id = CollaborationReadStateFixture.id(it + 10)) }
                ),
                clean.copy(pending = listOf(f.intent.copy(eventId = "bad"))),
                clean.copy(acknowledged = f.intent),
                clean.copy(pending = emptyList(), acknowledged = f.intent.copy(id = "bad")),
            )
        for (value in invalid) {
            f.rawRead = value
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.mark()).failure.kind)
        }
        assertEquals(0, f.writes)
    }
}
