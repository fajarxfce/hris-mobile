package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.CommandInsertResult
import dev.fajar.starter.database.WorkWakeRequest
import dev.fajar.starter.database.entities.CommandArchiveEntity
import dev.fajar.starter.database.entities.CommandEntity
import dev.fajar.starter.database.entities.WorkWakeEntity

@Dao
abstract class CommandDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM account_scope WHERE id = 1 AND sessionId = :session AND accountKey = :account)"
    )
    protected abstract suspend fun admitted(session: String, account: String): Boolean

    @Query(
        "SELECT * FROM durable_commands WHERE accountKey = :account AND partition = :partition AND id = :id"
    )
    protected abstract suspend fun record(
        account: String,
        partition: String,
        id: String,
    ): CommandEntity?

    @Query(
        "SELECT * FROM durable_commands WHERE accountKey = :account AND partition = :partition AND (:resource IS NULL OR resourceId = :resource) AND (:after IS NULL OR sortKey > :after) ORDER BY sortKey LIMIT :limit"
    )
    protected abstract suspend fun records(
        account: String,
        partition: String,
        resource: String?,
        after: String?,
        limit: Int,
    ): List<CommandEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM durable_commands WHERE accountKey = :account AND partition = :partition AND resourceId = :resource)"
    )
    protected abstract suspend fun occupied(
        account: String,
        partition: String,
        resource: String,
    ): Boolean

    @Query("SELECT COUNT(*) FROM durable_commands") protected abstract suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM durable_commands WHERE accountKey = :account")
    protected abstract suspend fun countAccount(account: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM durable_commands) OR EXISTS(SELECT 1 FROM command_archives)"
    )
    abstract suspend fun containsRecords(): Boolean

    @Query(
        "SELECT * FROM command_archives WHERE accountKey = :account AND partition = :partition AND id = :id"
    )
    protected abstract suspend fun completed(
        account: String,
        partition: String,
        id: String,
    ): CommandArchiveEntity?

    @Insert protected abstract suspend fun retain(record: CommandArchiveEntity)

    @Insert protected abstract suspend fun create(record: CommandEntity)

    @Query("SELECT * FROM work_wakes WHERE `key` = :key")
    protected abstract suspend fun mutationWake(key: String): WorkWakeEntity?

    @Query("SELECT COUNT(*) FROM work_wakes")
    protected abstract suspend fun mutationWakeCount(): Int

    @Upsert protected abstract suspend fun write(wake: WorkWakeEntity)

    @Query(
        "UPDATE durable_commands SET state = :state, revision = revision + 1 WHERE accountKey = :account AND partition = :partition AND id = :id AND revision = :revision"
    )
    protected abstract suspend fun change(
        account: String,
        partition: String,
        id: String,
        revision: Long,
        state: String,
    ): Int

    @Query(
        "DELETE FROM durable_commands WHERE accountKey = :account AND partition = :partition AND id = :id AND revision = :revision"
    )
    protected abstract suspend fun delete(
        account: String,
        partition: String,
        id: String,
        revision: Long,
    ): Int

    @Transaction
    open suspend fun page(
        session: String,
        account: String,
        partition: String,
        resource: String?,
        after: String?,
        limit: Int,
    ): List<CommandEntity> =
        if (admitted(session, account)) records(account, partition, resource, after, limit)
        else emptyList()

    @Transaction
    open suspend fun get(
        session: String,
        account: String,
        partition: String,
        id: String,
    ): CommandEntity? = if (admitted(session, account)) record(account, partition, id) else null

    @Transaction
    open suspend fun lookup(
        session: String,
        account: String,
        partition: String,
        id: String,
    ): Pair<CommandEntity?, CommandArchiveEntity?> =
        if (admitted(session, account))
            record(account, partition, id) to completed(account, partition, id)
        else null to null

    @Transaction
    open suspend fun insert(session: String, entry: CommandEntity): CommandInsertResult {
        if (!admitted(session, entry.accountKey)) return CommandInsertResult.ScopeChanged
        val previous = record(entry.accountKey, entry.partition, entry.id)
        if (previous != null) {
            require(
                previous.request == entry.request &&
                    previous.resourceId == entry.resourceId &&
                    previous.sortKey == entry.sortKey
            ) {
                "Command identity cannot change."
            }
            return CommandInsertResult.AlreadyStored
        }
        completed(entry.accountKey, entry.partition, entry.id)?.let {
            require(
                it.request == entry.request &&
                    it.resourceId == entry.resourceId &&
                    it.sortKey == entry.sortKey
            ) {
                "Archived command identity cannot change."
            }
            return CommandInsertResult.AlreadyStored
        }
        if (occupied(entry.accountKey, entry.partition, entry.resourceId))
            return CommandInsertResult.ResourceOccupied
        if (countAll() >= 512 || countAccount(entry.accountKey) >= 128)
            return CommandInsertResult.CapacityExceeded
        create(entry)
        return CommandInsertResult.Stored
    }

    @Transaction
    open suspend fun insertBatch(
        session: String,
        entries: List<CommandEntity>,
        wake: WorkWakeRequest? = null,
    ): CommandInsertResult? {
        val account = entries.first().accountKey
        require(entries.size in 1..128 && entries.all { it.accountKey == account })
        if (!admitted(session, account)) return CommandInsertResult.ScopeChanged
        val missing = mutableListOf<CommandEntity>()
        for (entry in entries) {
            val previous = record(account, entry.partition, entry.id)
            val archived = completed(account, entry.partition, entry.id)
            require(previous == null || archived == null)
            if (previous != null || archived != null) {
                require(
                    (previous?.request ?: archived?.request) == entry.request &&
                        (previous?.resourceId ?: archived?.resourceId) == entry.resourceId &&
                        (previous?.sortKey ?: archived?.sortKey) == entry.sortKey
                ) {
                    "Command identity cannot change."
                }
            } else {
                if (occupied(account, entry.partition, entry.resourceId))
                    return CommandInsertResult.ResourceOccupied
                missing.add(entry)
            }
        }
        if (missing.isEmpty()) return CommandInsertResult.AlreadyStored
        if (countAll() + missing.size > 512 || countAccount(account) + missing.size > 128)
            return CommandInsertResult.CapacityExceeded
        if (wake != null) {
            val current = mutationWake(wake.key)
            if (current?.generation == wake.generation) return null
            if (current == null && mutationWakeCount() >= wake.maxKeys) return null
            write(WorkWakeEntity(wake.key, wake.generation, true))
        }
        for (entry in missing) create(entry)
        return CommandInsertResult.Stored
    }

    @Transaction
    open suspend fun update(
        session: String,
        account: String,
        partition: String,
        id: String,
        revision: Long,
        state: String,
    ): Boolean = admitted(session, account) && change(account, partition, id, revision, state) == 1

    @Transaction
    open suspend fun remove(
        session: String,
        account: String,
        partition: String,
        id: String,
        revision: Long,
    ): Boolean = admitted(session, account) && delete(account, partition, id, revision) == 1

    @Transaction
    open suspend fun archived(
        session: String,
        account: String,
        partition: String,
        id: String,
    ): CommandArchiveEntity? =
        if (admitted(session, account)) completed(account, partition, id) else null

    @Transaction
    open suspend fun archive(session: String, expected: CommandEntity, evidence: String): Boolean {
        if (!admitted(session, expected.accountKey)) return false
        val candidate =
            CommandArchiveEntity(
                expected.accountKey,
                expected.partition,
                expected.id,
                expected.resourceId,
                expected.sortKey,
                expected.request,
                expected.state,
                expected.revision,
                evidence,
            )
        val retained = completed(expected.accountKey, expected.partition, expected.id)
        if (retained != null) return retained == candidate
        val current = record(expected.accountKey, expected.partition, expected.id) ?: return false
        if (current != expected) return false
        retain(candidate)
        check(delete(expected.accountKey, expected.partition, expected.id, expected.revision) == 1)
        return true
    }

    @Transaction
    open suspend fun archiveAbsent(
        session: String,
        entry: CommandEntity,
        evidence: String,
    ): Boolean {
        if (!admitted(session, entry.accountKey)) return false
        val candidate =
            CommandArchiveEntity(
                entry.accountKey,
                entry.partition,
                entry.id,
                entry.resourceId,
                entry.sortKey,
                entry.request,
                entry.state,
                entry.revision,
                evidence,
            )
        val previous = completed(entry.accountKey, entry.partition, entry.id)
        if (previous != null) return previous == candidate
        if (
            record(entry.accountKey, entry.partition, entry.id) != null ||
                occupied(entry.accountKey, entry.partition, entry.resourceId)
        )
            return false
        retain(candidate)
        return true
    }

    @Transaction
    open suspend fun archiveAndInsert(
        session: String,
        expected: CommandEntity,
        evidence: String,
        successor: CommandEntity,
    ): Boolean {
        require(
            expected.accountKey == successor.accountKey &&
                expected.partition == successor.partition &&
                expected.resourceId == successor.resourceId &&
                expected.id != successor.id &&
                successor.revision == 0L
        )
        if (!admitted(session, expected.accountKey)) return false
        if (completed(expected.accountKey, expected.partition, expected.id) != null) return false
        if (record(expected.accountKey, expected.partition, expected.id) != expected) return false
        if (
            record(successor.accountKey, successor.partition, successor.id) != null ||
                completed(successor.accountKey, successor.partition, successor.id) != null
        )
            return false
        retain(
            CommandArchiveEntity(
                expected.accountKey,
                expected.partition,
                expected.id,
                expected.resourceId,
                expected.sortKey,
                expected.request,
                expected.state,
                expected.revision,
                evidence,
            )
        )
        check(delete(expected.accountKey, expected.partition, expected.id, expected.revision) == 1)
        create(successor)
        return true
    }
}
