package dev.fajar.starter.people.domain.profile.entities

/** One complete published generation. Its observer owns the decoded records' lifetime. */
data class SelfProfileSnapshot(
    val checkpoint: SelfProfileCheckpoint?,
    val records: List<SelfProfileRecord>,
) {
    override fun toString() = "SelfProfileSnapshot([redacted])"
}
