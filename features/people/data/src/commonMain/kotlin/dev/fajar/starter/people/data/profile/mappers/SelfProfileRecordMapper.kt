@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.profile.mappers

import dev.fajar.starter.people.data.dto.*
import dev.fajar.starter.people.data.mappers.*
import dev.fajar.starter.people.data.profile.dto.SelfProfileRecordDto
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.profile.entities.SelfProfileRecord
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun SelfProfileRecordDto.toRecord(
    expectedId: String,
    companyId: String,
    accountId: String,
): SelfProfileRecord {
    require(id == expectedId && Uuid.parse(id).toString() == id && version >= 0)
    TimeZone.of(timezone)
    val details = employment.toDetails(companyId, id, LocalDate.parse(employment.asOf))
    val person = profile.toProfile()
    require(details.employee.accountId == accountId && person.accountId == accountId)
    require(
        details.employee.personId == person.personId &&
            details.employee.legalName == person.legalName &&
            details.employee.email == person.email
    )
    // Stored snapshots retain every term reference rather than inventing missing assignments.
    val terms = employment.employee.terms
    require((terms.branchId == null) == (details.branch == null))
    require((terms.departmentId == null) == (details.department == null))
    require((terms.positionId == null) == (details.position == null))
    require((terms.costCenterId == null) == (details.costCenter == null))
    require((terms.managerId == null) == (details.manager == null))
    return SelfProfileRecord(id, version, timezone, EmploymentProfile(details, person))
}

fun SelfProfileRecord.toReplicaDto(): SelfProfileRecordDto {
    val details = profile.details
    val employee = details.employee
    val person = profile.person
    return SelfProfileRecordDto(
        id,
        version,
        timezone,
        EmploymentDetailsDto(
            details.asOf.toString(),
            EmploymentDto(
                employee.id,
                employee.companyId,
                employee.employeeNumber,
                PersonSummaryDto(
                    employee.personId,
                    employee.accountId,
                    employee.legalName,
                    employee.email,
                ),
                EmploymentTermsDto(
                    employee.effectiveFrom.toString(),
                    when (employee.contract) {
                        EmploymentContract.Permanent -> "PERMANENT"
                        EmploymentContract.FixedTerm -> "FIXED_TERM"
                    },
                    employee.startDate.toString(),
                    employee.endDate?.toString(),
                    when (employee.status) {
                        EmploymentStatus.Probation -> "PROBATION"
                        EmploymentStatus.Active -> "ACTIVE"
                        EmploymentStatus.Suspended -> "SUSPENDED"
                        EmploymentStatus.Ended -> "ENDED"
                    },
                    details.branch?.id,
                    details.department?.id,
                    details.position?.id,
                    details.costCenter?.id,
                    details.manager?.id,
                ),
                employee.version,
                employee.appliedRevision,
            ),
            details.branch?.toSelfProfileDto(),
            details.department?.toSelfProfileDto(),
            details.position?.toSelfProfileDto(),
            details.costCenter?.toSelfProfileDto(),
            details.manager?.let {
                EmploymentManagerDto(it.id, it.employeeNumber, it.legalName, it.working)
            },
        ),
        PersonProfileDto(
            person.personId,
            person.ownerCompanyId,
            person.accountId,
            person.legalName,
            person.birthDate?.toString(),
            person.nationality,
            person.email,
            person.version,
        ),
    )
}

fun EmploymentAssignment.toSelfProfileDto() = EmploymentAssignmentDto(id, code, name, active)
