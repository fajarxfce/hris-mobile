package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.leave.domain.working.usecases.*
import kotlinx.coroutines.flow.*

class LeaveWorkingPresentationFixture(base: LeavePresentationFixture) {
    var stored: LeaveWorkingCopy? = null
    var beforeRead: suspend () -> Unit = {}
    var beforeSave: suspend (LeaveWorkingCopy) -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var failure: Failure? = null
    var loseAcknowledgement = false
    var writes = 0
    var active = 0
    var maxActive = 0
    var observations = 0
    val mutations = mutableListOf<LeaveWorkingCopy>()
    val copies =
        object : LeaveWorkingCopyRepository {
            override fun observe(
                access: LeaveAccess,
                employeeId: String,
            ): Flow<AppResult<LeaveWorkingCopy?>> = flow {
                observations++
                try {
                    beforeRead()
                    emit(AppResult.Success(stored))
                } finally {
                    observations--
                }
            }

            override suspend fun save(
                access: LeaveAccess,
                expectedRevision: String?,
                copy: LeaveWorkingCopy,
            ): AppResult<LeaveWorkingCopy?> {
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
                access: LeaveAccess,
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
        OpenLeaveWorkingCopy(
            copies,
            base.replica,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val save =
        SaveLeaveWorkingCopy(
            copies,
            base.replica,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val discard =
        DiscardLeaveWorkingCopy(
            copies,
            base.replica,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
}
