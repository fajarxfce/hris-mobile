package dev.fajar.starter.people.domain.replica.entities

/** One complete published generation. Its observer owns the decoded records' lifetime. */
data class DirectorySnapshot(
    val checkpoint: DirectoryCheckpoint?,
    val records: List<DirectoryRecord>,
) {
    override fun toString() = "DirectorySnapshot([redacted])"
}
