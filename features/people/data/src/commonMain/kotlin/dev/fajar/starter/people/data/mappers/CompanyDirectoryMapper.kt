@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.mappers

import dev.fajar.starter.people.data.dto.CompanyDirectoryDto
import dev.fajar.starter.people.domain.entities.CompanyDirectory
import dev.fajar.starter.people.domain.entities.DirectoryEmployee
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun CompanyDirectoryDto.toCompanyDirectory(after: String?): CompanyDirectory {
    require(employees.items.size <= 20)
    val date = LocalDate.parse(asOf)
    TimeZone.of(timezone)
    val items =
        employees.items.map {
            require(Uuid.parse(it.id).toString() == it.id)
            require(it.employeeNumber.isNotBlank() && it.employeeNumber.length <= 32)
            require(it.name.isNotBlank() && it.name.length <= 200)
            require(it.position == null || (it.position.isNotBlank() && it.position.length <= 200))
            require(
                it.department == null || (it.department.isNotBlank() && it.department.length <= 200)
            )
            DirectoryEmployee(it.id, it.employeeNumber, it.name, it.position, it.department)
        }
    require(items.distinctBy { it.id }.size == items.size)
    require(items.distinctBy { it.employeeNumber }.size == items.size)
    require(items.none { it.employeeNumber == after })
    val next = employees.nextCursor
    require(
        next == null || (items.isNotEmpty() && next == items.last().employeeNumber && next != after)
    )
    return CompanyDirectory(date, timezone, items, next)
}
