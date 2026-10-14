package dev.fajar.starter.people.domain.replica.entities

data class DirectorySelection(
    val query: String = "",
    val after: String? = null,
    val departmentId: String? = null,
    val departmentQuery: String = "",
) {
    override fun toString() = "DirectorySelection([redacted])"
}
