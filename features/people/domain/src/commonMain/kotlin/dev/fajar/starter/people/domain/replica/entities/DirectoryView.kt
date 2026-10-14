package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.people.domain.entities.CompanyDirectory
import dev.fajar.starter.people.domain.entities.DirectoryDepartments
import kotlin.time.Instant

data class DirectoryView(
    val page: CompanyDirectory,
    val departments: DirectoryDepartments,
    val refreshedAt: Instant,
    val selection: DirectorySelection,
) {
    override fun toString() = "DirectoryView([redacted])"
}
