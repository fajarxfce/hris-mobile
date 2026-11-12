package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceThread
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceRoomBinding
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRoomBindingPreparationStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun knownLocalCommandsFollowOriginalBindingPublicationAndEncryptedReopen() = withDatabase { f ->
        val conversation = f.conversation()
        assertEquals(AppResult.Success(true), f.metadata().retain(f.access, conversation))
        val canonical =
            CollaborationSpaceMessageIntent(
                historyId(14171),
                f.access.messageOwner(),
                conversation.id,
                conversation.createdAt,
                "Before the room is retained",
            )
        val position = requireNotNull(f.binding().original).conversation.position
        val company =
            CollaborationLocalAccess(
                f.access.sessionId,
                f.access.audience,
                f.access.accountId,
                position.companyId,
            )
        val original =
            CollaborationMessageIntent(
                canonical.id,
                company.messageOwner(),
                position.conversationId,
                conversation.createdAt,
                "Original pending text",
            )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.spaceMessages().insert(f.access, canonical),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.originalMessages().insert(company, original),
        )
        coroutineScope {
            val values = Channel<AppResult<CollaborationSpaceThreadSnapshot>>(8)
            val job = launch {
                f.observeHistory()(f.access, conversation.id).collect { values.send(it) }
            }
            try {
                val unresolved =
                    assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                        assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                                values.receive()
                            )
                            .value
                    )
                assertEquals(conversation.localMetadata(), unresolved.conversation)
                assertEquals(listOf(canonical), unresolved.spaceCommands.map { it.intent })
                assertEquals(AppResult.Success(true), f.bindings().retain(f.access, f.binding()))
                val bound =
                    assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                        assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                                values.receive()
                            )
                            .value
                    )
                assertIs<CollaborationSpaceHistoryLocation.Original>(bound.history.location)
                assertEquals(listOf(canonical), bound.spaceCommands.map { it.intent })
                assertEquals(listOf(original), bound.originalCommands.map { it.intent })
                assertEquals(0, f.roomReads)
                assertEquals(0, f.originalReads)
            } finally {
                job.cancelAndJoin()
                values.close()
            }
        }
        val keys = f.history.keys.read()
        f.reopen()
        val restored =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observeHistory()(f.access, conversation.id).first()
                )
                .value
        assertEquals(listOf(canonical), restored.spaceCommands.map { it.intent })
        assertEquals(listOf(original), restored.originalCommands.map { it.intent })
        assertEquals(f.access, restored.history.access)
        assertEquals(keys, f.history.keys.read())
    }

    @Test
    fun aSpaceBindingAppearsAndDisappearsWithoutReopeningOrDroppingCanonicalCommands() =
        withDatabase { f ->
            val conversation = f.conversation()
            assertEquals(AppResult.Success(true), f.metadata().retain(f.access, conversation))
            val command =
                CollaborationSpaceMessageIntent(
                    historyId(14172),
                    f.access.messageOwner(),
                    conversation.id,
                    conversation.createdAt,
                    "Canonical offline text",
                )
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.spaceMessages().insert(f.access, command),
            )
            val space =
                f.binding().let {
                    it.copy(
                        room =
                            it.room.copy(
                                origin = CollaborationSpaceRoomOrigin.SPACE,
                                original = null,
                            ),
                        original = null,
                    )
                }
            coroutineScope {
                val values = Channel<AppResult<CollaborationSpaceThreadSnapshot>>(8)
                val job = launch {
                    f.observeHistory()(f.access, conversation.id).collect { values.send(it) }
                }
                try {
                    val first =
                        assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                                    values.receive()
                                )
                                .value
                        )
                    assertEquals(listOf(command), first.spaceCommands.map { it.intent })
                    assertEquals(AppResult.Success(true), f.bindings().retain(f.access, space))
                    val bound =
                        assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                                    values.receive()
                                )
                                .value
                        )
                    assertIs<CollaborationSpaceHistoryLocation.Space>(bound.history.location)
                    assertEquals(listOf(command), bound.spaceCommands.map { it.intent })
                    assertTrue(bound.originalCommands.isEmpty())
                    assertTrue(
                        f.history.database.replicas.clear(
                            spaceRoomBindingScope(f.access),
                            spaceRoomBindingPartition(f.access),
                        )
                    )
                    val published =
                        assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                                    values.receive()
                                )
                                .value
                        )
                    assertEquals(first, published)
                    assertEquals(0, f.roomReads)
                    assertEquals(0, f.originalReads)
                } finally {
                    job.cancelAndJoin()
                    values.close()
                }
            }
        }

    @Test
    fun canonicalThreadReopensBothEncryptedCommandNamespacesWithoutMovingTheirOriginals() =
        withDatabase { f ->
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            val position = requireNotNull(f.binding().original).conversation.position
            val company =
                CollaborationLocalAccess(
                    f.access.sessionId,
                    f.access.audience,
                    f.access.accountId,
                    position.companyId,
                )
            val id = historyId(14101)
            val canonical =
                CollaborationSpaceMessageIntent(
                    id,
                    f.access.messageOwner(),
                    f.conversation().id,
                    f.conversation().createdAt,
                    "Canonical queued text",
                )
            val original =
                CollaborationMessageIntent(
                    id,
                    company.messageOwner(),
                    position.conversationId,
                    f.conversation().createdAt,
                    "Original queued text",
                )
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.spaceMessages().insert(f.access, canonical),
            )
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.originalMessages().insert(company, original),
            )
            val before =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
            assertEquals(listOf(canonical), before.spaceCommands.map { it.intent })
            assertEquals(listOf(original), before.originalCommands.map { it.intent })
            val keys = f.history.keys.read()
            f.reopen()
            val after =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
            assertNotEquals(before.history.access.sessionId, after.history.access.sessionId)
            assertEquals(f.access, after.history.access)
            val oldLocation =
                assertIs<CollaborationSpaceHistoryLocation.Original>(before.history.location)
            val renewedCompany = company.copy(sessionId = f.access.sessionId)
            assertEquals(
                before.copy(
                    history =
                        before.history.copy(
                            access = f.access,
                            location = oldLocation.copy(access = renewedCompany),
                        )
                ),
                after,
            )
            assertNull(
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.originalMessages().get(company, id)
                    )
                    .value
            )
            assertEquals(keys, f.history.keys.read())
            assertEquals(
                original,
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.originalMessages().get(renewedCompany, id)
                    )
                    .value
                    ?.intent,
            )
            assertEquals(
                canonical,
                assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                        f.spaceMessages().get(f.access, id)
                    )
                    .value
                    ?.intent,
            )
            assertEquals(1, f.roomReads)
            assertEquals(1, f.originalReads)
        }

    @Test
    fun spaceThreadNeverIncludesCompanyCommandsWithIdenticalOwnerConversationAndMessageIds() =
        withDatabase { f ->
            val originalBinding = f.binding()
            val binding =
                originalBinding.copy(
                    room =
                        originalBinding.room.copy(
                            origin = CollaborationSpaceRoomOrigin.SPACE,
                            original = null,
                        ),
                    original = null,
                )
            assertEquals(AppResult.Success(true), f.bindings().retain(f.access, binding))
            val company =
                CollaborationLocalAccess(
                    f.access.sessionId,
                    f.access.audience,
                    f.access.accountId,
                    f.access.spaceId,
                )
            val id = historyId(14102)
            val canonical =
                CollaborationSpaceMessageIntent(
                    id,
                    f.access.messageOwner(),
                    f.conversation().id,
                    f.conversation().createdAt,
                    "Space-only queued text",
                )
            val original =
                CollaborationMessageIntent(
                    id,
                    company.messageOwner(),
                    f.conversation().id,
                    f.conversation().createdAt,
                    "Separate Company queued text",
                )
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.spaceMessages().insert(f.access, canonical),
            )
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.originalMessages().insert(company, original),
            )
            f.reopen()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
            assertIs<CollaborationSpaceHistoryLocation.Space>(snapshot.history.location)
            assertEquals(listOf(canonical), snapshot.spaceCommands.map { it.intent })
            assertTrue(snapshot.originalCommands.isEmpty())
            assertEquals(
                original,
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.originalMessages().get(company.copy(sessionId = f.access.sessionId), id)
                    )
                    .value
                    ?.intent,
            )
            assertEquals(0, f.roomReads + f.originalReads)
        }

    @Test
    fun canonicalHistoryReadsOriginalCiphertextAfterReopenWithoutCopyingOrAcquiringRooms() =
        withDatabase { f ->
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            val binding = f.binding()
            val position = requireNotNull(binding.original).conversation.position
            val company =
                CollaborationLocalAccess(
                    f.access.sessionId,
                    f.access.audience,
                    f.access.accountId,
                    position.companyId,
                )
            assertEquals(
                AppResult.Success(true),
                f.history.repository.merge(
                    company,
                    position.conversationId,
                    binding.room.roomId,
                    listOf(f.history.event(1)),
                ),
            )
            assertEquals(
                AppResult.Success(true),
                f.spaceHistory()
                    .merge(
                        f.access,
                        f.conversation().id,
                        binding.room.roomId,
                        listOf(f.history.event(2)),
                    ),
            )
            val before =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
                    .history
            assertEquals(
                listOf(f.history.event(1).eventId),
                before.page.messages.map { it.primaryEventId },
            )
            assertEquals(
                position.conversationId,
                assertIs<CollaborationSpaceHistoryLocation.Original>(before.location).conversationId,
            )
            val bindings = f.rawBindings()
            val key = f.history.keys.read()
            f.reopen()
            val after =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
                    .history
            assertEquals(before.page, after.page)
            assertEquals(f.access, after.access)
            assertEquals(bindings.documents, f.rawBindings().documents)
            assertEquals(key, f.history.keys.read())
            assertEquals(
                listOf(f.history.event(2).eventId),
                assertIs<AppResult.Success<CollaborationHistoryPage>>(
                        f.spaceHistory()
                            .observe(f.access, f.conversation().id, binding.room.roomId)
                            .first()
                    )
                    .value
                    .messages
                    .map { it.primaryEventId },
            )
            assertEquals(1, f.roomReads)
            assertEquals(1, f.originalReads)
        }

    @Test
    fun canonicalSpaceHistoryNeverSelectsACompanyPartitionWithIdenticalIds() = withDatabase { f ->
        val original = f.binding()
        val binding =
            original.copy(
                room =
                    original.room.copy(
                        origin = CollaborationSpaceRoomOrigin.SPACE,
                        original = null,
                    ),
                original = null,
            )
        assertEquals(AppResult.Success(true), f.bindings().retain(f.access, binding))
        val company =
            CollaborationLocalAccess(
                f.access.sessionId,
                f.access.audience,
                f.access.accountId,
                f.access.spaceId,
            )
        assertEquals(
            AppResult.Success(true),
            f.history.repository.merge(
                company,
                f.conversation().id,
                binding.room.roomId,
                listOf(f.history.event(2)),
            ),
        )
        assertEquals(
            AppResult.Success(true),
            f.spaceHistory()
                .merge(
                    f.access,
                    f.conversation().id,
                    binding.room.roomId,
                    listOf(f.history.event(1)),
                ),
        )
        val before =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observeHistory()(f.access, f.conversation().id).first()
                )
                .value
                .history
        assertIs<CollaborationSpaceHistoryLocation.Space>(before.location)
        assertEquals(
            listOf(f.history.event(1).eventId),
            before.page.messages.map { it.primaryEventId },
        )
        val key = f.history.keys.read()
        f.reopen()
        val after =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                    f.observeHistory()(f.access, f.conversation().id).first()
                )
                .value
                .history
        assertEquals(before.page, after.page)
        assertEquals(key, f.history.keys.read())
        assertEquals(0, f.roomReads + f.originalReads)
    }

    @Test
    fun canonicalHistoryHonorsPersistedGroupDenialAcrossReopenUntilItsAdmissionIsCleared() =
        withDatabase { f ->
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            val binding = f.binding()
            val position = requireNotNull(binding.original).conversation.position
            val company =
                CollaborationLocalAccess(
                    f.access.sessionId,
                    f.access.audience,
                    f.access.accountId,
                    position.companyId,
                )
            assertEquals(
                AppResult.Success(true),
                f.history.repository.merge(
                    company,
                    position.conversationId,
                    binding.room.roomId,
                    listOf(f.history.event(1)),
                ),
            )
            val before =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
                    .history
            val denied =
                Failure(FailureKind.AccessDenied, "Denied", code = "collaboration_access_denied")
            assertEquals(AppResult.Success(true), f.directory().restrict(f.access, denied))
            f.reopen()
            assertEquals(
                denied.code,
                assertIs<AppResult.Failed>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .failure
                    .code,
            )
            val admission =
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
            assertNotNull(admission.revision)
            assertEquals(AppResult.Success(true), f.directory().admit(f.access, admission.revision))
            assertEquals(
                before.page,
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot.Bound>>(
                        f.observeHistory()(f.access, f.conversation().id).first()
                    )
                    .value
                    .history
                    .page,
            )
            assertEquals(1, f.roomReads)
            assertEquals(1, f.originalReads)
        }

    @Test
    fun lostCanonicalAndBindingPublicationResponsesRecoverThroughActualEncryptedIndexes() =
        withDatabase { f ->
            var publications = 0
            val store =
                object : ReplicaStore by f.history.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        check(
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                                wake,
                            )
                        )
                        publications++
                        throw IllegalStateException("Lost publication response")
                    }
                }
            assertEquals(AppResult.Success(f.binding()), f.prepare(store)(f.conversation()))
            assertEquals(2, publications)
            assertEquals(1, f.scheduledWakes.size)
            assertEquals(1, f.roomReads)
            assertEquals(1, f.originalReads)
            assertEquals(
                AppResult.Success(f.conversation().localMetadata()),
                f.metadata().getById(f.access, f.conversation().id),
            )
            val raw = f.rawBindings()
            assertEquals(2, raw.documents.size)
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            assertEquals(raw, f.rawBindings())
            assertEquals(
                listOf(f.history.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)),
                f.scheduledWakes,
            )
            assertEquals(1, f.roomReads)
        }

    @Test
    fun cancelledCommittedBindingReopensAfterLoginReplacementAndExpiredNativeProofWithoutRemoteReads() =
        withDatabase { f ->
            val store =
                object : ReplicaStore by f.history.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        val saved =
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                                wake,
                            )
                        if (partition == spaceRoomBindingPartition(f.access) && saved)
                            throw CancellationException("Lost binding response")
                        return saved
                    }
                }
            assertFailsWith<CancellationException> { f.prepare(store)(f.conversation()) }
            val before = f.rawBindings()
            val pending =
                assertNotNull(
                    f.history.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
                )
            assertTrue(pending.pending)
            assertTrue(f.scheduledWakes.isEmpty())
            val key = f.history.keys.read()
            f.reopen()
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            assertEquals(before.documents, f.rawBindings().documents)
            assertEquals(before.checkpoint?.payload, f.rawBindings().checkpoint?.payload)
            assertEquals(key, f.history.keys.read())
            assertEquals(
                pending,
                f.history.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY),
            )
            assertTrue(f.scheduledWakes.isEmpty())
            assertEquals(1, f.roomReads)
            assertEquals(1, f.originalReads)
        }

    @Test
    fun directoryDenialAfterPublicationRetainsOwnedFactsWithoutClearingTheActualAdmission() =
        withDatabase { f ->
            val denied =
                Failure(
                    FailureKind.AccessDenied,
                    "Original denial detail",
                    code = "collaboration_access_denied",
                )
            val store =
                object : ReplicaStore by f.history.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        val saved =
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                                wake,
                            )
                        if (saved && partition == spaceRoomBindingPartition(f.access))
                            assertEquals(
                                AppResult.Success(true),
                                f.directory().restrict(f.access, denied),
                            )
                        return saved
                    }
                }
            assertEquals(
                denied.code,
                assertIs<AppResult.Failed>(f.prepare(store)(f.conversation())).failure.code,
            )
            val before = f.rawBindings()
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            assertEquals(before, f.rawBindings())
            assertEquals(
                denied.code,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
                    .denial
                    ?.code,
            )
            assertEquals(1, f.roomReads)
        }

    @Test
    fun failedSchedulingLeavesAtomicWorkForLifecycleRecoveryAcrossReopen() = withDatabase { f ->
        f.onSchedule = {
            AppResult.Failed(Failure(FailureKind.Unavailable, "Scheduler unavailable"))
        }
        assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
        val wake = f.scheduledWakes.single()
        val before = f.rawBindings()
        f.reopen()
        assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
        assertEquals(
            wake,
            f.history.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY),
        )
        assertTrue(wake.pending)
        assertEquals(before.documents, f.rawBindings().documents)
        assertEquals(1, f.scheduledWakes.size)
        assertEquals(1, f.roomReads)
        assertEquals(1, f.originalReads)
    }

    private fun withDatabase(block: suspend (Scenario) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val f = Scenario(CollaborationHistoryFixture(databaseOwner()))
            try {
                f.history.activate()
                block(f)
            } finally {
                f.close()
            }
        }
    }

    private class Scenario(val history: CollaborationHistoryFixture) {
        private val now = Instant.parse("2026-11-07T00:00:00Z")
        private val commandKey = MemoryCredentialStore()
        var owner =
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    history.access.sessionId,
                    history.access.audience,
                    history.access.accountId,
                    historyId(14001),
                ),
                historyId(14000),
            )
        val access
            get() = owner.localDirectoryAccess()

        private val peer = historyId(14002)
        private val position =
            CollaborationOriginalConversationPosition(historyId(14003), historyId(14004))
        private val room =
            CollaborationSpacePublishedRoom(
                "!original:matrix.example.test",
                "matrix.example.test",
                CollaborationSpaceRoomOrigin.LEGACY,
                now,
                CollaborationSpaceOriginalRoom(
                    position,
                    "!original:matrix.example.test",
                    now - 1.days,
                ),
            )
        private val original =
            CollaborationSpaceOriginalRoomMetadata(
                CollaborationOriginalConversation(
                    position,
                    access.accountId,
                    peer,
                    peer,
                    now - 2.days,
                    0,
                ),
                CollaborationOriginalRoom(
                    room.serverName,
                    room.roomId,
                    now - 1.days,
                    access.accountId,
                ),
            )
        private val directoryKey = MemoryCredentialStore()
        private var admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
        private var session =
            Session(
                owner.parent.sessionId,
                User(access.accountId, "Fictional coworker", "fixture@example.test"),
                SessionTokens(
                    "fictional-access",
                    "fictional-refresh",
                    (now + 1.hours).toEpochMilliseconds(),
                ),
                SessionBinding(
                    owner.parent.nativeSessionId,
                    access.audience,
                    (now + 2.hours).toEpochMilliseconds(),
                ),
            )
        var roomReads = 0
        var originalReads = 0
        val scheduledWakes = mutableListOf<WorkWakeRecord>()
        var onSchedule: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
        private val schedules =
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertEquals(CollaborationSpaceRecoveryWake.WORK_KEY, key)
                    val wake = assertNotNull(history.database.workWakes.get(key))
                    assertTrue(wake.pending)
                    assertEquals(
                        AppResult.Success(binding()),
                        bindings().getByConversation(access, conversation().id),
                    )
                    scheduledWakes += wake
                    return onSchedule()
                }
            }
        private val sessions =
            object : SessionRepository {
                override val persistent = true

                override fun observe() = flowOf(AppResult.Success(session))

                override suspend fun current(): AppResult<Session?> = AppResult.Success(session)

                override suspend fun compareAndSet(
                    expected: Session?,
                    updated: Session?,
                ): AppResult<Boolean> = error("No credential mutation")

                override suspend fun clear(sessionId: String): AppResult<Boolean> =
                    error("No logout")
            }
        private val rooms =
            object : CollaborationSpaceRoomRepository {
                override suspend fun get(
                    conversation: CollaborationSpaceConversation
                ): AppResult<CollaborationSpaceRoomView> {
                    assertEquals(this@Scenario.conversation(), conversation)
                    roomReads++
                    val attempt = CollaborationSpaceRoomAttempt(access.accountId, 0)
                    return AppResult.Success(
                        CollaborationSpaceRoomView(
                            conversation,
                            CollaborationSpaceRoomStatus(
                                attempt,
                                CollaborationProvisioningState.SUCCEEDED,
                                1,
                                now - 120.seconds,
                                now - 120.seconds,
                                now + 60.seconds,
                                now,
                                null,
                                CollaborationSpaceRoomOutcome(
                                    0,
                                    CollaborationProvisioningState.SUCCEEDED,
                                    now,
                                    null,
                                ),
                            ),
                            room,
                        )
                    )
                }

                override suspend fun request(
                    conversation: CollaborationSpaceConversation,
                    operation: String,
                    request: CollaborationSpaceRoomRequest,
                ): AppResult<CollaborationSetupReceipt> = error("No provider room request")

                override suspend fun readReceipt(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomOperation,
                ): AppResult<CollaborationSpaceRoomReceiptObservation> = error("No setup receipt")

                override suspend fun readIntentReceipt(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomRequestIntent,
                ): AppResult<CollaborationSpaceRoomIntentReceiptObservation> =
                    error("No setup receipt")

                override suspend fun fenceRequest(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomRequestIntent,
                ): AppResult<CollaborationSpaceRoomRequestResolution> = error("No setup fence")
            }
        private val originals =
            object : CollaborationSpaceOriginalConversationRepository {
                override suspend fun list(
                    owner: CollaborationSpaceOwner,
                    after: CollaborationOriginalConversationPosition?,
                    limit: Int,
                ): AppResult<CollaborationSpaceOriginalConversationPage> = error("No original scan")

                override suspend fun get(
                    owner: CollaborationSpaceOwner,
                    position: CollaborationOriginalConversationPosition,
                ): AppResult<CollaborationSpaceOriginalConversation> {
                    assertEquals(this@Scenario.owner, owner)
                    assertEquals(this@Scenario.position, position)
                    originalReads++
                    return AppResult.Success(
                        CollaborationSpaceOriginalConversation(
                            owner,
                            original.conversation,
                            original.room,
                        )
                    )
                }
            }
        private val clock =
            object : Clock {
                override fun now() = this@Scenario.now
            }

        fun conversation() =
            CollaborationSpaceConversation(
                owner,
                historyId(14005),
                access.accountId,
                peer,
                peer,
                now,
                0,
            )

        fun binding() =
            CollaborationSpaceRoomBinding(conversation().localMetadata(), room, original)

        fun metadata(store: ReplicaStore = history.database.replicas) =
            EncryptedCollaborationSpaceConversationMetadataRepository(
                store,
                history.cipher,
                history.keys,
            )

        fun bindings(store: ReplicaStore = history.database.replicas) =
            EncryptedCollaborationSpaceRoomBindingRepository(store, history.cipher, history.keys)

        fun spaceHistory() =
            EncryptedCollaborationSpaceHistoryRepository(
                history.database.replicas,
                history.cipher,
                history.keys,
            )

        fun spaceMessages() =
            EncryptedCollaborationSpaceMessageRepository(
                history.database.commands,
                history.cipher,
                commandKey,
            )

        fun originalMessages() =
            EncryptedCollaborationMessageRepository(
                history.database.commands,
                history.cipher,
                commandKey,
            )

        fun observeHistory() =
            ObserveCollaborationSpaceThread(
                bindings(),
                metadata(),
                spaceHistory(),
                history.repository,
                spaceMessages(),
                originalMessages(),
                directory(),
                sessions,
                IdentityServer(access.audience, "Fictional server"),
            )

        fun directory() =
            EncryptedCollaborationSpaceDirectoryRepository(
                history.database.replicas,
                history.cipher,
                directoryKey,
                admissions,
            )

        fun prepare(store: ReplicaStore = history.database.replicas) =
            PrepareCollaborationSpaceRoomBinding(
                bindings(store),
                metadata(store),
                rooms,
                originals,
                directory(),
                sessions,
                IdentityServer(access.audience, "Fictional server"),
                AppBuild(AppPlatform.Android, 5, "test", null),
                schedules,
                clock,
            )

        suspend fun rawBindings() =
            history.database.replicas
                .observe(
                    spaceRoomBindingScope(access),
                    spaceRoomBindingPartition(access),
                    limit = 10,
                )
                .first()

        suspend fun reopen() {
            val renewed = history.access.copy(sessionId = "renewed-local-login")
            history.reopen(renewed)
            owner =
                owner.copy(
                    parent =
                        owner.parent.copy(
                            sessionId = renewed.sessionId,
                            nativeSessionId = historyId(14006),
                        )
                )
            session =
                session.copy(
                    id = renewed.sessionId,
                    binding =
                        session.binding.copy(
                            serverId = owner.parent.nativeSessionId,
                            expiresAtEpochMillis = (now - 1.seconds).toEpochMilliseconds(),
                        ),
                )
            admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
        }

        fun close() {
            directoryKey.close()
            history.close()
        }
    }
}
