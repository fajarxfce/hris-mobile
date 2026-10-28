package dev.fajar.starter.database

import dev.fajar.starter.database.entities.CommandEntity
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

    override suspend fun containsRecords() = dao.containsRecords()
}

private fun CommandEntity.toRecord() =
    CommandRecord(partition, id, resourceId, sortKey, request, state, revision)
