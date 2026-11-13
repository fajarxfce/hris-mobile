package dev.fajar.starter.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.fajar.starter.database.entities.CommandEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomCommandStore(private val database: StarterDatabase) : CommandStore {
    private val dao = database.commandDao()

    override fun observeAll(scope: CommandScope, partition: String) =
        database.invalidationTracker
            .createFlow("account_scope", "durable_commands")
            .map {
                validateCommandScope(scope, partition)
                dao.page(scope.sessionId, scope.accountKey, partition, null, null, 128).map {
                    it.toRecord()
                }
            }
            .distinctUntilChanged()

    override fun observe(
        scope: CommandScope,
        partition: String,
        resourceId: String?,
        after: String?,
        limit: Int,
    ) =
        database.invalidationTracker
            .createFlow("account_scope", "durable_commands")
            .map {
                validateCommandScope(scope, partition)
                validateCommandPage(resourceId, after, limit)
                dao.page(scope.sessionId, scope.accountKey, partition, resourceId, after, limit)
                    .map { it.toRecord() }
            }
            .distinctUntilChanged()

    override fun observeRecord(scope: CommandScope, partition: String, id: String) =
        database.invalidationTracker
            .createFlow("account_scope", "durable_commands", "command_archives")
            .map {
                validateCommandScope(scope, partition)
                validateCommandIdentifier(id)
                val (active, archived) =
                    dao.lookup(scope.sessionId, scope.accountKey, partition, id)
                CommandLookup(
                    active?.toRecord(),
                    archived?.let { row ->
                        CommandArchive(
                            CommandRecord(
                                row.partition,
                                row.id,
                                row.resourceId,
                                row.sortKey,
                                row.request,
                                row.state,
                                row.revision,
                            ),
                            row.evidence,
                        )
                    },
                )
            }
            .distinctUntilChanged()

    override suspend fun get(scope: CommandScope, partition: String, id: String): CommandRecord? {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        return dao.get(scope.sessionId, scope.accountKey, partition, id)?.toRecord()
    }

    override suspend fun insert(scope: CommandScope, record: CommandRecord): CommandInsertResult {
        validateCommandRecord(scope, record)
        return dao.insert(
            scope.sessionId,
            CommandEntity(
                scope.accountKey,
                record.partition,
                record.id,
                record.resourceId,
                record.sortKey,
                record.request,
                record.state,
                record.revision,
            ),
        )
    }

    override suspend fun insertBatch(
        scope: CommandScope,
        records: List<CommandRecord>,
    ): CommandInsertResult =
        requireNotNull(
            dao.insertBatch(
                scope.sessionId,
                commandBatch(scope, records).map { it.toEntity(scope.accountKey) },
            )
        )

    override suspend fun insertBatchAndWake(
        scope: CommandScope,
        records: List<CommandRecord>,
        wake: WorkWakeRequest,
    ): CommandInsertResult? {
        val detached = commandBatch(scope, records).map { it.toEntity(scope.accountKey) }
        validateWorkWakeRequest(wake)
        currentCoroutineContext().ensureActive()
        return dao.insertBatch(scope.sessionId, detached, wake)
    }

    override suspend fun update(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        validateCommandState(expectedRevision, state)
        return dao.update(scope.sessionId, scope.accountKey, partition, id, expectedRevision, state)
    }

    override suspend fun remove(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        require(expectedRevision in 0..9_007_199_254_740_991L)
        return dao.remove(scope.sessionId, scope.accountKey, partition, id, expectedRevision)
    }

    override suspend fun archived(
        scope: CommandScope,
        partition: String,
        id: String,
    ): CommandArchive? {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        return dao.archived(scope.sessionId, scope.accountKey, partition, id)?.let {
            CommandArchive(
                CommandRecord(
                    it.partition,
                    it.id,
                    it.resourceId,
                    it.sortKey,
                    it.request,
                    it.state,
                    it.revision,
                ),
                it.evidence,
            )
        }
    }

    override suspend fun archive(
        scope: CommandScope,
        expected: CommandRecord,
        evidence: String,
    ): Boolean {
        validateCommandArchive(scope, expected, evidence)
        return dao.archive(
            scope.sessionId,
            CommandEntity(
                scope.accountKey,
                expected.partition,
                expected.id,
                expected.resourceId,
                expected.sortKey,
                expected.request,
                expected.state,
                expected.revision,
            ),
            evidence,
        )
    }

    override suspend fun archiveAndMutate(
        scope: CommandScope,
        expected: CommandRecord,
        evidence: String,
        mutations: List<ReplicaMutation>,
    ): Boolean {
        validateCommandArchive(scope, expected, evidence)
        val replicaScope = ReplicaScope(scope.sessionId, scope.accountKey)
        val retained = retainReplicaMutations(replicaScope, mutations)
        val candidate = expected.toEntity(scope.accountKey)
        currentCoroutineContext().ensureActive()
        return try {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val (active, archived) =
                        dao.lookup(
                            scope.sessionId,
                            scope.accountKey,
                            expected.partition,
                            expected.id,
                        )
                    if (archived != null)
                        return@immediateTransaction dao.archive(
                            scope.sessionId,
                            candidate,
                            evidence,
                        )
                    if (active != candidate) return@immediateTransaction false
                    if (!database.replicaDao().mutate(replicaScope, retained, null))
                        return@immediateTransaction false
                    check(dao.archive(scope.sessionId, candidate, evidence))
                    true
                }
            }
        } finally {
            database.invalidationTracker.refreshAsync()
        }
    }

    override suspend fun containsRecords() = dao.containsRecords()

    override suspend fun archiveAndInsert(
        scope: CommandScope,
        expected: CommandRecord,
        evidence: String,
        successor: CommandRecord,
    ): Boolean {
        validateCommandHandoff(scope, expected, evidence, successor)
        return dao.archiveAndInsert(
            scope.sessionId,
            CommandEntity(
                scope.accountKey,
                expected.partition,
                expected.id,
                expected.resourceId,
                expected.sortKey,
                expected.request,
                expected.state,
                expected.revision,
            ),
            evidence,
            CommandEntity(
                scope.accountKey,
                successor.partition,
                successor.id,
                successor.resourceId,
                successor.sortKey,
                successor.request,
                successor.state,
                successor.revision,
            ),
        )
    }

    override suspend fun archiveAbsent(
        scope: CommandScope,
        record: CommandRecord,
        evidence: String,
    ): Boolean {
        validateCommandArchive(scope, record, evidence)
        return dao.archiveAbsent(
            scope.sessionId,
            CommandEntity(
                scope.accountKey,
                record.partition,
                record.id,
                record.resourceId,
                record.sortKey,
                record.request,
                record.state,
                record.revision,
            ),
            evidence,
        )
    }
}

private fun CommandEntity.toRecord() =
    CommandRecord(partition, id, resourceId, sortKey, request, state, revision)

private fun CommandRecord.toEntity(accountKey: String) =
    CommandEntity(accountKey, partition, id, resourceId, sortKey, request, state, revision)
