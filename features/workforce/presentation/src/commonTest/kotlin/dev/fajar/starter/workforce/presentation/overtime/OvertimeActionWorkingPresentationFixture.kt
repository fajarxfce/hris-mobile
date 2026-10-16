package dev.fajar.starter.workforce.presentation.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimeActionWorkingCopyRepository
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
import kotlinx.coroutines.flow.*

class OvertimeActionWorkingPresentationFixture(private val base: OvertimePresentationFixture) {
    val records = mutableMapOf<Pair<String, OvertimeAction>, OvertimeActionWorkingCopy>()

    fun stored(action: OvertimeAction) = records[base.selection.requestId to action]

    var beforeRead: suspend () -> Unit = {}
    var beforeSave: suspend (OvertimeActionWorkingCopy) -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var failure: Failure? = null
    var loseAcknowledgement = false
    var writes = 0
    var active = 0
    var maxActive = 0
    var observations = 0
    val mutations = mutableListOf<OvertimeActionWorkingCopy>()
    val copies =
        object : OvertimeActionWorkingCopyRepository {
            override fun observe(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
                action: OvertimeAction,
            ): Flow<AppResult<OvertimeActionWorkingCopy?>> = flow {
                observations++
                try {
                    beforeRead()
                    emit(AppResult.Success(records[selection.requestId to action]))
                } finally {
                    observations--
                }
            }

            override suspend fun save(
                access: WorkforceAccess,
                expectedRevision: String?,
                copy: OvertimeActionWorkingCopy,
            ): AppResult<OvertimeActionWorkingCopy?> {
                active++
                maxActive = maxOf(maxActive, active)
                mutations += copy
                try {
                    beforeSave(copy)
                    failure?.let {
                        return AppResult.Failed(it)
                    }
                    val key = copy.requestId to copy.action
                    val old = records[key]
                    if (old?.revision == copy.revision)
                        return AppResult.Success(
                            old.takeIf { it.copy(savedAt = copy.savedAt) == copy }
                        )
                    if (old?.revision != expectedRevision) return AppResult.Success(null)
                    records[key] = copy
                    writes++
                    if (loseAcknowledgement) {
                        loseAcknowledgement = false
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Fixture acknowledgement lost")
                        )
                    }
                    return AppResult.Success(copy)
                } finally {
                    active--
                }
            }

            override suspend fun remove(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
                action: OvertimeAction,
                expectedRevision: String,
            ): AppResult<Boolean> {
                beforeRemove()
                val key = selection.requestId to action
                if (records[key]?.revision != expectedRevision) return AppResult.Success(false)
                records.remove(key)
                return AppResult.Success(true)
            }
        }
    val open =
        OpenOvertimeActionWorkingCopy(
            copies,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val save =
        SaveOvertimeActionWorkingCopy(
            copies,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val discard =
        DiscardOvertimeActionWorkingCopy(
            copies,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
}
