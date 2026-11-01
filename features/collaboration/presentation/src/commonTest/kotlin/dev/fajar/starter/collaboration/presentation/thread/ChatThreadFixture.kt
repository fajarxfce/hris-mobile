package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.participants.ChatParticipantsFixture
import dev.fajar.starter.collaboration.presentation.setup.ChatSetupFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class ChatThreadFixture {
    val setup = ChatSetupFixture()
    val base = setup.base
    val controls = ChatParticipantsFixture()
    val summary = base.item(1)
    val conversationId = summary.conversation.id
    val self = CollaborationSelfIdentity("@self:example.test")
    val source =
        CollaborationMessageDevice(
            setup.owner.nativeSessionId,
            setup.active.id,
            chatId(9),
            "!room:example.test",
        )
    var room =
        CollaborationDirectRoom(
            conversationId,
            1,
            CollaborationDirectRoomState.READY,
            source.roomId,
            setup.owner.accountId,
            false,
            null,
        )
    val records = MutableStateFlow<List<CollaborationMessageRecord>>(emptyList())
    val historyRows = MutableStateFlow<List<CollaborationHistoryMessage>>(emptyList())
    val updates =
        MutableStateFlow<AppResult<List<CollaborationTimelineItem>>>(AppResult.Success(emptyList()))
    val receiptRows = mutableListOf<CollaborationMessageReceipt>()
    val insertions = mutableListOf<CollaborationMessageIntent>()
    val roomRequests = mutableListOf<CollaborationRequestDirectRoom>()
    var beforeInsert: suspend () -> Unit = {}
    var afterInsert: suspend () -> Unit = {}
    var insertResult: AppResult<CollaborationMessageInsertion> =
        AppResult.Success(CollaborationMessageInsertion.STORED)
    var beforeEnter: suspend () -> Unit = {}
    var beforeResume: suspend () -> Unit = {}
    var timelineFlow: Flow<AppResult<List<CollaborationTimelineItem>>>? = null
    var beforeRoom: suspend () -> Unit = {}
    var sendRoom: suspend (CollaborationRequestDirectRoom) -> AppResult<CollaborationSetupReceipt> =
        {
            room = room.copy(state = CollaborationDirectRoomState.READY, version = 2)
            AppResult.Success(CollaborationSetupReceipt(conversationId, 2))
        }
    var timelineCloses = 0
    var timelineOpens = 0
    var queueCloses = 0
    var deliveries = 0
    var historyReads = 0
    var pageNext: String? = null
    val requestedPages = mutableListOf<String?>()
    val transactions = mutableMapOf<String, String>()

    init {
        base.records.value = listOf(summary)
    }

    val scheduledKeys = mutableListOf<String>()
    val schedules =
        object : dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                scheduledKeys += key
                return AppResult.Success(Unit)
            }
        }
    val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(access: CollaborationLocalAccess) = AppResult.Success(self)

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ) = AppResult.Success(identity == self)
        }
    val messages =
        object : CollaborationMessageRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) =
                records.map { AppResult.Success(it) }

            override suspend fun get(access: CollaborationLocalAccess, id: String) =
                AppResult.Success(records.value.find { it.intent.id == id })

            override suspend fun insert(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<CollaborationMessageInsertion> {
                insertions += intent
                beforeInsert()
                records.value +=
                    CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
                afterInsert()
                return insertResult
            }

            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                if (expected !in records.value) return AppResult.Success(false)
                records.value =
                    records.value.map {
                        if (it == expected) it.copy(revision = it.revision + 1, state = state)
                        else it
                    }
                return AppResult.Success(true)
            }
        }
    val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> {
                requestedPages += after
                return historyRows.map {
                    AppResult.Success(
                        CollaborationHistoryPage(it, if (after == null) pageNext else null)
                    )
                }
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = AppResult.Success(null)

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = AppResult.Success(null)

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = AppResult.Success(null)

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No fabricated originals")
        }
    val receipts =
        object : CollaborationReceiptRepository {
            override suspend fun read(access: CollaborationLocalAccess, conversationId: String) =
                AppResult.Success(receiptRows.toList())

            override suspend fun append(
                access: CollaborationLocalAccess,
                conversationId: String,
                receipts: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> {
                receipts.forEach { new ->
                    receiptRows.removeAll { it.intent.id == new.intent.id }
                    receiptRows += new
                }
                return AppResult.Success(true)
            }
        }
    val retirement =
        object : CollaborationReceiptRetirementRepository {
            override suspend fun removeReceipts(
                access: CollaborationLocalAccess,
                conversationId: String,
                expected: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> = error("No retirement without original evidence")
        }
    val completions =
        object : CollaborationMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageCompletion?> = AppResult.Success(null)

            override suspend fun archiveCompletion(
                access: CollaborationLocalAccess,
                completion: CollaborationMessageCompletion,
            ): AppResult<Boolean> = error("No retirement without original evidence")
        }
    val remote =
        object : CollaborationConversationRepository by base.remote {
            override suspend fun get(owner: CollaborationOwner, conversationId: String) =
                AppResult.Success(summary.conversation)

            override suspend fun room(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectRoom> {
                beforeRoom()
                return AppResult.Success(room)
            }

            override suspend fun requestRoom(
                owner: CollaborationOwner,
                command: CollaborationRequestDirectRoom,
            ): AppResult<CollaborationSetupReceipt> {
                roomRequests += command
                return sendRoom(command)
            }
        }
    val runtimes =
        object : CollaborationRuntimeRepository by setup.runtimes {
            override suspend fun selfIdentity(owner: CollaborationOwner, deviceId: String) =
                AppResult.Success(self)

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> {
                historyReads++
                return AppResult.Success(
                    CollaborationHistoryHead(owner, deviceId, matrixRoomId, null)
                )
            }

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> {
                timelineOpens++
                beforeEnter()
                return AppResult.Success(
                    object : CollaborationTimelineLease {
                        override val owner = setup.owner
                        override val device = source
                        override val deviceId = deviceId
                        override val matrixRoomId = matrixRoomId
                        override val updates = timelineFlow ?: this@ChatThreadFixture.updates

                        override suspend fun close(): AppResult<Unit> {
                            timelineCloses++
                            return AppResult.Success(Unit)
                        }
                    }
                )
            }

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationMessageQueueLease> =
                AppResult.Success(
                    object : CollaborationMessageQueueLease {
                        override val owner = setup.owner
                        override val device = source

                        override suspend fun snapshot() =
                            AppResult.Success(transactions.values.toSet())

                        override suspend fun prepareText(logicalId: String, text: String) =
                            AppResult.Success(
                                CollaborationMessageOrigin(self.matrixUserId, "a".repeat(64))
                            )

                        override suspend fun enqueue(
                            logicalId: String,
                            text: String,
                            origin: CollaborationMessageOrigin,
                        ): AppResult<Unit> {
                            transactions[logicalId] = "tx-$logicalId"
                            return AppResult.Success(Unit)
                        }

                        override suspend fun resume(
                            mappedTransactions: Set<String>
                        ): AppResult<CollaborationQueueDelivery> {
                            deliveries++
                            beforeResume()
                            val sent =
                                mappedTransactions.map {
                                    CollaborationQueueReceipt(it, "\$event-$it")
                                }
                            transactions.clear()
                            return AppResult.Success(
                                CollaborationQueueDelivery(
                                    sent,
                                    emptySet(),
                                    CollaborationQueueDeliveryReason.DRAINED,
                                )
                            )
                        }

                        override suspend fun close(): AppResult<Unit> {
                            queueCloses++
                            return AppResult.Success(Unit)
                        }
                    }
                )
        }

    fun online() {
        setup.device = setup.active
        setup.opened = true
    }

    fun original() =
        CollaborationHistoryMessage(
            "@peer:example.test",
            null,
            "\$saved",
            1000,
            "b".repeat(64),
            "Saved conversation",
            listOf("\$saved"),
            false,
        )

    fun model() =
        with(base) {
            ChatThreadViewModel(
                "selection",
                conversationId,
                createObserveCollaborationAccess(),
                createReadCollaborationThreadContext(),
                createObserveCollaborationHistory(),
                createObserveCollaborationMessages(),
                createQueueCollaborationText(),
                createRequestCollaborationDelivery(),
                createLoadCollaborationSetup(),
                createEnsureCollaborationRuntime(),
                createReadCollaborationIdentity(),
                createReadCollaborationConversations(),
                createReadCollaborationControl(),
                createPrepareCollaborationConversation(),
                createEnterCollaborationConversation(),
                createAcquireCollaborationHistory(),
            )
        }

    private fun createObserveCollaborationAccess() =
        with(base) { ObserveCollaborationAccess(local, sessions, workspaces, server, build, clock) }

    private fun createReadCollaborationThreadContext() =
        with(base) {
            ReadCollaborationThreadContext(
                local,
                identities,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    private fun createObserveCollaborationHistory() =
        with(base) {
            ObserveCollaborationHistory(history, local, sessions, workspaces, server, build, clock)
        }

    private fun createObserveCollaborationMessages() =
        with(base) {
            ObserveCollaborationMessages(
                messages,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    private fun createQueueCollaborationText() =
        with(base) {
            QueueCollaborationText(
                messages,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
                schedules,
            )
        }

    private fun createRequestCollaborationDelivery() =
        with(base) {
            RequestCollaborationDelivery(
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
                schedules,
            )
        }

    private fun createLoadCollaborationSetup() =
        with(base) {
            LoadCollaborationSetup(sessions, workspaces, setup.remote, server, build, clock, local)
        }

    private fun createEnsureCollaborationRuntime() =
        with(base) {
            EnsureCollaborationRuntime(runtimes, sessions, workspaces, server, build, clock)
        }

    private fun createReadCollaborationIdentity() =
        with(base) {
            ReadCollaborationIdentity(
                identities,
                runtimes,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    private fun createReadCollaborationConversations() =
        with(base) {
            ReadCollaborationConversations(
                sessions,
                workspaces,
                this@ChatThreadFixture.remote,
                server,
                build,
                clock,
                local,
            )
        }

    private fun createReadCollaborationControl() =
        with(base) {
            ReadCollaborationControl(
                setup.commands,
                controls.commands,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    private fun createPrepareCollaborationConversation() =
        with(base) {
            PrepareCollaborationConversation(
                controls.commands,
                this@ChatThreadFixture.remote,
                setup.remote,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    private fun createEnterCollaborationConversation() =
        with(base) {
            EnterCollaborationConversation(
                runtimes,
                this@ChatThreadFixture.remote,
                setup.remote,
                sessions,
                workspaces,
                server,
                build,
                clock,
                local,
            )
        }

    private fun createAcquireCollaborationHistory() =
        with(base) {
            AcquireCollaborationHistory(
                history,
                local,
                this@ChatThreadFixture.remote,
                setup.remote,
                runtimes,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }
}
