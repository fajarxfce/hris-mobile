@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.replica.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun validateDirectorySelection(selection: DirectorySelection): AppResult<Unit> =
    if (
        selection.query.length > 120 ||
            selection.departmentQuery.length > 120 ||
            (selection.after != null &&
                (selection.after.isBlank() || selection.after.length > 32)) ||
            (selection.departmentId != null &&
                Uuid.parseOrNull(selection.departmentId)?.toString() != selection.departmentId)
    )
        directoryFailure("invalid_page")
    else AppResult.Success(Unit)

/** Filters a complete local projection; it never starts acquisition or changes read admission. */
fun selectDirectory(snapshot: DirectorySnapshot, selection: DirectorySelection): DirectoryView? {
    val checkpoint = snapshot.checkpoint ?: return null
    val employees = snapshot.records.asSequence().map { it.employee }
    val query = selection.query.trim()
    val matches =
        employees
            .filter { employee ->
                (selection.departmentId == null ||
                    employee.departmentId == selection.departmentId) &&
                    (selection.after == null || employee.employeeNumber > selection.after) &&
                    (query.isEmpty() ||
                        employee.name.contains(query, ignoreCase = true) ||
                        employee.employeeNumber.contains(query, ignoreCase = true))
            }
            .sortedWith(compareBy({ it.employeeNumber }, { it.id }))
            .take(21)
            .toList()
    val items = matches.take(20)
    val departmentQuery = selection.departmentQuery.trim()
    val departments =
        employees
            .mapNotNull { employee ->
                employee.departmentId?.let {
                    DirectoryDepartment(it, requireNotNull(employee.department))
                }
            }
            .sortedWith(compareBy({ it.name }, { it.id }))
            .distinctBy { it.id }
            .filter { it.name.contains(departmentQuery, ignoreCase = true) }
            .take(51)
            .toList()
    val refreshedAt = requireNotNull(checkpoint.refreshedAt)
    return DirectoryView(
        CompanyDirectory(
            refreshedAt.toLocalDateTime(TimeZone.of(checkpoint.access.timezone)).date,
            checkpoint.access.timezone,
            items,
            items.lastOrNull()?.employeeNumber?.takeIf { matches.size > 20 },
        ),
        DirectoryDepartments(departments.take(50), departments.size > 50),
        refreshedAt,
        selection,
    )
}
