package dev.fajar.starter.workspace.domain.entities

/** Result of one finite acquisition, independent of command delivery and draft submission. */
interface CompanyDataSyncOutcome {
    val caughtUp: Boolean
}
