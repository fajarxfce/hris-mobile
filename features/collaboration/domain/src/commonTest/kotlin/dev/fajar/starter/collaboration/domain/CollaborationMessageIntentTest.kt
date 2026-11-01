package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationMessageIntentTest {
    @Test
    fun accountReplacementWhileFinalAdmissionReadCannotPublishSuccess() = runTest {
        val f = MessageIntentFixture()
        f.beforeAdmission = { if (f.messageReads == 2) f.setup.session = null }
        assertIs<AppResult.Failed>(f.queue())
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun expiredLiveAuthorityStillAcceptsAndObservesAnOwnedOfflineIntent() = runTest {
        val f = MessageIntentFixture()
        f.setup.now += 365.days
        val result = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        assertEquals("  Offline\nmessage 🙂  ", result.intent.text)
        assertEquals(CollaborationMessageState.Queued, result.state)
        assertEquals(
            listOf(result),
            assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(f.observe().first()).value,
        )
        assertEquals(0, f.setup.calls)
        assertEquals(1, f.insertions)
    }

    @Test
    fun lostStorageResponseRecoversTheSameIntentTimestampAndId() = runTest {
        val f = MessageIntentFixture()
        f.insertResult = AppResult.Failed(f.storage)
        assertIs<AppResult.Failed>(f.queue())
        val original = f.records.value.single()
        f.setup.now += 2.days
        f.insertResult = AppResult.Success(CollaborationMessageInsertion.STORED)
        assertEquals(AppResult.Success(original), f.queue())
        assertEquals(1, f.insertions)
        assertIs<AppResult.Failed>(f.queue(text = "Replacement text"))
        assertEquals(listOf(original), f.records.value)
    }

    @Test
    fun unknownConversationAndUnownedLocalProjectionCannotPrepareMessage() = runTest {
        for (kind in 0..2) {
            val f = MessageIntentFixture()
            f.summary =
                when (kind) {
                    0 -> null
                    1 ->
                        f.summary!!.copy(
                            conversation = f.summary!!.conversation.copy(companyId = intentId(77))
                        )
                    else ->
                        f.summary!!.copy(
                            conversation =
                                f.summary!!.conversation.copy(firstAccountId = intentId(77))
                        )
                }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.insertions)
        }
    }

    @Test
    fun companyDenialAppearingDuringLookupWinsOverFailedSessionRead() = runTest {
        val f = MessageIntentFixture()
        f.afterConversation = {
            f.gate.value = CollaborationListAdmission("denied", f.denied)
            f.setup.sessionFailure = f.storage
        }
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.queue()).failure)
        assertEquals(0, f.insertions)
    }

    @Test
    fun lateSessionReplacementPreventsInsertionAndCannotPublishAlreadyCommittedMessage() = runTest {
        val before = MessageIntentFixture()
        before.afterConversation = { before.setup.session = null }
        assertIs<AppResult.Failed>(before.queue())
        assertEquals(0, before.insertions)
        val after = MessageIntentFixture()
        after.afterInsert = { after.setup.session = null }
        assertIs<AppResult.Failed>(after.queue())
        assertEquals(1, after.records.value.size)
        assertIs<AppResult.Failed>(after.observe().first())
        assertEquals(0, after.messageObservations)
    }

    @Test
    fun cancellationAfterLocalCommitRecoversWithoutAnotherInsertion() = runTest {
        val f = MessageIntentFixture()
        val committed = CompletableDeferred<Unit>()
        f.afterInsert = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.queue() }
        committed.await()
        task.cancelAndJoin()
        f.afterInsert = {}
        assertIs<AppResult.Success<*>>(f.queue())
        assertEquals(1, f.insertions)
    }

    @Test
    fun denialStopsPendingObservationAndRetainsCiphertextUntilAdmittedReopening() = runTest {
        val f = MessageIntentFixture()
        assertIs<AppResult.Success<*>>(f.queue())
        val ready = CompletableDeferred<Unit>()
        val denied = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        var successes = 0
        val task = launch {
            f.observe().collect {
                if (it is AppResult.Failed) {
                    assertEquals(f.denied, it.failure)
                    denied.complete(Unit)
                } else if (++successes == 1) ready.complete(Unit) else recovered.complete(Unit)
            }
        }
        ready.await()
        f.gate.value = CollaborationListAdmission("denied", f.denied)
        denied.await()
        assertEquals(0, f.activeMessageObservers)
        assertEquals(1, f.records.value.size)
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.queue()).failure)
        f.gate.value = CollaborationListAdmission("admitted", null)
        recovered.await()
        task.cancelAndJoin()
        assertEquals(0, f.activeMessageObservers)
    }

    @Test
    fun invalidInputNeverReadsStorageOrCreatesAnIntent() = runTest {
        val f = MessageIntentFixture()
        for (text in listOf("", " \n ", "too-long".repeat(3000), "bad\u0000text")) assertIs<
            AppResult.Failed
        >(
            f.queue(text)
        )
        assertEquals(0, f.messageReads)
        assertEquals(0, f.insertions)
    }

    @Test
    fun failedAdmissionReadAndFullQueueDoNotPretendMessageWasAccepted() = runTest {
        val f = MessageIntentFixture()
        f.gateFailure = f.storage
        assertEquals(f.storage, assertIs<AppResult.Failed>(f.queue()).failure)
        assertEquals(0, f.insertions)
        f.gateFailure = null
        f.insertResult = AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED)
        assertEquals(
            "client.command_queue_full",
            assertIs<AppResult.Failed>(f.queue()).failure.code,
        )
        assertTrue(f.records.value.isEmpty())
    }
}

