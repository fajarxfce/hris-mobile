@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.leave.data.datasources.LeavePreparationDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeavePreparationRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteLeavePreparationRepository(private val remote: LeavePreparationDataSource) :
    LeavePreparationRepository {
    override suspend fun types(access: LeaveAccess, selection: LeaveTypeSelection) = safeApiCall {
        Uuid.parse(access.companyId)
        remote
            .types(access.sessionId, access.companyId, selection.asOf.toString(), selection.after)
            .toTypes(selection)
    }

    override suspend fun preview(access: LeaveAccess, selection: LeaveDraftSelection) =
        safeApiCall {
            Uuid.parse(access.companyId)
            Uuid.parse(selection.employeeId)
            Uuid.parse(selection.typeId)
            remote
                .preview(
                    access.sessionId,
                    access.companyId,
                    LeavePreviewRequest(
                        selection.employeeId,
                        selection.typeId,
                        selection.days.map {
                            LeaveRequestedDayDto(it.date.toString(), it.portion.name)
                        },
                    ),
                )
                .toPreview(selection)
        }
}
