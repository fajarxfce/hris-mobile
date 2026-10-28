package dev.fajar.starter.workforce.presentation.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimePlanWorkingCopyRepository
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
import kotlinx.coroutines.flow.*

class OvertimeWorkingPresentationFixture(base: OvertimePresentationFixture) {
    var stored: OvertimePlanWorkingCopy? = null
    var beforeRead: suspend () -> Unit = {}
    var beforeSave: suspend (OvertimePlanWorkingCopy) -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var failure: Failure? = null
    var loseAcknowledgement = false
    var writes = 0
    var active = 0
    var maxActive = 0
    var observations = 0
    val mutations = mutableListOf<OvertimePlanWorkingCopy>()
    val copies =
        object : OvertimePlanWorkingCopyRepository {
            override fun observePage(access: WorkforceAccess, after: String?) = flow {
                beforeRead()
                emit(
                    AppResult.Success(
                        dev.fajar.starter.workforce.domain.overtime.entities.OvertimePage(
                            listOfNotNull(stored)
                        )
                    )
                )
            }

            override fun observe(
                access: WorkforceAccess,
                employeeId: String,
            ): Flow<AppResult<OvertimePlanWorkingCopy?>> = flow {
                observations++
                try {
                    beforeRead()
                    emit(AppResult.Success(stored))
                } finally {
                    observations--
                }
            }

            override suspend fun save(
                access: WorkforceAccess,
                expectedRevision: String?,
                copy: OvertimePlanWorkingCopy,
            ): AppResult<OvertimePlanWorkingCopy?> {
                active++
                maxActive = maxOf(maxActive, active)
                mutations += copy
                try {
                    beforeSave(copy)
                    failure?.let {
                        return AppResult.Failed(it)
                    }
                    val old = stored
                    if (old?.revision == copy.revision)
                        return AppResult.Success(
                            old.takeIf { it.copy(savedAt = copy.savedAt) == copy }
                        )
                    if (old?.revision != expectedRevision) return AppResult.Success(null)
                    stored = copy
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
                employeeId: String,
                expectedRevision: String,
            ): AppResult<Boolean> {
                beforeRemove()
                if (stored?.revision != expectedRevision) return AppResult.Success(false)
                stored = null
                return AppResult.Success(true)
            }
        }
    val open =
        OpenOvertimePlanWorkingCopy(
            copies,
            base.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val save =
        SaveOvertimePlanWorkingCopy(
            copies,
            base.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val discard =
        DiscardOvertimePlanWorkingCopy(
            copies,
            base.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
}
