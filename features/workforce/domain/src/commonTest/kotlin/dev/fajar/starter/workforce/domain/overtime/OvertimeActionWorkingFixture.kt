package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimeActionWorkingCopyRepository
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.flow

class OvertimeActionWorkingFixture(val overtime: OvertimeFixture = OvertimeFixture()) {
    val base = overtime.base
    val records = mutableMapOf<Pair<String, OvertimeAction>, OvertimeActionWorkingCopy>()
    var beforeRead: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var removeFailure: Failure? = null
    var observations = 0
    var observers = 0
    var writes = 0
    var removals = 0
    val copies =
        object : OvertimeActionWorkingCopyRepository {
            override fun observePage(access: WorkforceAccess, after: String?) = flow {
                beforeRead()
                emit(
                    AppResult.Success(
                        dev.fajar.starter.workforce.domain.overtime.entities.OvertimePage(
                            records.values.toList()
                        )
                    )
                )
            }

            override fun observe(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
                action: OvertimeAction,
            ) = flow {
                observations++
                observers++
                try {
                    beforeRead()
                    emit(AppResult.Success(records[selection.requestId to action]))
                } finally {
                    observers--
                }
            }

            override suspend fun save(
                access: WorkforceAccess,
                expectedRevision: String?,
                copy: OvertimeActionWorkingCopy,
            ): AppResult<OvertimeActionWorkingCopy?> {
                beforeWrite()
                writes++
                val key = copy.requestId to copy.action
                val previous = records[key]
                if (previous?.revision == copy.revision)
                    return AppResult.Success(
                        previous.takeIf { it.copy(savedAt = copy.savedAt) == copy }
                    )
                if (previous?.revision != expectedRevision) return AppResult.Success(null)
                records[key] = copy
                return AppResult.Success(copy)
            }

            override suspend fun remove(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
                action: OvertimeAction,
                expectedRevision: String,
            ): AppResult<Boolean> {
                removals++
                beforeRemove()
                removeFailure?.let {
                    return AppResult.Failed(it)
                }
                val key = selection.requestId to action
                if (records[key]?.revision != expectedRevision) return AppResult.Success(false)
                records.remove(key)
                return AppResult.Success(true)
            }
        }
    val open =
        OpenOvertimeActionWorkingCopy(
            copies,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val save =
        SaveOvertimeActionWorkingCopy(
            copies,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val discard =
        DiscardOvertimeActionWorkingCopy(
            copies,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    fun seedOwnership() {
        overtime.detailRecords.value =
            AppResult.Success(
                OvertimeSnapshot(
                    overtime.access,
                    "working-fixture",
                    OvertimeRequestView(overtime.selection, overtime.details),
                    base.clock.now(),
                    base.clock.now() + 1.hours,
                )
            )
    }
}
