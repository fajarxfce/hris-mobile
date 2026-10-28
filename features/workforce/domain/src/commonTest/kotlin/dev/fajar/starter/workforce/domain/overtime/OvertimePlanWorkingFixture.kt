package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeSyncPhase
import dev.fajar.starter.workforce.domain.overtime.policies.initialOvertimeCheckpoint
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimePlanWorkingCopyRepository
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
import kotlinx.coroutines.flow.flow

class OvertimePlanWorkingFixture(val overtime: OvertimeFixture = OvertimeFixture()) {
    val base = overtime.base
    var stored: OvertimePlanWorkingCopy? = null
    var beforeRead: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var removeFailure: Failure? = null
    var observations = 0
    var observers = 0
    var writes = 0
    var removals = 0
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

            override fun observe(access: WorkforceAccess, employeeId: String) = flow {
                observations++
                observers++
                try {
                    beforeRead()
                    emit(AppResult.Success(stored))
                } finally {
                    observers--
                }
            }

            override suspend fun save(
                access: WorkforceAccess,
                expectedRevision: String?,
                copy: OvertimePlanWorkingCopy,
            ): AppResult<OvertimePlanWorkingCopy?> {
                beforeWrite()
                writes++
                val previous = stored
                if (previous?.revision == copy.revision)
                    return AppResult.Success(
                        previous.takeIf { it.copy(savedAt = copy.savedAt) == copy }
                    )
                if (previous?.revision != expectedRevision) return AppResult.Success(null)
                stored = copy
                return AppResult.Success(copy)
            }

            override suspend fun remove(
                access: WorkforceAccess,
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
        OpenOvertimePlanWorkingCopy(
            copies,
            overtime.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val save =
        SaveOvertimePlanWorkingCopy(
            copies,
            overtime.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val discard =
        DiscardOvertimePlanWorkingCopy(
            copies,
            overtime.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    fun seedOwnership() {
        val checkpoint =
            initialOvertimeCheckpoint(overtime.access, "working-fixture", base.clock.now())
                .copy(
                    revision = 2,
                    phase = OvertimeSyncPhase.CHANGES,
                    cursor = "cursor/0",
                    published = true,
                    refreshedAt = base.clock.now(),
                )
        overtime.replicas.current = checkpoint
        overtime.replicas.published.value =
            AppResult.Success(
                OvertimeReplicaFixture.Published(checkpoint, base.directory, emptyList())
            )
    }
}
