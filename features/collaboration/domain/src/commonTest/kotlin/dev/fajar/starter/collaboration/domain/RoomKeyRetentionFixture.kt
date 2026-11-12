package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationSpaceRoomKeys
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.flow.*

/** Actual admission/registration fixtures with controlled local writes and an owned raw mailbox. */
internal class RoomKeyRetentionFixture {
    val history = SpaceHistoryRecoveryCompletionFixture()
    val base = history.base
    val runtime = history.runtime
    val source = base.source
    val access = history.access
    val original = assertNotNull(base.retained)
    val published = history.reader.keyHistory.binding
    val batch =
        CollaborationSpaceRoomKeyBatch(
            runtime,
            source,
            id(2100),
            listOf(original.room.roomId),
            false,
        )
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? = null
    var wake: CollaborationSpaceIncomingWake? = null
    var pending: CollaborationSpaceRoomKeyBatch? = null
    var waiting: CollaborationSpaceRoomKeyBatch? = null
    val updates = MutableStateFlow<AppResult<CollaborationSpaceRoomKeyBatch>?>(null)
    val calls = mutableListOf<String>()
    val lookupRooms = mutableListOf<Pair<String, String>>()
    val acknowledged = mutableListOf<CollaborationSpaceRoomKeyBatch>()
    val failures = mutableMapOf<String, Failure>()
    var before: suspend (String) -> Unit = {}
    var starts = 0
    var observers = 0
    var stopped = 0
    var writes = 0
    var schedules = 0
    var consumed = 0
    var casLosses = 0
    var commit = true
    var loseReply = false
    var consumeAck = true
    var originalKnown = true
    var publishedKnown = false
    var wrongOriginal = false
    val storage = base.directory.storageFailure

    fun id(value: Int) = base.id(value)

    fun publish(value: CollaborationSpaceRoomKeyBatch = batch) {
        if (pending == null) {
            pending = value
            updates.value = AppResult.Success(value)
        } else {
            check(waiting == null)
            waiting = value
        }
    }

    suspend fun step(name: String) {
        calls += name
        before(name)
    }

    val keys =
        object : CollaborationSpaceRoomKeyRepository {
            override suspend fun startKeyObservation(
                runtime: CollaborationSpaceRuntimeReady,
                source: CollaborationSpaceIncomingSource,
            ): AppResult<Unit> {
                assertEquals(this@RoomKeyRetentionFixture.runtime, runtime)
                assertEquals(this@RoomKeyRetentionFixture.source, source)
                starts++
                step("start")
                return failures["start"]?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }

            override fun observeKeys(
                runtime: CollaborationSpaceRuntimeReady,
                source: CollaborationSpaceIncomingSource,
            ): Flow<AppResult<CollaborationSpaceRoomKeyBatch>> = flow {
                assertEquals(this@RoomKeyRetentionFixture.runtime, runtime)
                assertEquals(this@RoomKeyRetentionFixture.source, source)
                assertTrue(starts > 0)
                observers++
                try {
                    step("observe")
                    emitAll(updates.filterNotNull())
                } finally {
                    stopped++
                }
            }

            override suspend fun acknowledgeKeys(
                batch: CollaborationSpaceRoomKeyBatch
            ): AppResult<Boolean> {
                acknowledged += batch
                step("ack")
                failures["ack"]?.let {
                    return AppResult.Failed(it)
                }
                if (!consumeAck || pending?.receiptId != batch.receiptId)
                    return AppResult.Success(false)
                consumed++
                pending = waiting
                waiting = null
                updates.value = pending?.let { AppResult.Success(it) }
                return AppResult.Success(true)
            }
        }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@RoomKeyRetentionFixture.access, access)
                step("demand")
                return failures["demand"]?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(snapshot)
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@RoomKeyRetentionFixture.access, access)
                writes++
                step("write")
                failures["write"]?.let {
                    return AppResult.Failed(it)
                }
                if (casLosses > 0) {
                    casLosses--
                    return AppResult.Success(null)
                }
                if (snapshot != expected) return AppResult.Success(null)
                val value = CollaborationSpaceIncomingDemandSnapshot(id(2200 + writes), demand)
                if (commit) {
                    snapshot = value
                    this@RoomKeyRetentionFixture.wake = wake ?: this@RoomKeyRetentionFixture.wake
                }
                step("committed")
                return if (loseReply) AppResult.Failed(storage) else AppResult.Success(value)
            }
        }

    val originals =
        object : CollaborationSpaceOriginalRoomMetadataRepository by base.metadata {
            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                companyId: String,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> {
                assertEquals(this@RoomKeyRetentionFixture.access, access)
                assertEquals(original.conversation.position.companyId, companyId)
                assertEquals(original.room.serverName, serverName)
                lookupRooms += "original" to roomId
                step("original")
                failures["original"]?.let {
                    return AppResult.Failed(it)
                }
                val value =
                    if (wrongOriginal)
                        original.copy(room = original.room.copy(roomId = "!wrong:test"))
                    else original
                return AppResult.Success(
                    value.takeIf {
                        originalKnown && (wrongOriginal || roomId == original.room.roomId)
                    }
                )
            }
        }

    val bindings =
        object : CollaborationSpaceRoomBindingRepository by history.reader.bindings {
            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(this@RoomKeyRetentionFixture.access, access)
                assertEquals(published.room.serverName, serverName)
                lookupRooms += "published" to roomId
                step("published")
                failures["published"]?.let {
                    return AppResult.Failed(it)
                }
                return AppResult.Success(
                    published.takeIf { publishedKnown && roomId == published.room.roomId }
                )
            }
        }

    val usecase =
        RetainCollaborationSpaceRoomKeys(
            keys,
            base.sync,
            demands,
            history.roots,
            base.registrations,
            originals,
            bindings,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertEquals(CollaborationSpaceIncomingWake.WORK_KEY, key)
                    schedules++
                    step("schedule")
                    return failures["schedule"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(Unit)
                }
            },
        )

    suspend fun receive(value: CollaborationSpaceRoomKeyBatch = batch): AppResult<Boolean> {
        publish(value)
        return usecase.observe(runtime).first()
    }
}
