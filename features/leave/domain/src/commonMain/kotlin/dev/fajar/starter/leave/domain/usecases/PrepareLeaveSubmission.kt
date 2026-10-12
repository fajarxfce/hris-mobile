@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveSubmissionCommandRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class PrepareLeaveSubmission(
    private val commands: LeaveSubmissionCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        prepared: LeavePreparation,
        reason: String,
        attachmentRevisionIds: List<String> = emptyList(),
    ): AppResult<LeaveSubmissionRecord> {
        if (prepared.selection.days.size !in 1..366) return leaveFailure("invalid_leave_request")
        if (attachmentRevisionIds.size > MAX_LEAVE_ATTACHMENTS)
            return leaveFailure("invalid_leave_attachments")
        val preparation =
            prepared.copy(
                selection =
                    prepared.selection.copy(
                        days = prepared.selection.days.toList().sortedBy { it.date }
                    )
            )
        val attachments = attachmentRevisionIds.toList().sorted()
        return withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val valid =
                validateLeaveSubmission(preparation, access, reason, attachments, clock.now())
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val previous = commands.observe(access, preparation.selection.employeeId).first()
            currentCoroutineContext().ensureActive()
            val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val stillValid =
                validateLeaveSubmission(preparation, access, reason, attachments, clock.now())
            if (stillValid is AppResult.Failed) return@withTimeoutOrNull stillValid
            val existing =
                when (previous) {
                    is AppResult.Failed -> return@withTimeoutOrNull previous
                    is AppResult.Success -> previous.value
                }
            val normalized = reason.trim()
            if (existing != null)
                return@withTimeoutOrNull retained(
                    existing,
                    access,
                    preparation,
                    normalized,
                    attachments,
                )
            val command =
                LeaveSubmissionCommand(
                    Uuid.random().toString(),
                    access.accountId,
                    access.audience,
                    access.companyId,
                    Uuid.random().toString(),
                    preparation.selection,
                    preparation.preview.revision,
                    preparation.preview.policy.name,
                    preparation.preview.charged,
                    normalized,
                    attachments,
                    clock.now(),
                )
            val insertion = commands.insert(access, command)
            currentCoroutineContext().ensureActive()
            val continuation = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
            when (insertion) {
                is AppResult.Failed -> return@withTimeoutOrNull insertion
                is AppResult.Success ->
                    when (insertion.value) {
                        LeaveCommandAdmission.CAPACITY_EXCEEDED ->
                            return@withTimeoutOrNull leaveFailure(
                                "client.command_queue_full",
                                FailureKind.Storage,
                            )
                        LeaveCommandAdmission.SCOPE_CHANGED ->
                            return@withTimeoutOrNull leaveFailure(
                                "client.leave_scope_changed",
                                FailureKind.AccessDenied,
                            )
                        LeaveCommandAdmission.STORED,
                        LeaveCommandAdmission.OCCUPIED -> Unit
                    }
            }
            val stored = commands.observe(access, preparation.selection.employeeId).first()
            currentCoroutineContext().ensureActive()
            val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (stored) {
                is AppResult.Failed -> stored
                is AppResult.Success ->
                    retained(stored.value, access, preparation, normalized, attachments)
            }
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)
    }

    private fun retained(
        record: LeaveSubmissionRecord?,
        access: LeaveAccess,
        preparation: LeavePreparation,
        reason: String,
        attachments: List<String>,
    ): AppResult<LeaveSubmissionRecord> =
        if (
            record != null &&
                record.command.belongsTo(access) &&
                record.command.selection == preparation.selection &&
                record.command.previewRevision == preparation.preview.revision &&
                record.command.reason == reason &&
                record.command.attachmentRevisionIds == attachments
        )
            AppResult.Success(record)
        else leaveFailure("client.leave_submission_pending", FailureKind.Conflict)

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            if (session == null) null
            else
                when (val result = workspaces.current(session.id)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return when (
            val admitted = admitLeave(workspaceId, workspace, latest, build, clock.now())
        ) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                when (val permitted = admitSelfLeavePreparation(admitted.value)) {
                    is AppResult.Failed -> permitted
                    is AppResult.Success -> admitted
                }
        }
    }
}
