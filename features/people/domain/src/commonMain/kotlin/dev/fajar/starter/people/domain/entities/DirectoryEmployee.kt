package dev.fajar.starter.people.domain.entities

/** Company directory identity, without private profile or contract fields. */
data class DirectoryEmployee(
    val id: String,
    val employeeNumber: String,
    val name: String,
    val position: String?,
    val department: String?,
) {
    override fun toString() = "DirectoryEmployee([redacted])"
}
