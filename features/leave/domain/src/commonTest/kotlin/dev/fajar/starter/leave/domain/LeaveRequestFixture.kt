package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class LeaveRequestFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val base = LeaveFixture(instant)
    val selection = LeaveRequestSelection(base.request.id)
    val details =
        LeaveRequestDetails(
            base.request.id,
            base.employee.id,
            base.employee.employeeNumber,
            "Alex",
            base.session.user.id,
            base.clock.now(),
            LeaveRequestPolicy(base.balance.typeId, "ANNUAL", 0, "Annual leave", true, true, false),
            listOf(
                LeaveDay(
                    base.request.from,
                    LeavePortion.FULL,
                    base.clock.now(),
                    base.clock.now() + 8.hours,
                    480,
                    480,
                )
            ),
            LeaveDays(2),
            "Family appointment",
            LeaveStatus.PENDING,
            0,
            LeaveWorkflow(
                "88888888-8888-8888-8888-888888888888",
                LeaveWorkflowStatus.PENDING,
                0,
                0,
            ),
            null,
            LeavePage(
                listOf(
                    LeaveChange(
                        0,
                        LeaveChangeKind.SUBMITTED,
                        LeaveStatus.PENDING,
                        base.clock.now(),
                        "Family appointment",
                    )
                )
            ),
            setOf(LeaveAction.WITHDRAW),
            emptyList(),
        )
    val records = MutableStateFlow<AppResult<LeaveRequestSnapshot?>>(AppResult.Success(null))
    var requestCall: suspend (LeaveRequestSelection) -> AppResult<LeaveRequestDetails> = {
        AppResult.Success(details)
    }
    var generationWait: suspend () -> Unit = {}
    var publicationWait: suspend () -> Unit = {}
    var reads = 0
    var writes = 0
    var clears = 0
    var observers = 0
    var rejectWrite = false
    var cleanupFails = false
    val remote =
        object : LeaveRepository by base.remote {
            override suspend fun details(
                access: LeaveAccess,
                selection: LeaveRequestSelection,
            ): AppResult<LeaveRequestDetails> {
                reads++
                return requestCall(selection)
            }
        }
    val cache =
        object : LeaveRequestCacheRepository {
            override fun observeRequest(
                access: LeaveAccess
            ): Flow<AppResult<LeaveRequestSnapshot?>> = flow {
                observers++
                try {
                    emitAll(records)
                } finally {
                    observers--
                }
            }

            override suspend fun requestGeneration(access: LeaveAccess): AppResult<String?> {
                generationWait()
                return AppResult.Success((records.value as? AppResult.Success)?.value?.generation)
            }

            override suspend fun replaceRequest(
                access: LeaveAccess,
                expectedGeneration: String?,
                snapshot: LeaveRequestSnapshot?,
            ): AppResult<Boolean> {
                publicationWait()
                if (snapshot == null && cleanupFails)
                    return AppResult.Failed(Failure(FailureKind.Storage, "Storage unavailable"))
                val current = (records.value as? AppResult.Success)?.value
                if (rejectWrite || expectedGeneration != current?.generation)
                    return AppResult.Success(false)
                if (snapshot == null) clears++ else writes++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val refresh =
        RefreshLeaveRequest(remote, cache, base.sessions, base.workspaces, base.clock, base.build)
    val observe = ObserveLeaveRequest(cache, base.sessions, base.workspaces, base.clock, base.build)

    suspend fun load(selected: LeaveRequestSelection = selection) =
        refresh(base.workspace.id, selected)
}