internal class MessageIntentFixture {
    val setup = CollaborationSetupFixture()
    private val account = intentId(1)
    private val company = intentId(2)
    private val conversation = intentId(3)

    init {
        setup.session = setup.initial.copy(user = setup.initial.user.copy(id = account))
        setup.workspace =
            setup.selected.copy(
                company = setup.selected.company.copy(id = company),
                admission = setup.selected.admission.copy(accountId = account),
            )
    }

    val storage = Failure(FailureKind.Storage, "Unavailable")
    val denied = Failure(FailureKind.Service, "Unavailable", code = "company_maintenance")
    val records = MutableStateFlow(emptyList<CollaborationMessageRecord>())
    val gate = MutableStateFlow(CollaborationListAdmission(null, null))
    var gateFailure: Failure? = null
    var messageReads = 0
    var insertions = 0
    var messageObservations = 0
    var activeMessageObservers = 0
    var afterConversation: suspend () -> Unit = {}
    var afterInsert: suspend () -> Unit = {}
    var beforeAdmission: suspend () -> Unit = {}
    var insertResult: AppResult<CollaborationMessageInsertion> =
        AppResult.Success(CollaborationMessageInsertion.STORED)
    var summary: CollaborationDirectSummary? =
        CollaborationDirectSummary(
            CollaborationDirectConversation(
                conversation,
                company,
                account,
                intentId(4),
                account,
                0,
                0,
            ),
            CollaborationParticipant(intentId(4), "Fictional colleague"),
        )
    val messages =
        object : CollaborationMessageRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                messageObservations++
                activeMessageObservers++
                try {
                    emitAll(records.map { AppResult.Success(it) })
                } finally {
                    activeMessageObservers--
                }
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> {
                messageReads++
                return AppResult.Success(records.value.find { it.intent.id == id })
            }

            override suspend fun insert(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<CollaborationMessageInsertion> {
                insertions++
                if (
                    insertResult !is AppResult.Success ||
                        (insertResult as AppResult.Success).value ==
                            CollaborationMessageInsertion.STORED
                )
                    records.value +=
                        CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
                afterInsert()
                return insertResult
            }

            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No transport during local preparation")
        }
    val lists =
        object : CollaborationListRepository {
            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary?> {
                val found = summary
                afterConversation()
                return AppResult.Success(found)
            }

            override fun observeAdmission(access: CollaborationLocalAccess) =
                gate.map {
                    gateFailure?.let { failure -> AppResult.Failed(failure) }
                        ?: AppResult.Success(it)
                }

            override suspend fun admission(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListAdmission> {
                beforeAdmission()
                return observeAdmission(access).first()
            }

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ): Flow<AppResult<CollaborationLocalList>> = error("No listing")

            override suspend fun checkpoint(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListCheckpoint?> = error("No acquisition")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ): AppResult<Boolean> = error("No acquisition")

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> = error("No remote failure")

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> = error("No admission refresh")
        }
    val server = IdentityServer(setup.initial.binding.audience, "Fixture")
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    val clock =
        object : Clock {
            override fun now() = setup.now
        }
    private val queue =
        QueueCollaborationText(
            messages,
            lists,
            setup.sessions,
            setup.workspaces,
            server,
            build,
            clock,
        )
    private val observer =
        ObserveCollaborationMessages(
            messages,
            lists,
            setup.sessions,
            setup.workspaces,
            server,
            build,
            clock,
        )

    suspend fun queue(text: String = "  Offline\nmessage 🙂  ") =
        queue("selection", conversation, intentId(5), text)

    fun observe() = observer("selection", conversation)
}

private fun intentId(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
