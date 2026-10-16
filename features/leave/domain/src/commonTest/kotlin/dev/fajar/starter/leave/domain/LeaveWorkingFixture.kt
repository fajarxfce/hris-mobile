package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.leave.domain.policies.admitLeave
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.leave.domain.working.usecases.*
import kotlinx.coroutines.flow.flow

class LeaveWorkingFixture(val base: LeaveFixture = LeaveFixture()) {
    fun access() =
        (admitLeave(base.workspace.id, base.workspace, base.session, base.build, base.clock.now())
                as AppResult.Success)
            .value

    var stored: LeaveWorkingCopy? = null
    var beforeRead: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var removeFailure: dev.fajar.starter.common.result.Failure? = null
    var observations = 0
    var writes = 0
    var removals = 0
    val copies =
        object : LeaveWorkingCopyRepository {
            override fun observe(access: LeaveAccess, employeeId: String) = flow {
                observations++
                beforeRead()
                emit(AppResult.Success(stored))
            }

            override suspend fun save(
                access: LeaveAccess,
                expectedRevision: String?,
                copy: LeaveWorkingCopy,
            ): AppResult<LeaveWorkingCopy?> {
                beforeWrite()
                writes++
                if (stored?.revision != expectedRevision) return AppResult.Success(null)
                stored = copy
                return AppResult.Success(copy)
            }

            override suspend fun remove(
                access: LeaveAccess,
                employeeId: String,
                expectedRevision: String,
            ): AppResult<Boolean> {
                removals++
                beforeRemove()
                removeFailure?.let {
                    return AppResult.Failed(it)
                }
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
