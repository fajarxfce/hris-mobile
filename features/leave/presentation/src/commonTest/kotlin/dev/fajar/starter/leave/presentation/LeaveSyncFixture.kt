package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeaveRecordRepository
import dev.fajar.starter.leave.domain.usecases.SyncLeave
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository

class LeaveSyncFixture(val base: LeavePresentationFixture = LeavePresentationFixture()) {
    val replica = base.replica
    val collections = setOf(SyncCollection.LEAVE_REQUESTS, SyncCollection.LEAVE_BALANCES)
    val references =
        listOf(
            SyncReference(SyncCollection.LEAVE_REQUESTS, base.request.id, base.request.version),
            SyncReference(
                SyncCollection.LEAVE_BALANCES,
                base.balance.accountId,
                base.balance.version,
            ),
        )
    var bootstrapReads = 0
    var changeReads = 0
    var recordReads = 0
    val requestedCursors = mutableListOf<String?>()
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(collections, references, null, "head", base.clock.now())
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(
            SyncChangePage(
                if (changeReads > 1) references.map { SyncChange(it, SyncOperation.UPSERT) }
                else emptyList(),
                "next",
                false,
                false,
                5,
                base.clock.now(),
            )
        )
    }
    var requestCall: suspend (String) -> AppResult<LeaveReplicaRecord.Request> = {
        base.requestReads++
        when (val result = base.requestsCall(null)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    LeaveReplicaRecord.Request(result.value.items.single().fixtureDetails())
                )
        }
    }
    var historyCall: suspend (String, Long) -> AppResult<LeaveReplicaRecord.Request> = { id, _ ->
        requestCall(id)
    }
    var balanceCall: suspend (String) -> AppResult<LeaveReplicaRecord.Balance> = {
        base.balanceReads++
        base.years += base.balance.year
        when (val result = base.balancesCall(base.balance.year)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    LeaveReplicaRecord.Balance(base.employee.id, result.value.items.single())
                )
        }
    }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                check(collections == this@LeaveSyncFixture.collections && limit == 8)
                bootstrapReads++
                requestedCursors += cursor
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                check(collections == this@LeaveSyncFixture.collections && limit == 8)
                changeReads++
                requestedCursors += cursor
                return changesCall(cursor)
            }
        }
    val remote =
        object : LeaveRecordRepository {
            override suspend fun request(
                access: LeaveAccess,
                id: String,
                historyAfter: Long?,
            ): AppResult<LeaveReplicaRecord.Request> {
                recordReads++
                return if (historyAfter == null) requestCall(id) else historyCall(id, historyAfter)
            }

            override suspend fun balance(
                access: LeaveAccess,
                id: String,
            ): AppResult<LeaveReplicaRecord.Balance> {
                recordReads++
                return balanceCall(id)
            }
        }
    val sync =
        SyncLeave(
            feed,
            remote,
            base.remote,
            replica,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    suspend fun run() = sync(base.workspace.id)
}
