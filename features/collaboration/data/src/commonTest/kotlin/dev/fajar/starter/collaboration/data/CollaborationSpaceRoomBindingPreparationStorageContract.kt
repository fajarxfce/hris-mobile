package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceRoomBinding
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.securestorage.MemoryCredentialStore
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRoomBindingPreparationStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

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
                    ): Boolean {
                        check(
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                            )
                        )
                        publications++
                        throw IllegalStateException("Lost publication response")
                    }
                }
            assertEquals(AppResult.Success(f.binding()), f.prepare(store)(f.conversation()))
            assertEquals(2, publications)
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
                    ): Boolean {
                        val saved =
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                            )
                        if (partition == spaceRoomBindingPartition(f.access) && saved)
                            throw CancellationException("Lost binding response")
                        return saved
                    }
                }
            assertFailsWith<CancellationException> { f.prepare(store)(f.conversation()) }
            val before = f.rawBindings()
            val key = f.history.keys.read()
            f.reopen()
            assertEquals(AppResult.Success(f.binding()), f.prepare()(f.conversation()))
            assertEquals(before.documents, f.rawBindings().documents)
            assertEquals(before.checkpoint?.payload, f.rawBindings().checkpoint?.payload)
            assertEquals(key, f.history.keys.read())
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
                    ): Boolean {
                        val saved =
                            f.history.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
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

        private fun bindings(store: ReplicaStore = history.database.replicas) =
            EncryptedCollaborationSpaceRoomBindingRepository(store, history.cipher, history.keys)

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
