package dev.fajar.starter.people.presentation.directory.mappers

import dev.fajar.starter.people.domain.entities.CompanyDirectory
import dev.fajar.starter.people.presentation.directory.EmployeeDirectoryContent
import dev.fajar.starter.people.presentation.directory.EmployeeRowState

/** Display-only projection; acquisition and directory eligibility stay in the domain. */
fun CompanyDirectory.toDirectoryContent() =
    EmployeeDirectoryContent(
        items.map { employee ->
            val words = employee.name.trim().split(Regex("\\s+"))
            val initials =
                listOf(words.firstOrNull(), words.lastOrNull().takeIf { words.size > 1 })
                    .mapNotNull { it?.firstOrNull(Char::isLetter) }
                    .joinToString("")
                    .uppercase()
                    .take(2)
            EmployeeRowState(
                employee.id,
                employee.name,
                employee.position,
                listOfNotNull(employee.department, employee.employeeNumber).joinToString(" · "),
                initials.ifEmpty { "?" },
            )
        },
        nextCursor,
    )
