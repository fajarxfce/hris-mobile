package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageProgressRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationComposerProgress
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ObserveCollaborationComposerProgressTest {
    @Test
    fun storedAndHeldOriginalsRestoreWithoutAnyUiRequestOrNetwork() = runTest {
        val f = ComposerProgressFixture()
        f.originals.value = emptyList()
        f.outbox.entries.value = listOf(CollaborationComposerOutboxEntry(f.original, true))
        f.base.setup.now += 365.days
        val result =
            assertIs<AppResult.Success<List<CollaborationComposerMessageProgress>>>(
                    f.observe().first()
                )
                .value
                .single()
        assertEquals(f.original, result.original)
        assertTrue(result.retained)
        assertTrue(result.reviewRequired)
        assertNull(result.record)
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.outbox.active)
    }

    @Test
    fun oneStoredAndRequestedOriginalUsesOneExactRecordSubscription() = runTest {
        val f = ComposerProgressFixture()
        f.outbox.entries.value = listOf(CollaborationComposerOutboxEntry(f.original, false))
        val values =
            Channel<AppResult<List<CollaborationComposerMessageProgress>>>(Channel.UNLIMITED)
        val job = launch { f.observe().collect(values::send) }
        assertEquals(
            1,
            assertIs<AppResult.Success<List<CollaborationComposerMessageProgress>>>(
                    values.receive()
                )
                .value
                .size,
        )
        assertEquals(1, f.active)
        assertEquals(1, f.outbox.active)
        f.outbox.entries.value = emptyList()
        val afterRetirement =
            assertIs<AppResult.Success<List<CollaborationComposerMessageProgress>>>(
                    values.receive()
                )
                .value
                .single()
        assertFalse(afterRetirement.retained)
        assertEquals(f.original, afterRetirement.original)
        val cancelled = f.record(CollaborationMessageState.Cancelled)
        f.records.value = mapOf(f.original.id to cancelled)
        assertEquals(
            cancelled,
            assertIs<AppResult.Success<List<CollaborationComposerMessageProgress>>>(
                    values.receive()
                )
                .value
                .single()
                .record,
        )
        job.cancelAndJoin()
        values.close()
        assertEquals(0, f.active)
        assertEquals(0, f.outbox.active)
    }

    @Test
    fun storedCollisionOrOversizedInventoryCannotOpenIdentitySubscriptions() = runTest {
        for (oversized in listOf(false, true)) {
            val f = ComposerProgressFixture()
            f.outbox.entries.value =
                if (oversized) List(129) { CollaborationComposerOutboxEntry(f.original, false) }
                else
                    listOf(
                        CollaborationComposerOutboxEntry(
                            f.original.copy(nextRevision = deliveryId(998)),
                            false,
                        )
                    )
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.started)
            assertEquals(0, f.outbox.active)
        }
    }

    @Test
    fun archivedCancellationBeforeSubscriptionIsAnExplicitTerminalRecord() = runTest {
        val f = ComposerProgressFixture()
        val cancelled = f.record(CollaborationMessageState.Cancelled)
        f.records.value = mapOf(f.original.id to cancelled)
        f.base.setup.now += 365.days
        assertEquals(
            AppResult.Success(listOf(CollaborationComposerMessageProgress(f.original, cancelled))),
            f.observe().first(),
        )
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.active)
    }

    @Test
    fun missingIdentityIsNotInventedAsQueuedOrCompleted() = runTest {
        val f = ComposerProgressFixture()
        assertEquals(
            AppResult.Success(listOf(CollaborationComposerMessageProgress(f.original, null))),
            f.observe().first(),
        )
        assertEquals(1, f.started)
        assertEquals(0, f.active)
    }

    @Test
    fun changedContentOrOrderingCannotReuseTheOriginalsProgress() = runTest {
        for (time in listOf(false, true)) {
            val f = ComposerProgressFixture()
            val record = f.record(CollaborationMessageState.Cancelled)
            f.records.value =
                mapOf(
                    f.original.id to
                        record.copy(
                            intent =
                                if (time)
                                    record.intent.copy(
                                        createdAt = record.intent.createdAt + 1.seconds
                                    )
                                else record.intent.copy(text = "Other original")
                        )
                )
            val failure = assertIs<AppResult.Failed>(f.observe().first()).failure
            assertEquals(FailureKind.Storage, failure.kind)
            assertEquals("client.collaboration_composer_changed", failure.code)
        }
    }

    @Test
    fun changingTheRequestedSetClosesObsoletePerMessageObservers() = runTest {
        val f = ComposerProgressFixture()
        val values =
            Channel<AppResult<List<CollaborationComposerMessageProgress>>>(Channel.UNLIMITED)
        val observer = launch { f.observe().collect { values.send(it) } }
        assertIs<AppResult.Success<*>>(values.receive())
        assertEquals(1, f.active)
        f.originals.value = emptyList()
        assertEquals(AppResult.Success(emptyList()), values.receive())
        assertEquals(0, f.active)
        observer.cancelAndJoin()
        values.close()
    }

    @Test
    fun denialClearsProgressAndStopsTheExactRecordSubscription() = runTest {
        val f = ComposerProgressFixture()
        val values =
            Channel<AppResult<List<CollaborationComposerMessageProgress>>>(Channel.UNLIMITED)
        val observer = launch { f.observe().collect { values.send(it) } }
        assertIs<AppResult.Success<*>>(values.receive())
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(AppResult.Failed(f.base.denied), values.receive())
        assertEquals(0, f.active)
        observer.cancelAndJoin()
        values.close()
    }

    @Test
    fun replacementAccountBeforePublicationCannotReceiveTheOldTerminalRecord() = runTest {
        val f = ComposerProgressFixture()
        val values =
            Channel<AppResult<List<CollaborationComposerMessageProgress>>>(Channel.UNLIMITED)
        val observer = launch { f.observe().collect { values.send(it) } }
        assertIs<AppResult.Success<*>>(values.receive())
        f.base.setup.session =
            f.base.setup.session!!.copy(
                user = f.base.setup.session!!.user.copy(id = deliveryId(999))
            )
        f.records.value = mapOf(f.original.id to f.record(CollaborationMessageState.Cancelled))
        assertIs<AppResult.Failed>(values.receive())
        observer.cancelAndJoin()
        values.close()
        assertEquals(0, f.active)
    }

    @Test
    fun duplicateOrOversizedRequestsNeverOpenRecordSubscriptions() = runTest {
        for (count in listOf(2, 129)) {
            val f = ComposerProgressFixture()
            f.originals.value = List(count) { f.original }
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.started)
        }
    }

    @Test
    fun stagingInventoryIsDetachedBeforePublication() = runTest {
        val f = ComposerProgressFixture()
        val before = mutableSetOf("original-transaction")
        f.records.value =
            mapOf(
                f.original.id to
                    f.record(
                        CollaborationMessageState.Staging(
                            CollaborationMessageDevice(
                                deliveryId(21),
                                deliveryId(22),
                                deliveryId(23),
                                "!room:test",
                            ),
                            before,
                        )
                    )
            )
        val received =
            assertIs<AppResult.Success<List<CollaborationComposerMessageProgress>>>(
                    f.observe().first()
                )
                .value
                .single()
        before += "later-transaction"
        assertEquals(
            setOf("original-transaction"),
            (received.record!!.state as CollaborationMessageState.Staging).before,
        )
    }
}

private class ComposerProgressFixture {
    val base = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            "local",
            base.server.audience,
            base.summary!!.conversation.firstAccountId,
            base.summary!!.conversation.companyId,
        )
    val original =
        CollaborationComposerSubmission(
            CollaborationDraft(
                base.summary!!.conversation.id,
                deliveryId(900),
                "Original",
                base.setup.now,
            ),
            deliveryId(901),
            base.setup.now,
        )
    val originals = MutableStateFlow(listOf(original))
    val outbox = ComposerOutboxTestStore()
    val records = MutableStateFlow(emptyMap<String, CollaborationMessageRecord?>())
    var started = 0
    var active = 0
    private val progress =
        object : CollaborationMessageProgressRepository {
            override fun observeRecord(
                access: CollaborationLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationMessageRecord?>> = flow {
                started++
                active++
                try {
                    emitAll(records.map { AppResult.Success(it[id]) }.distinctUntilChanged())
                } finally {
                    active--
                }
            }
        }
    private val operation =
        ObserveCollaborationComposerProgress(
            progress,
            outbox,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    fun record(state: CollaborationMessageState) =
        CollaborationMessageRecord(collaborationComposerIntent(access, original), 0, state)

    fun observe() = operation("selection", access, originals)
}
