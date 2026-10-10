@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.mappers

import dev.fajar.starter.people.data.dto.*
import dev.fajar.starter.people.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun SelfEmploymentDirectoryDto.toDirectory(
    companyId: String,
    after: String?,
): SelfEmploymentDirectory {
    require(employments.items.size <= 20)
    val date = LocalDate.parse(asOf)
    TimeZone.of(timezone)
    val items = employments.items.map { it.toEmployment(companyId) }
    require(items.distinctBy { it.id }.size == items.size)
    require(items.distinctBy { it.employeeNumber }.size == items.size)
    val cursor = employments.nextCursor
    require(
        cursor == null ||
            (cursor.length in 1..32 &&
                items.isNotEmpty() &&
                cursor == items.last().employeeNumber &&
                cursor != after)
    )
    return SelfEmploymentDirectory(date, timezone, items, cursor)
}

fun EmploymentDto.toEmployment(expectedCompanyId: String): Employment {
    Uuid.parse(id)
    Uuid.parse(companyId)
    Uuid.parse(person.id)
    person.accountId?.let { Uuid.parse(it) }
    require(companyId == expectedCompanyId && employeeNumber.length in 1..32)
    require(person.legalName.isNotBlank() && person.legalName.length <= 200)
    require(person.email == null || person.email.length <= 254)
    require(version >= 0 && appliedRevision in 0..version)
    LocalDate.parse(terms.effectiveFrom)
    val start = LocalDate.parse(terms.startDate)
    val end = terms.endDate?.let { LocalDate.parse(it) }
    require(end == null || end >= start)
    return Employment(
        id,
        companyId,
        person.id,
        person.accountId,
        employeeNumber,
        person.legalName,
        person.email,
        when (terms.contract) {
            "PERMANENT" -> EmploymentContract.Permanent
            "FIXED_TERM" -> EmploymentContract.FixedTerm
            else -> error("Unknown employment contract.")
        },
        when (terms.status) {
            "PROBATION" -> EmploymentStatus.Probation
            "ACTIVE" -> EmploymentStatus.Active
            "SUSPENDED" -> EmploymentStatus.Suspended
            "ENDED" -> EmploymentStatus.Ended
            else -> error("Unknown employment status.")
        },
        start,
        end,
        version,
        appliedRevision,
    )
}

fun EmploymentDetailsDto.toDetails(
    companyId: String,
    employeeId: String,
    date: LocalDate,
): EmploymentDetails {
    require(employee.id == employeeId && LocalDate.parse(asOf) == date)
    return EmploymentDetails(
        date,
        employee.toEmployment(companyId),
        branch?.toAssignment(employee.terms.branchId),
        department?.toAssignment(employee.terms.departmentId),
        position?.toAssignment(employee.terms.positionId),
        costCenter?.toAssignment(employee.terms.costCenterId),
        manager?.let {
            Uuid.parse(it.id)
            require(it.id == employee.terms.managerId && it.employeeNumber.length in 1..32)
            require(it.legalName.isNotBlank() && it.legalName.length <= 200)
            EmploymentManager(it.id, it.employeeNumber, it.legalName, it.working)
        },
    )
}

fun EmploymentAssignmentDto.toAssignment(expectedId: String?): EmploymentAssignment {
    Uuid.parse(id)
    require(id == expectedId && code.length in 1..32 && name.isNotBlank() && name.length <= 200)
    return EmploymentAssignment(id, code, name, active)
}

fun PersonProfileDto.toProfile(): PersonProfile {
    Uuid.parse(personId)
    Uuid.parse(ownerCompanyId)
    accountId?.let { Uuid.parse(it) }
    require(
        legalName.isNotBlank() && legalName.length <= 200 && nationality.matches(Regex("[A-Z]{2}"))
    )
    require(email == null || email.length <= 254)
    require(version >= 0)
    return PersonProfile(
        personId,
        ownerCompanyId,
        accountId,
        legalName,
        birthDate?.let { LocalDate.parse(it) },
        nationality,
        email,
        version,
    )
}
