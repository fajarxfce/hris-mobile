package dev.fajar.starter.people.domain.profile.policies

import dev.fajar.starter.people.domain.profile.entities.*

/** Selects an already acquired profile; it does not renew authority or request data. */
fun selectSelfProfile(snapshot: SelfProfileSnapshot, employeeId: String?): SelfProfileView? {
    val checkpoint = snapshot.checkpoint ?: return null
    val records =
        snapshot.records.sortedWith(
            compareBy({ it.profile.details.employee.employeeNumber }, { it.id })
        )
    val chosen = records.firstOrNull { it.id == employeeId } ?: records.firstOrNull()
    return SelfProfileView(
        records.map { it.profile.details.employee },
        chosen?.id,
        chosen?.profile,
        requireNotNull(checkpoint.refreshedAt),
        employeeId,
    )
}
