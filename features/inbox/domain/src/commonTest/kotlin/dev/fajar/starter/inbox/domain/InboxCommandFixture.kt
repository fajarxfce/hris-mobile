package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.belongsTo
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.*

class InboxCommandFixture(val inbox: InboxFixture = InboxFixture()) {
    val records = MutableStateFlow<List<InboxCommandRecord>>(emptyList())
    val transmitted = mutableListOf<InboxCommand>()
    var beforeInsert: suspend () -> Unit = {}
    var beforeObserve: suspend () -> Unit = {}
    var updateFailure: Failure? = null
    var transmit: suspend (InboxCommand) -> AppResult<InboxActionReceipt> = {
        AppResult.Success(InboxActionReceipt(it.itemId, it.expectedVersion + 1))
    }
    var canonical = inbox.item
    var itemCalls = 0
    var itemFailure: Failure? = null

    init {
        publish(inbox.item)
    }

    fun publish(item: InboxItem) {
        val checkpoint =
            InboxCheckpoint(
                inbox.access,
                "generation",
                1,
                InboxSyncPhase.CHANGES,
                "cursor",
                true,
                inbox.clock.now(),
                inbox.clock.now() + 1.hours,
                null,
            )
        inbox.checkpoint = checkpoint
        inbox.snapshot.value = AppResult.Success(InboxCachePage(checkpoint, listOf(item), null))
    }

    val commands =
        object : InboxCommandRepository {
            override fun observe(access: InboxAccess, itemId: String) =
                records.map { rows ->
                    beforeObserve()
                    AppResult.Success(
                        rows.singleOrNull {
                            it.command.itemId == itemId && it.command.belongsTo(access)
                        }
                    )
                }

            override suspend fun find(access: InboxAccess, id: String) =
                AppResult.Success(
                    records.value.singleOrNull {
                        it.command.id == id && it.command.belongsTo(access)
                    }
                )

            override suspend fun page(access: InboxAccess, cursor: String?) =
                AppResult.Success(
                    InboxCommandPage(records.value.filter { it.command.belongsTo(access) }, null)
                )

            override suspend fun insert(
                access: InboxAccess,
                command: InboxCommand,
            ): AppResult<InboxCommandAdmission> {
                beforeInsert()
                if (
                    records.value.any {
                        it.command.itemId == command.itemId && it.command.belongsTo(access)
                    }
                )
                    return AppResult.Success(InboxCommandAdmission.OCCUPIED)
                records.value += InboxCommandRecord(command)
                return AppResult.Success(InboxCommandAdmission.STORED)
            }

            override suspend fun update(
                access: InboxAccess,
                expected: InboxCommandRecord,
                outcome: InboxCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                updateFailure?.let {
                    return AppResult.Failed(it)
                }
                if (expected !in records.value) return AppResult.Success(false)
                records.value =
                    records.value.map {
                        if (it == expected)
                            it.copy(
                                revision = it.revision + 1,
                                attempts = attempts,
                                outcome = outcome,
                            )
                        else it
                    }
                return AppResult.Success(true)
            }

            override suspend fun remove(
                access: InboxAccess,
                expected: InboxCommandRecord,
            ): AppResult<Boolean> {
                val found = expected in records.value
                records.value -= expected
                return AppResult.Success(found)
            }
        }
    val remote =
        object : InboxRepository {
            override suspend fun unreadCount(
                sessionId: String,
                companyId: String,
            ): AppResult<Long> = error("Command replay must not read a navigation count.")

            override suspend fun item(
                sessionId: String,
                companyId: String,
                id: String,
            ): AppResult<InboxItem> {
                itemCalls++
                return itemFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(canonical)
            }

            override suspend fun act(
                sessionId: String,
                command: InboxCommand,
            ): AppResult<InboxActionReceipt> {
                transmitted += command
                return transmit(command)
            }
        }
    val prepare =
        PrepareInboxAction(commands, inbox.cache, inbox.sessions, inbox.workspaces, inbox.clock)
    val replay =
        ReplayInboxAction(
            commands,
            remote,
            inbox.cache,
            inbox.sessions,
            inbox.workspaces,
            inbox.clock,
            inbox.build,
        )
    val review =
        ReviewInboxAction(
            commands,
            remote,
            inbox.cache,
            inbox.sessions,
            inbox.workspaces,
            inbox.clock,
        )
    val observe = ObserveInboxAction(commands, inbox.sessions, inbox.workspaces, inbox.clock)

    suspend fun queue(action: InboxAction = InboxAction.READ, version: Long = inbox.item.version) =
        prepare(inbox.workspace.id, inbox.item.id, version, action)
}
